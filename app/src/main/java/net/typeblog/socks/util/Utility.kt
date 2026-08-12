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
import net.typeblog.socks.util.Constants.PREF_NETSHIELD_BLOCK_ADULT
import net.typeblog.socks.util.Constants.PREF_NETSHIELD_ENABLED

import java.io.BufferedReader
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.io.InputStreamReader
import java.net.Authenticator
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.PasswordAuthentication
import java.net.Proxy
import java.net.URL
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
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

    // NetShield cloud upstreams — AdGuard public DNS (filtering resolvers).
const val ADGUARD_DNS = "94.140.14.14"

    // AdGuard's Family variant no longer blocks adult content (public policy
    // change since 2021), so the "Block adult content" tier uses CleanBrowsing
    // Family Filter, which still enforces it. Verified over TCP/53 (pdnsd uses
    // tcp-only): pornhub.com -> no records (blocked); google.com resolves fine.
    const val ADGUARD_DNS_FAMILY = "185.228.168.168"
    // Countries where AdGuard public DNS is documented as blocked or heavily
    // unreliable (China: GFW/SAN interference; Russia: RKN collateral bans
    // historically took out AdGuard DNS for direct users; Iran: same as CN).
    // NetShield is simply unavailable there — plain DNS is used, no fallback.
    val NETSHIELD_UNSUPPORTED_COUNTRIES = setOf("CN", "RU", "IR")

    data class NetshieldPolicy(
        val upstream: String?
    )

    @JvmStatic
    fun extractFile(context: Context) {
        // No longer needed: we run libpdnsd.so and libtun2socks.so directly from nativeLibraryDir
    }

    @JvmStatic
    fun exec(cmd: String): Int {
        return try {
            Log.d(TAG, "Executing: $cmd")
            val p = Runtime.getRuntime().exec(cmd)

            val br = java.io.BufferedReader(java.io.InputStreamReader(p.errorStream))
            var line = br.readLine()
            while (line != null) {
                Log.e(TAG, "STDERR: $line")
                line = br.readLine()
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

            // Read merged stdout/stderr to prevent buffer deadlock
            val br = java.io.BufferedReader(java.io.InputStreamReader(p.inputStream))
            var line = br.readLine()
            while (line != null) {
                Log.d(TAG, "exec: $line")
                line = br.readLine()
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

        if (!file.exists()) {
            return
        }

        val i: InputStream = try {
            FileInputStream(file)
        } catch (e: Exception) {
            return
        }

        val buf = ByteArray(512)
        val str = StringBuilder()

        try {
            var len = i.read(buf, 0, 512)
            while (len > 0) {
                str.append(String(buf, 0, len))
                len = i.read(buf, 0, 512)
            }
            i.close()
        } catch (e: Exception) {
            return
        }

        try {
            val pid = str.toString().trim().replace("\n", "").toInt()
            Runtime.getRuntime().exec("kill $pid").waitFor()
            file.delete()
        } catch (e: Exception) {
            // ignore
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

        if (f.exists()) {
            f.delete()
        }

        try {
            val out = FileOutputStream(f)
            out.write(conf.toByteArray())
            out.flush()
            out.close()
        } catch (e: Exception) {
            // ignore
        }

        val cache = File("$dir/pdnsd.cache")

        if (!cache.exists()) {
            try {
                cache.createNewFile()
            } catch (e: Exception) {
                // ignore
            }
        }
    }

    @JvmStatic
    fun netshieldPolicy(context: Context, server: String?, user: String?, realCountry: String? = null): NetshieldPolicy {
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        if (!prefs.getBoolean(PREF_NETSHIELD_ENABLED, false)) return NetshieldPolicy(null)
        val adult = prefs.getBoolean(PREF_NETSHIELD_BLOCK_ADULT, false)

        val cc = realCountry?.uppercase()
            ?: ProxyProviders.parseCountry(
                user ?: "",
                ProxyProviders.detectType(server ?: "", user ?: "")
            )?.uppercase()

        // Only countries where the AdGuard cloud upstream is legally blocked
        // (CN/RU/IR) disable NetShield. Every other country — including custom
        // profiles without a zone (unknown country) — gets the AdGuard
        // upstream (family variant blocks adult content).
        return if (cc == null || cc !in NETSHIELD_UNSUPPORTED_COUNTRIES) {
            NetshieldPolicy(if (adult) ADGUARD_DNS_FAMILY else ADGUARD_DNS)
        } else {
            NetshieldPolicy(null)
        }
    }

    @JvmStatic
    fun startVpn(context: Context, profile: Profile) {
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        val perApp = profile.isPerApp() || prefs.getBoolean(PREF_ADV_PER_APP, false)
        val bypass = if (profile.isPerApp()) {
            profile.isBypassApp()
        } else {
            prefs.getBoolean(PREF_ADV_APP_BYPASS, false)
        }
        val appList = if (profile.isPerApp()) {
            profile.getAppList()
        } else {
            prefs.getString(PREF_ADV_APP_LIST, "") ?: ""
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
                .putExtra(INTENT_APP_LIST, appList.split("\n").toTypedArray())
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
        val winner = AtomicReference<IpInfo?>(null)
        val latch = CountDownLatch(1)
        val providers = listOf(
            "http://ip-api.com/json/?fields=status,query,country,countryCode,regionName,city,isp,org,as,timezone" to { obj: JSONObject ->
                if (obj.optString("status") != "success") {
                    null
                } else {
                    IpInfo(
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
                }
            },
            "https://ipapi.co/json/" to { obj: JSONObject ->
                val ip = obj.optString("ip")
                if (ip.isEmpty()) {
                    null
                } else {
                    IpInfo(
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
                }
            }
        )
        // Race both providers; first non-null success wins (returns faster)
        providers.forEach { (url, parse) ->
            Thread {
                try {
                    val info = fetchPublicIp(url, parse, server, port, username, password)
                    if (info != null && winner.compareAndSet(null, info)) {
                        latch.countDown()
                    }
                } catch (_: Exception) {
                }
            }.start()
        }
        latch.await(10, TimeUnit.SECONDS)
        return winner.get()
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
        return try {
            val u = URL(url)
            if (server.isNullOrEmpty()) {
                conn = u.openConnection() as HttpURLConnection
            } else {
                val addr = InetSocketAddress(server, port)
                val proxy = Proxy(Proxy.Type.SOCKS, addr)
                conn = u.openConnection(proxy) as HttpURLConnection
                if (!username.isNullOrEmpty()) {
                    val user = username
                    val pass = password ?: ""
                    Authenticator.setDefault(object : Authenticator() {
                        override fun getPasswordAuthentication(): PasswordAuthentication {
                            return PasswordAuthentication(user, pass.toCharArray())
                        }
                    })
                }
            }
            conn.connectTimeout = 5000
            conn.readTimeout = 5000
            val reader = BufferedReader(InputStreamReader(conn.inputStream))
            val sb = StringBuilder()
            var line = reader.readLine()
            while (line != null) {
                sb.append(line)
                line = reader.readLine()
            }
            reader.close()
            parse(JSONObject(sb.toString()))
        } catch (e: Exception) {
            Log.d("Utility", "checkPublicIp($url) failed: ${e.message}")
            null
        } finally {
            conn?.disconnect()
        }
    }

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
