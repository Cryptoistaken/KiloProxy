package net.typeblog.socks.util

import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.FileProvider
import net.typeblog.socks.BuildConfig
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Checks the public GitHub release feed for a newer build and installs the APK.
 * Both [check] and [downloadAndInstall] are synchronous and MUST be called
 * from a background thread.
 */
object UpdateChecker {

    data class UpdateInfo(
        val versionCode: Int,
        val tag: String,
        val apkUrl: String,
        val sizeBytes: Long,
        val body: String
    )

    private const val UPDATE_URL =
        "https://api.github.com/repos/Cryptoistaken/KiloProxy/releases/latest"
    private const val USER_AGENT = "KiloProxy-Updater"
    private const val TIMEOUT_MILLIS = 8000
    private const val DOWNLOAD_CONNECT_TIMEOUT = 30_000
    private const val DOWNLOAD_READ_TIMEOUT = 300_000
    private const val MAX_RETRIES = 2
    private const val BUFFER_SIZE = 8192
    private const val TAG = "KiloProxyUpdate"

    fun check(): UpdateInfo? {
        Log.d(TAG, "check() called, installed versionCode = ${BuildConfig.VERSION_CODE}")
        val info = fetchLatestNotes() ?: run {
            Log.d(TAG, "check() -> fetchLatestNotes() returned null")
            return null
        }
        if (info.versionCode <= BuildConfig.VERSION_CODE) {
            Log.d(TAG, "check() -> latest ${info.tag} (code ${info.versionCode}) <= installed, no update")
            return null
        }
        Log.d(TAG, "check() -> update available: ${info.tag} (code ${info.versionCode}), apk = ${info.apkUrl}")
        return info
    }

    /**
     * Fetches the latest release without the version gate — used to display
     * the release notes ("What's new") regardless of the installed version.
     * Synchronous, MUST be called from a background thread.
     */
    fun fetchLatestNotes(): UpdateInfo? {
        var connection: HttpURLConnection? = null
        return try {
            Log.d(TAG, "fetchLatestNotes() -> GET $UPDATE_URL")
            connection = URL(UPDATE_URL).openConnection() as HttpURLConnection
            connection.connectTimeout = TIMEOUT_MILLIS
            connection.readTimeout = TIMEOUT_MILLIS
            connection.setRequestProperty("User-Agent", USER_AGENT)

            val status = connection.responseCode
            Log.d(TAG, "fetchLatestNotes() -> HTTP $status")
            if (status != HttpURLConnection.HTTP_OK) {
                val err = connection.errorStream?.bufferedReader(Charsets.UTF_8)?.readText()
                Log.e(TAG, "fetchLatestNotes() -> non-200 response, body: ${err?.take(500)}")
                return null
            }

            val body = connection.inputStream
                .bufferedReader(Charsets.UTF_8)
                .use { it.readText() }
            Log.d(TAG, "fetchLatestNotes() -> body ${body.length} chars, parsing...")
            parseRelease(JSONObject(body))
        } catch (e: Exception) {
            Log.e(TAG, "fetchLatestNotes() -> exception: ${e::class.simpleName}: ${e.message}", e)
            null
        } finally {
            connection?.disconnect()
        }
    }

    private fun parseRelease(json: JSONObject): UpdateInfo? {
        // Tags are pushed as v<versionCode> — anything else is not for us.
        val tag = json.optString("tag_name")
        if (!tag.matches(Regex("""^v(\d+)$"""))) {
            Log.w(TAG, "parseRelease() -> tag '$tag' does not match v<versionCode>")
            return null
        }

        val versionCode = tag.drop(1).toIntOrNull() ?: return null

        val assets = json.optJSONArray("assets") ?: run {
            Log.w(TAG, "parseRelease() -> no assets array in release $tag")
            return null
        }
        if (assets.length() == 0) return null

        // Prefer the arm64 build (device ABI), fall back to the first asset.
        val arm64Asset = (0 until assets.length())
            .map { assets.getJSONObject(it) }
            .firstOrNull { it.optString("name").contains("arm64") }
        val asset = arm64Asset ?: assets.getJSONObject(0)
        val apkUrl = asset.optString("browser_download_url")
        if (apkUrl.isEmpty()) return null
        if (!apkUrl.startsWith("https://")) {
            Log.w(TAG, "parseRelease() -> non-https apkUrl rejected: $apkUrl")
            return null
        }

        Log.d(TAG, "parseRelease() -> picked asset '${asset.optString("name")}' (${asset.optLong("size")} bytes) from release $tag")
        return UpdateInfo(
            versionCode = versionCode,
            tag = tag,
            apkUrl = apkUrl,
            sizeBytes = asset.optLong("size"),
            body = json.optString("body")
        )
    }

    /**
     * Downloads the APK to cache and launches the package installer.
     *
     * [totalBytes] is the expected download size (used to compute progress). When
     * provided (> 0), [onProgress] is invoked on the calling thread with a
     * 0f..1f fraction at regular intervals while bytes stream in.
     * Synchronous, MUST be called from a background thread. Returns an error
     * message on failure, or null once the installer has been launched.
     */
    fun downloadAndInstall(
        context: Context,
        url: String,
        totalBytes: Long = 0L,
        onProgress: ((Float) -> Unit)? = null
    ): String? {
        if (!url.startsWith("https://")) return "Update URL must use HTTPS"
        var lastException: Exception? = null
        repeat(MAX_RETRIES) { attempt ->
            var connection: HttpURLConnection? = null
            try {
                val file = File(context.cacheDir, "update.apk")
                if (file.exists()) file.delete()

                Log.d(TAG, "downloadAndInstall() -> attempt ${attempt + 1}/$MAX_RETRIES, GET $url")
                connection = URL(url).openConnection() as HttpURLConnection
                connection.connectTimeout = DOWNLOAD_CONNECT_TIMEOUT
                connection.readTimeout = DOWNLOAD_READ_TIMEOUT
                connection.setRequestProperty("User-Agent", USER_AGENT)

                val status = connection.responseCode
                Log.d(TAG, "downloadAndInstall() -> HTTP $status")
                if (status != HttpURLConnection.HTTP_OK) {
                    val err = "HTTP $status"
                    val body = connection.errorStream?.bufferedReader(Charsets.UTF_8)?.readText()
                    Log.e(TAG, "downloadAndInstall() -> $err, body: ${body?.take(300)}")
                    connection.disconnect()
                    if (attempt < MAX_RETRIES - 1) {
                        Thread.sleep(1000L * (attempt + 1))
                        return@repeat
                    }
                    return "Download failed ($err)"
                }

                var downloaded = 0L
                var lastReportedPct = -1
                connection.inputStream.use { input ->
                    file.outputStream().use { output ->
                        val buffer = ByteArray(BUFFER_SIZE)
                        var read = input.read(buffer)
                        while (read != -1) {
                            output.write(buffer, 0, read)
                            downloaded += read
                            if (totalBytes > 0) {
                                val pct = (downloaded * 100 / totalBytes).toInt()
                                if (pct != lastReportedPct) {
                                    lastReportedPct = pct
                                    onProgress?.invoke((pct / 100f).coerceIn(0f, 1f))
                                }
                            }
                            read = input.read(buffer)
                        }
                    }
                }

                val uri = FileProvider.getUriForFile(context, "${context.packageName}.provider", file)
                Log.d(TAG, "downloadAndInstall() -> downloaded ${file.length()} bytes to $file, launching installer")
                val intent = Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(uri, "application/vnd.android.package-archive")
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                try {
                    context.startActivity(intent)
                } catch (e: Exception) {
                    Log.e(TAG, "downloadAndInstall() -> startActivity(installer) threw: ${e::class.simpleName}: ${e.message}", e)
                    return "Could not open installer: ${e.message}"
                }
                return null
            } catch (e: Exception) {
                lastException = e
                Log.w(TAG, "downloadAndInstall() -> attempt ${attempt + 1} failed: ${e::class.simpleName}: ${e.message}", e)
                connection?.disconnect()
                if (attempt < MAX_RETRIES - 1) {
                    Thread.sleep(1000L * (attempt + 1))
                }
            }
        }
        return lastException?.message ?: "Update failed"
    }
}