package net.typeblog.socks.util

import android.content.Context
import android.content.Intent
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
    private const val BUFFER_SIZE = 8192

    fun check(): UpdateInfo? {
        val info = fetchLatestNotes() ?: return null
        if (info.versionCode <= BuildConfig.VERSION_CODE) return null
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
            connection = URL(UPDATE_URL).openConnection() as HttpURLConnection
            connection.connectTimeout = TIMEOUT_MILLIS
            connection.readTimeout = TIMEOUT_MILLIS
            connection.setRequestProperty("User-Agent", USER_AGENT)

            val json = JSONObject(
                connection.inputStream
                    .bufferedReader(Charsets.UTF_8)
                    .use { it.readText() }
            )
            parseRelease(json)
        } catch (e: Exception) {
            null
        } finally {
            connection?.disconnect()
        }
    }

    private fun parseRelease(json: JSONObject): UpdateInfo? {
        // Tags are pushed as v<versionCode> — anything else is not for us.
        val tag = json.optString("tag_name")
        if (!tag.matches(Regex("""^v(\d+)$"""))) return null

        val versionCode = tag.drop(1).toIntOrNull() ?: return null

        val assets = json.optJSONArray("assets") ?: return null
        if (assets.length() == 0) return null

        // Prefer the arm64 build (device ABI), fall back to the first asset.
        val arm64Asset = (0 until assets.length())
            .map { assets.getJSONObject(it) }
            .firstOrNull { it.optString("name").contains("arm64") }
        val asset = arm64Asset ?: assets.getJSONObject(0)
        val apkUrl = asset.optString("browser_download_url")
        if (apkUrl.isEmpty()) return null

        return UpdateInfo(
            versionCode = versionCode,
            tag = tag,
            apkUrl = apkUrl,
            sizeBytes = asset.optLong("size"),
            body = json.optString("body")
        )
    }

    fun downloadAndInstall(context: Context, url: String): String? {
        var connection: HttpURLConnection? = null
        return try {
            val file = File(context.cacheDir, "update.apk")
            if (file.exists()) file.delete()

            connection = URL(url).openConnection() as HttpURLConnection
            connection.connectTimeout = TIMEOUT_MILLIS
            connection.readTimeout = TIMEOUT_MILLIS
            connection.setRequestProperty("User-Agent", USER_AGENT)

            if (connection.responseCode != HttpURLConnection.HTTP_OK) {
                return "Download failed (HTTP ${connection.responseCode})"
            }

            connection.inputStream.use { input ->
                file.outputStream().use { output ->
                    val buffer = ByteArray(BUFFER_SIZE)
                    var read = input.read(buffer)
                    while (read != -1) {
                        output.write(buffer, 0, read)
                        read = input.read(buffer)
                    }
                }
            }

            val uri = FileProvider.getUriForFile(context, "${context.packageName}.provider", file)
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(intent)
            null
        } catch (e: Exception) {
            e.message ?: "Update failed"
        } finally {
            connection?.disconnect()
        }
    }
}