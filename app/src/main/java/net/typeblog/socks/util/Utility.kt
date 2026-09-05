package net.typeblog.socks.util

import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.preference.PreferenceManager
import net.typeblog.socks.SocksVpnService
import net.typeblog.socks.util.Constants.INTENT_APP_BYPASS
import net.typeblog.socks.util.Constants.INTENT_APP_LIST
import net.typeblog.socks.util.Constants.INTENT_DNS
import net.typeblog.socks.util.Constants.INTENT_DNS_PORT
import net.typeblog.socks.util.Constants.INTENT_IPV6_PROXY
import net.typeblog.socks.util.Constants.INTENT_NAME
import net.typeblog.socks.util.Constants.INTENT_PER_APP
import net.typeblog.socks.util.Constants.INTENT_PORT
import net.typeblog.socks.util.Constants.INTENT_ROUTE
import net.typeblog.socks.util.Constants.INTENT_SERVER
import net.typeblog.socks.util.Constants.INTENT_USERNAME
import net.typeblog.socks.util.Constants.INTENT_PASSWORD
import net.typeblog.socks.util.Constants.INTENT_UDP_GW
import net.typeblog.socks.util.Constants.PREF_ADV_APP_BYPASS
import net.typeblog.socks.util.Constants.PREF_ADV_APP_LIST
import net.typeblog.socks.util.Constants.PREF_ADV_PER_APP

import java.io.BufferedReader
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStreamReader
import java.net.Authenticator
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.PasswordAuthentication
import java.net.Proxy
import java.net.URL
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import org.json.JSONObject

data class IpInfo(
    val ip: String,
    val countryCode: String,
    val country: String = "",
    val regionName: String = "",
    val city: String = "",
    val isp: String = "",
    val org: String = "",
    val asName: String = "",
    val timezone: String = ""
)

object Utility {
    private val TAG = Utility::class.java.simpleName

    @JvmStatic
    fun extractFile(context: Context) {
        // No longer needed: we run libpdnsd.so and libtun2socks.so directly from nativeLibraryDir
    }

    @JvmStatic
    fun exec(cmd: String): Int {
        return try {
            Log.d(TAG, "Executing: $cmd")
            val p = Runtime.getRuntime().exec(cmd)
            BufferedReader(InputStreamReader(p.errorStream)).use { br ->
                var line = br.readLine()
                while (line != null) {
                    Log.e(TAG, "STDERR: $line")
                    line = br.readLine()
                }
            }
            val ret = p.waitFor()
            Log.d(TAG, "Process exited with: $ret")
            ret
        } catch (e: Exception) {
            Log.e(TAG, "exec failed", e)
            -1
        }
    }

    @JvmStatic
    fun exec(cmd: Array<String>): Int {
        return try {
            Log.d(TAG, "Executing: ${cmd.contentToString()}")
            val pb = ProcessBuilder(*cmd)
            pb.redirectErrorStream(true)
            val p = pb.start()
            BufferedReader(InputStreamReader(p.inputStream)).use { br ->
                var line = br.readLine()
                while (line != null) {
                    Log.d(TAG, "exec: $line")
                    line = br.readLine()
                }
            }
            val ret = p.waitFor()
            Log.d(TAG, "Process '${cmd.firstOrNull() ?: "?"}' exited with: $ret")
            ret
        } catch (e: Exception) {
            Log.e(TAG, "exec failed for cmd: ${cmd.contentToString()}", e)
            -1
        }
    }

    @JvmStatic
    fun killPidFile(f: String) {
        val file = File(f)
        if (!file.exists()) return
        val str = StringBuilder()
        try {
            FileInputStream(file).use { i ->
                val buf = ByteArray(512)
                var len = i.read(buf, 0, 512)
                while (len > 0) {
                    str.append(String(buf, 0, len))
                    len = i.read(buf, 0, 512)
                }
            }
        } catch (_: Exception) {
            return
        }
        try {
            val pid = str.toString().trim().replace("\n", "").toInt()
            Runtime.getRuntime().exec("kill $pid").waitFor()
            file.delete()
        } catch (_: Exception) {
        }
    }

    @JvmStatic
    fun join(list: List<String>?, separator: String): String {
        if (list == null || list.isEmpty()) return ""
        val ret = StringBuilder()
        for (s in list) {
            ret.append(s).append(separator)
        }
        return ret.substring(0, ret.length - separator.length)
    }

    @JvmStatic
    fun makePdnsdConf(context: Context, dns: String, port: Int, upstream: String? = null) {
        val dir = context.filesDir.absolutePath
        val conf = String.format(context.getString(net.typeblog.socks.R.string.pdnsd_conf), dir, dir, upstream ?: dns, port)
        val f = File("$dir/pdnsd.conf")
        if (f.exists()) f.delete()
        try {
            FileOutputStream(f).use { out ->
                out.write(conf.toByteArray())
                out.flush()
            }
        } catch (_: Exception) {
        }
        val cache = File("$dir/pdnsd.cache")
        if (!cache.exists()) {
            try { cache.createNewFile() } catch (_: Exception) {}
        }
    }

    @JvmStatic
    fun startVpn(context: Context, profile: Profile) {
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        // Single source of truth: global SplitTunnelingScreen prefs. Profile perapp is legacy; global takes precedence when enabled.
        val globalPerApp = prefs.getBoolean(PREF_ADV_PER_APP, false)
        val perApp = profile.isPerApp() || globalPerApp
        val bypass: Boolean
        val appList: String
        if (globalPerApp) {
            bypass = prefs.getBoolean(PREF_ADV_APP_BYPASS, false)
            appList = prefs.getString(PREF_ADV_APP_LIST, "") ?: ""
        } else if (profile.isPerApp()) {
            bypass = profile.isBypassApp()
            appList = profile.getAppList()
        } else {
            bypass = false
            appList = ""
        }

        val i = Intent(context, SocksVpnService::class.java)
            .putExtra(INTENT_NAME, profile.getName())
            .putExtra(INTENT_SERVER, profile.getServer())
            .putExtra(INTENT_PORT, profile.getPort())
            .putExtra(INTENT_ROUTE, profile.getRoute())
            .putExtra(INTENT_DNS, profile.getDns())
            .putExtra(INTENT_DNS_PORT, profile.getDnsPort())
            .putExtra(INTENT_PER_APP, perApp)
            .putExtra(INTENT_IPV6_PROXY, profile.hasIPv6())

        if (perApp) {
            i.putExtra(INTENT_APP_BYPASS, bypass)
                .putExtra(INTENT_APP_LIST, appList.split("\n").filter { it.isNotEmpty() }.toTypedArray())
        }

        i.putExtra(INTENT_USERNAME, profile.getUsername())
        i.putExtra(INTENT_PASSWORD, profile.getPassword())

        if (profile.hasUDP()) {
            i.putExtra(INTENT_UDP_GW, profile.getUDPGW())
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(i)
        } else {
            context.startService(i)
        }
    }

    @JvmStatic
    fun checkPublicIp(): IpInfo? {
        return checkPublicIp(null, 0, null, null)
    }

    @JvmStatic
    fun checkPublicIp(server: String?, port: Int, username: String?, password: String?): IpInfo? {
        val providers = listOf(
            "https://ip-api.com/json/?fields=status,query,country,countryCode,regionName,city,isp,org,as,timezone" to { obj: JSONObject ->
                if (obj.optString("status") != "success") null else IpInfo(
                        ip = obj.getString("query"),
                        countryCode = obj.getString("countryCode"),
                        country = obj.optString("country"),
                        regionName = obj.optString("regionName"),
                        city = obj.optString("city"),
                        isp = obj.optString("isp"),
                        org = obj.optString("org"),
                        asName = obj.optString("as"),
                        timezone = obj.optString("timezone")
                    )
            },
            "https://ipapi.co/json/" to { obj: JSONObject ->
                val ip = obj.optString("ip")
                if (ip.isEmpty()) null else IpInfo(
                        ip = ip,
                        countryCode = obj.optString("country_code", obj.optString("countryCode")),
                        country = obj.optString("country_name", obj.optString("country")),
                        regionName = obj.optString("region"),
                        city = obj.optString("city"),
                        isp = obj.optString("org"),
                        org = obj.optString("org"),
                        asName = obj.optString("asn"),
                        timezone = obj.optString("timezone")
                    )
            },
            "https://ipwho.is/" to { obj: JSONObject ->
                val ip = obj.optString("ip")
                if (ip.isEmpty() || obj.optString("success") == "false") null else {
                    val conn = obj.optJSONObject("connection")
                    val tz = obj.optJSONObject("timezone")
                    val asn = conn?.optString("asn", "") ?: ""
                    IpInfo(
                        ip = ip,
                        countryCode = obj.optString("country_code"),
                        country = obj.optString("country"),
                        regionName = obj.optString("region"),
                        city = obj.optString("city"),
                        isp = conn?.optString("isp", "") ?: "",
                        org = conn?.optString("org", "") ?: "",
                        asName = if (asn.isEmpty() || asn.startsWith("AS")) asn else "AS$asn",
                        timezone = tz?.optString("id", "") ?: ""
                    )
                }
            },
            "https://free.freeipapi.com/api/json" to { obj: JSONObject ->
                val ip = obj.optString("ipAddress")
                if (ip.isEmpty()) null else {
                    val asn = obj.optString("asn")
                    IpInfo(
                        ip = ip,
                        countryCode = obj.optString("countryCode"),
                        country = obj.optString("countryName"),
                        regionName = obj.optString("regionName"),
                        city = obj.optString("cityName"),
                        isp = obj.optString("asnOrganization"),
                        org = obj.optString("asnOrganization"),
                        asName = if (asn.isEmpty() || asn.startsWith("AS")) asn else "AS$asn",
                        timezone = obj.optJSONArray("timeZones")?.optString(0, "") ?: ""
                    )
                }
            }
        )
        val exec = Executors.newFixedThreadPool(providers.size) { r ->
            Thread(r).apply { isDaemon = true }
        }
        return try {
            val futures: List<Future<IpInfo?>> = providers.map { (url, parse) ->
                exec.submit(Callable<IpInfo?> { fetchPublicIp(url, parse, server, port, username, password) })
            }
            var result: IpInfo? = null
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
            for (f in futures) {
                val remaining = deadline - System.nanoTime()
                if (remaining <= 0) break
                try {
                    val info = f.get(remaining, TimeUnit.NANOSECONDS)
                    if (info != null) { result = info; break }
                } catch (_: Exception) {}
            }
            result
        } finally {
            exec.shutdownNow()
        }
    }

    private fun fetchPublicIp(
        url: String,
        parse: (JSONObject) -> IpInfo?,
        server: String?,
        port: Int,
        username: String?,
        password: String?
    ): IpInfo? {
        var conn: HttpURLConnection? = null
        var authSet = false
        return try {
            val u = URL(url)
            conn = if (server.isNullOrEmpty()) {
                u.openConnection() as HttpURLConnection
            } else {
                u.openConnection(Proxy(Proxy.Type.SOCKS, InetSocketAddress(server, port))) as HttpURLConnection
            }
            if (!server.isNullOrEmpty() && !username.isNullOrEmpty()) {
                val user = username
                val pass = password ?: ""
                Authenticator.setDefault(object : Authenticator() {
                    override fun getPasswordAuthentication() = PasswordAuthentication(user, pass.toCharArray())
                })
                authSet = true
            }
            conn.connectTimeout = 3000
            conn.readTimeout = 3000
            val text = try {
                BufferedReader(InputStreamReader(conn.inputStream)).use { it.readText() }
            } catch (_: Exception) {
                return null
            }
            parse(JSONObject(text))
        } catch (e: Exception) {
            Log.d("Utility", "checkPublicIp($url) failed: ${e.message}")
            null
        } finally {
            conn?.disconnect()
            if (authSet) Authenticator.setDefault(null)
        }
    }

    // Canonical usage-stats key suffix: same in :vpn (writer) and UI (reader).
    @JvmStatic
    fun usageSuffix(name: String): String =
        try { java.net.URLEncoder.encode(name, "UTF-8") } catch (_: Exception) { name.hashCode().toString() }

    @JvmStatic
    fun countryCodeToFlag(countryCode: String): String {
        if (countryCode.length != 2) return "\uD83C\uDF10"
        val first = String(Character.toChars(0x1F1E6 - 'A'.code + countryCode[0].uppercaseChar().code))
        val second = String(Character.toChars(0x1F1E6 - 'A'.code + countryCode[1].uppercaseChar().code))
        return "$first$second"
    }

    /**
     * Reads the per-interface transmit/receive byte counters for the VPN tunnel.
     *
     * Because tun2socks forwards packets natively (Java never sees individual
     * packets), the only reliable cross-process counter is the kernel's own
     * sysfs statistics for the tun interface created by VpnService. We locate
     * it by the unique tunnel address (10.10.10.1) we assign and sum its RX/TX.
     *
     * @return Pair(rxBytes, txBytes) on success, null if the interface is missing.
     */
    @JvmStatic
    fun readTunBytes(): Pair<Long, Long>? {
        return try {
            val iface = findTunInterface() ?: return null
            val rxFile = File("/sys/class/net/$iface/statistics/rx_bytes")
            val txFile = File("/sys/class/net/$iface/statistics/tx_bytes")
            if (!rxFile.exists() || !txFile.exists()) return null
            val rx = rxFile.readText().trim().toLongOrNull() ?: return null
            val tx = txFile.readText().trim().toLongOrNull() ?: return null
            Pair(rx, tx)
        } catch (_: Exception) {
            null
        }
    }

    private fun findTunInterface(): String? {
        return try {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val ni = interfaces.nextElement()
                val addrs = ni.inetAddresses
                while (addrs.hasMoreElements()) {
                    val addr = addrs.nextElement()
                    // Our tunnel always installs 10.10.10.1/24 on the tun interface.
                    if (addr.hostAddress == "10.10.10.1") return ni.name
                }
            }
            null
        } catch (_: Exception) {
            null
        }
    }

    @JvmStatic
    fun getRecentCountries(context: Context): List<String> {
        val file = java.io.File(context.filesDir, "recent_countries.txt")
        if (!file.exists()) return emptyList()
        return try {
            file.readText().split(",").map { it.trim() }.filter { it.isNotEmpty() }
        } catch (_: Exception) {
            emptyList()
        }
    }

    @JvmStatic
    fun addRecentCountry(context: Context, code: String) {
        val normalized = code.trim().uppercase()
        if (normalized.isEmpty()) return
        val existing = getRecentCountries(context)
        val updated = (listOf(normalized) + existing.filter { it != normalized }).take(10)
        try {
            java.io.File(context.filesDir, "recent_countries.txt").writeText(updated.joinToString(","))
        } catch (_: Exception) {
        }
    }

    @JvmStatic
    fun formatBytes(bytes: Long): String {
        if (bytes < 0) return "0 B"
        return when {
            bytes >= 1024L * 1024 * 1024 -> String.format(java.util.Locale.US, "%.2f GB", bytes / (1024.0 * 1024 * 1024))
            bytes >= 1024L * 1024 -> String.format(java.util.Locale.US, "%.2f MB", bytes / (1024.0 * 1024))
            bytes >= 1024L -> String.format(java.util.Locale.US, "%.1f KB", bytes / 1024.0)
            else -> "$bytes B"
        }
    }
}
