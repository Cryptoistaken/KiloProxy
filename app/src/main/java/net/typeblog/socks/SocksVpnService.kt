package net.typeblog.socks

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service.STOP_FOREGROUND_REMOVE
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Context.RECEIVER_EXPORTED
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.net.VpnService
import android.net.VpnService.Builder
import android.net.TrafficStats
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.os.Process
import android.os.PowerManager
import android.text.TextUtils
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.preference.PreferenceManager
import net.typeblog.socks.R
import net.typeblog.socks.util.Constants
import net.typeblog.socks.util.Constants.ACTION_STOP_VPN
import net.typeblog.socks.util.Constants.INTENT_APP_BYPASS
import net.typeblog.socks.util.Constants.INTENT_APP_LIST
import net.typeblog.socks.util.Constants.INTENT_DNS
import net.typeblog.socks.util.Constants.INTENT_DNS_PORT
import net.typeblog.socks.util.Constants.INTENT_IPV6_PROXY
import net.typeblog.socks.util.Constants.INTENT_NAME
import net.typeblog.socks.util.Constants.INTENT_PASSWORD
import net.typeblog.socks.util.Constants.INTENT_PER_APP
import net.typeblog.socks.util.Constants.INTENT_PORT
import net.typeblog.socks.util.Constants.INTENT_ROUTE
import net.typeblog.socks.util.Constants.INTENT_SERVER
import net.typeblog.socks.util.Constants.INTENT_UDP_GW
import net.typeblog.socks.util.Constants.INTENT_USERNAME
import net.typeblog.socks.util.Constants.PREF_AUTO_STOP
import net.typeblog.socks.util.IpInfo
import net.typeblog.socks.util.Routes
import net.typeblog.socks.util.SocksTester
import net.typeblog.socks.util.Utility
import net.typeblog.socks.BuildConfig.DEBUG

class SocksVpnService : VpnService() {
    inner class VpnBinder : IVpnService.Stub() {
        override fun isRunning(): Boolean {
            return mRunning
        }

        override fun stop() {
            Log.d(TAG, "stop() called via AIDL binder")
            stopMe("binder_stop")
        }

        override fun getCurrentIp(): String {
            return mCurrentIp ?: ""
        }

        override fun getCountryCode(): String {
            return mCountryCode ?: ""
        }

        override fun getCountry(): String {
            return mIpInfo?.country ?: ""
        }

        override fun getRegion(): String {
            return mIpInfo?.regionName ?: ""
        }

        override fun getCity(): String {
            return mIpInfo?.city ?: ""
        }

        override fun getIsp(): String {
            return mIpInfo?.isp ?: ""
        }

        override fun getOrg(): String {
            return mIpInfo?.org ?: ""
        }

        override fun getAsName(): String {
            return mIpInfo?.asName ?: ""
        }

        override fun getTimezone(): String {
            return mIpInfo?.timezone ?: ""
        }

        override fun getConnectedSince(): Long {
            return mConnectedSince
        }

        override fun getErrorMessage(): String {
            return mError ?: ""
        }

        override fun getReceivedBytes(): Long {
            return mCumulativeRx + mReceivedBytes
        }

        override fun getSentBytes(): Long {
            return mCumulativeTx + mSentBytes
        }

        override fun getProfileName(): String {
            return mProfileName ?: ""
        }

        override fun isProxyVerified(): Boolean {
            return mProxyVerified
        }
    }

    private var mInterface: ParcelFileDescriptor? = null
    @Volatile
    private var mRunning = false
    @Volatile
    private var mProxyVerified = false
    private val mBinder: IBinder = VpnBinder()
    private var mProfileName: String? = null
    private var mTun2socksProcess: java.lang.Process? = null
    private var mPdnsdProcess: java.lang.Process? = null
    @Volatile
    private var mDns: String? = null
    @Volatile
    private var mDnsPort: Int = 53
    @Volatile
    private var mNetshieldPolicy: Utility.NetshieldPolicy? = null
    private var mResolvedServer: String? = null
    private var mServer: String? = null
    private var mPort: Int = 0
    private var mUsername: String? = null
    private var mPassword: String? = null

    private var mCurrentIp: String? = null
    private var mCountryCode: String? = null
    private var mIpInfo: IpInfo? = null
    @Volatile
    private var mConnectedSince: Long = 0L
    private var mError: String? = null
    private var mIpCheckFailures = 0
    private val mIpCheckHandler = Handler(Looper.getMainLooper())

    // Last notification content actually issued, so the retry loop can skip
    // redundant notify() calls when the visible text didn't change.
    private var mLastNotificationText: String? = null
    private var mLastNotificationActions = -1

    @Volatile
    private var mReceivedBytes = 0L
    @Volatile
    private var mSentBytes = 0L
    @Volatile
    private var mCumulativeRx = 0L
    @Volatile
    private var mCumulativeTx = 0L
    private var mBaseRx = 0L
    private var mBaseTx = 0L

    private val mStatsHandler = Handler(Looper.getMainLooper())
    private var mStatsTick = 0L
    private val mStatsRunnable = object : Runnable {
        override fun run() {
            val rx = TrafficStats.getUidRxBytes(Process.myUid())
            val tx = TrafficStats.getUidTxBytes(Process.myUid())
            if (rx >= 0L && tx >= 0L) {
                mReceivedBytes = (rx - mBaseRx).coerceAtLeast(0L)
                mSentBytes = (tx - mBaseTx).coerceAtLeast(0L)
            }
            mStatsTick++
            if (mRunning) {
                // Persist usage periodically so the profiles page proxy card
                // reflects live data instead of only updating on VPN stop.
                if (mStatsTick % USAGE_PERSIST_TICKS == 0L) {
                    persistProfileBytes()
                }
                mStatsHandler.postDelayed(this, STATS_INTERVAL)
            }
        }
    }
    private val mScreenOffReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == Intent.ACTION_SCREEN_OFF) {
                Log.d(TAG, "Screen off received, auto-stopping VPN")
                stopMe("screen_off")
            }
        }
    }
    private val mScreenOnReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == Intent.ACTION_SCREEN_ON && mRunning) {
                Log.d(TAG, "Screen on — re-verifying connectivity")
                mIpCheckFailures = 0
                mIpCheckHandler.removeCallbacks(mIpCheckRunnable)
                mIpCheckHandler.post(mIpCheckRunnable)
            }
        }
    }
    private val mIpCheckRunnable = object : Runnable {
        override fun run() {
            if (!mRunning) return
            // During Doze (or while the screen is off) the network is suspended,
            // so every probe would fail. Skip the check instead of counting these
            // failures toward teardown; the loop re-verifies on wake.
            if (isNetworkCheckBlocked()) {
                mIpCheckHandler.postDelayed(this, DOZE_CHECK_INTERVAL)
                return
            }
            // Use the pre-resolved server IP when available so the ip-api and
            // SOCKS probes avoid a repeated hostname DNS round-trip.
            val server = mResolvedServer ?: mServer
            val port = mPort
            val username = mUsername
            val password = mPassword
            Thread {
                try {
                    val info = Utility.checkPublicIp(server, port, username, password)
                    if (info != null) {
                        runOnMainThread {
                            mCurrentIp = info.ip
                            mCountryCode = info.countryCode
                            mIpInfo = info
                            mProxyVerified = true
                            mIpCheckFailures = 0
                            reconcileNetshield()
                            updateNotification()
                            mIpCheckHandler.postDelayed(this, IP_CHECK_INTERVAL)
                        }
                    } else {
                        // Public-IP lookup failed; this may simply mean ip-api.com is
                        // unreachable. Connected state is already set at tunnel-up, so
                        // this is pure enrichment. Only a real SOCKS handshake failure
                        // counts as a dead proxy and may tear down the VPN.
                        val probe = SocksTester.probeProxy(server, port, username, password)
                        runOnMainThread {
                            if (probe == SocksTester.ProxyProbe.OK) {
                                // Proxy itself is healthy — do not count the lookup
                                // failure, do not stop. The ip-api lookup may simply
                                // be temporarily unreachable, so retry sooner than the
                                // normal cadence so the country/pill appears quickly.
                                mProxyVerified = true
                                mIpCheckFailures = 0
                                updateNotification()
                                mIpCheckHandler.postDelayed(this, IP_INFO_RETRY)
                            } else {
                                mIpCheckFailures++
                                Log.e(TAG, "IP check failed ($mIpCheckFailures/$MAX_IP_CHECK_FAILURES): $probe")
                                if (mIpCheckFailures >= MAX_IP_CHECK_FAILURES) {
                                    mError = when (probe) {
                                        SocksTester.ProxyProbe.AUTH_FAILED ->
                                            "Connection failed: proxy authentication failed. Check your username and password."
                                        SocksTester.ProxyProbe.NOT_SOCKS5 ->
                                            "Connection failed: server is not a SOCKS5 proxy."
                                        SocksTester.ProxyProbe.CONNECT_FAILED ->
                                            "Connection failed: proxy refused the connection."
                                        else ->
                                            "Connection failed: proxy unreachable or not responding."
                                    }
                                    Log.e(TAG, "Connectivity never verified — stopping: $mError")
                                    stopMe("proxy_connect_failed")
                                    return@runOnMainThread
                                }
                                mIpCheckHandler.postDelayed(this, IP_CHECK_RETRY)
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "IP check failed", e)
                    runOnMainThread {
                        mIpCheckHandler.postDelayed(this, IP_CHECK_RETRY)
                    }
                }
            }.start()
        }
    }

    private fun isNetworkCheckBlocked(): Boolean {
        val pm = getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return false
        return !pm.isInteractive ||
            (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && pm.isDeviceIdleMode)
    }

    private val mNotificationActionReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                ACTION_STOP_VPN -> {
                    Log.d(TAG, "Notification stop action received")
                    stopMe("notification_stop")
                }
            }
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Floating Control",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            if (manager != null) {
                manager.createNotificationChannel(channel)
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (DEBUG) {
            Log.d(TAG, "starting")
        }

        if (intent == null) {
            return 0
        }

        if (mRunning) {
            return 0
        }

        mProfileName = intent.getStringExtra(INTENT_NAME)
        val server = intent.getStringExtra(INTENT_SERVER)
        val port = intent.getIntExtra(INTENT_PORT, 1080)
        val username = intent.getStringExtra(INTENT_USERNAME)
        val passwd = intent.getStringExtra(INTENT_PASSWORD)
        mServer = server
        mPort = port
        mUsername = username
        mPassword = passwd
        mDns = dns
        mDnsPort = dnsPort
        val route = intent.getStringExtra(INTENT_ROUTE)
        val dns = intent.getStringExtra(INTENT_DNS)
        val dnsPort = intent.getIntExtra(INTENT_DNS_PORT, 53)
        val perApp = intent.getBooleanExtra(INTENT_PER_APP, false)
        val appBypass = intent.getBooleanExtra(INTENT_APP_BYPASS, false)
        val appList = intent.getStringArrayExtra(INTENT_APP_LIST)
        val ipv6 = intent.getBooleanExtra(INTENT_IPV6_PROXY, false)
        val udpgw = intent.getStringExtra(INTENT_UDP_GW)

        Log.d(TAG, "onStartCommand: profile=$mProfileName server=$server:$port user=$username route=$route dns=$dns:$dnsPort perApp=$perApp ipv6=$ipv6 udpgw=$udpgw")

        createNotificationChannel()

        showNotification()

            // Register notification action receiver
        registerReceiverCompat(mNotificationActionReceiver, IntentFilter(ACTION_STOP_VPN))

        configure(mProfileName, route, perApp, appBypass, appList, ipv6)

        if (DEBUG)
            Log.d(TAG, "fd: ${mInterface?.fd}")

        if (mInterface != null) {
            Log.d(TAG, "mInterface is non-null with fd=${mInterface!!.fd}, calling start()")
            start(mInterface!!.fd, server, port, username, passwd, dns, dnsPort, ipv6, udpgw)
        } else {
            Log.e(TAG, "mInterface is NULL after configure() — VPN establish() returned null!")
            stopMe("interface_null")
        }

        // NOTE (FIX #3): The mRunning-dependent post-start steps (stats runnable,
        // ip-check runnable, screen-off receiver, counter resets) now run inside
        // start()'s background completion, posted back to the main thread by
        // postStartOnMain() after the tunnel is up. Do not re-add a synchronous
        // `if (mRunning)` block here — start() returns before the tunnel is up.

        return START_STICKY
    }

    override fun onRevoke() {
        Log.d(TAG, "onRevoke called - VPN permission revoked")
        super.onRevoke()
        stopMe("vpn_revoked")
    }

    override fun onBind(intent: Intent?): IBinder? {
        if (intent?.action == VpnService.SERVICE_INTERFACE) {
            return super.onBind(intent)
        }
        if (android.os.Binder.getCallingUid() == Process.myUid()) {
            return mBinder
        }
        Log.w(TAG, "Unauthorized bind attempt from UID ${android.os.Binder.getCallingUid()}")
        return null
    }

    /**
     * Registers a [BroadcastReceiver] safely across all API levels.
     * On Android 13+ (API 33) the receiver MUST be flagged
     * RECEIVER_EXPORTED / RECEIVER_NOT_EXPORTED or the system throws a
     * SecurityException on non-system broadcasts. On API 21-25 only the
     * older 4-arg overload exists. These are private in-app receivers, so
     * we use RECEIVER_NOT_EXPORTED where required.
     */
    private fun registerReceiverCompat(receiver: BroadcastReceiver, filter: IntentFilter) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(receiver, filter, null, null)
        }
    }

    override fun onDestroy() {
        Log.d(TAG, "onDestroy called")
        super.onDestroy()
        stopMe("on_destroy")
    }

    private fun stopMe(reason: String = "") {
        Log.d(TAG, "stopMe called" + if (reason.isNotEmpty()) " - reason: $reason" else "")
        if (reason.isEmpty()) {
            // Log stack trace when no reason is given to identify caller
            Log.d(TAG, "stopMe stack trace:", Throwable("stopMe caller trace"))
        }
        persistProfileBytes()
        mStatsHandler.removeCallbacks(mStatsRunnable)
        if (Build.VERSION.SDK_INT >= 34) {
            stopForeground(STOP_FOREGROUND_DETACH)
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            @Suppress("DEPRECATION")
            stopForeground(false)
        }

        val dir = filesDir.absolutePath

        // Kill tun2socks: destroy Process handle or fall back to pid file
        mTun2socksProcess?.let { p ->
            try {
                p.destroy()
                Log.d(TAG, "tun2socks process destroyed")
            } catch (e: Exception) {
                Log.e(TAG, "Error destroying tun2socks process: ${e.message}")
            }
            mTun2socksProcess = null
        }
        Utility.killPidFile("$dir/tun2socks.pid")
        Utility.killPidFile("$dir/pdnsd.pid")

        // Kill pdnsd Process (launched non-blocking) if we hold a reference.
        mPdnsdProcess?.let { p ->
            try {
                p.destroy()
                Log.d(TAG, "pdnsd process destroyed")
            } catch (e: Exception) {
                Log.e(TAG, "Error destroying pdnsd process: ${e.message}")
            }
            mPdnsdProcess = null
        }

        try {
            mInterface?.close()
        } catch (e: Exception) {
            Log.e(TAG, "Error: ${e.message}", e)
        }

        mProfileName = null
        mServer = null
        mResolvedServer = null
        mPort = 0
        mUsername = null
        mPassword = null
        mDns = null
        mDnsPort = 53
        mNetshieldPolicy = null
        mCurrentIp = null
        mCountryCode = null
        mIpInfo = null
        mConnectedSince = 0L
        mRunning = false
        mProxyVerified = false
        mError = null

        mCumulativeRx = 0L
        mCumulativeTx = 0L
        mReceivedBytes = 0L
        mSentBytes = 0L
        mBaseRx = 0L
        mBaseTx = 0L

        mIpCheckHandler.removeCallbacks(mIpCheckRunnable)
        mStatsHandler.removeCallbacks(mStatsRunnable)

        try {
            unregisterReceiver(mNotificationActionReceiver)
        } catch (_: Exception) { }

        try {
            unregisterReceiver(mScreenOffReceiver)
        } catch (e: Exception) { }

        try {
            unregisterReceiver(mScreenOnReceiver)
        } catch (e: Exception) { }

        stopSelf()
    }

    private fun usageKeySuffix(): String {
        val name = mProfileName ?: ""
        return name.replace(Regex("[^A-Za-z0-9]"), "_")
    }

    private fun loadProfileBytes(profileName: String?) {
        val name = profileName ?: return
        val prefs = PreferenceManager.getDefaultSharedPreferences(this)
        val suffix = usageKeySuffix()
        mCumulativeRx = prefs.getLong("usage_rx_${name}_$suffix", 0L)
        mCumulativeTx = prefs.getLong("usage_tx_${name}_$suffix", 0L)
    }

    private fun persistProfileBytes() {
        val name = mProfileName ?: return
        val prefs = PreferenceManager.getDefaultSharedPreferences(this)
        val suffix = usageKeySuffix()
        val totalRx = mCumulativeRx + mReceivedBytes
        val totalTx = mCumulativeTx + mSentBytes
        prefs.edit()
            .putLong("usage_rx_${name}_$suffix", totalRx)
            .putLong("usage_tx_${name}_$suffix", totalTx)
            .apply()
        Log.d(TAG, "Persisted usage for ${name}_$suffix: rx=$totalRx tx=$totalTx")
    }

    private fun showNotification() {
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.notify_title))
            .setContentText(getString(R.string.notify_msg, mProfileName ?: ""))
            .setSmallIcon(R.drawable.ic_launcher)
            .build()

        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun updateNotification() {
        if (!mRunning) return

        val notificationText = if (!mCurrentIp.isNullOrEmpty()) {
            getString(R.string.notify_msg, mProfileName ?: "")
        } else {
            "Connecting..."
        }

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.notify_title))
            .setContentText(notificationText)
            .setSmallIcon(R.drawable.ic_launcher)
            .setOngoing(true)
            .build()

        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        // Skip redundant re-issues when the visible content+actions haven't changed
        // (the healthy-proxy / ip-api-fail retry path can otherwise fire this ~2x/sec).
        val textNow = notificationText
        if (textNow == mLastNotificationText && ((notification.actions?.size ?: 0) == mLastNotificationActions)) {
            return
        }
        mLastNotificationText = textNow
        mLastNotificationActions = notification.actions?.size ?: 0
        nm.notify(NOTIFICATION_ID, notification)
    }

    private fun configure(name: String?, route: String?, perApp: Boolean, bypass: Boolean, apps: Array<String>?, ipv6: Boolean) {
        val b = Builder()
        b.setMtu(1500)
            .setSession(name ?: "KiloProxy")
            .addAddress("10.10.10.1", 24)
            .addDnsServer("8.8.8.8")

        if (ipv6) {
            b.addAddress("fdfe:dcba:9876::1", 126)
                .addRoute("::", 0)
        }

        Routes.addRoutes(this, b, route ?: "all")

        b.addRoute("8.8.8.8", 32)

        if (!perApp) {
            // Exclude the app's own UID from the tunnel. tun2socks and pdnsd run
            // under this UID and must reach the real network to resolve the SOCKS
            // server hostname and connect to the proxy; routing them into the
            // un-served tun would deadlock startup (getaddrinfo black-hole).
            // The proxy IP is still reported to the user because the ip-api.com
            // check is explicitly tunneled through the SOCKS proxy (see checkPublicIp).
            try {
                b.addDisallowedApplication(packageName)
            } catch (e: Exception) {
                Log.e(TAG, "Error: ${e.message}", e)
            }
        } else {
            if (bypass) {
                // In bypass mode, selected apps bypass the tunnel; the app's own
                // UID must also bypass so tun2socks/pdnsd can reach the proxy.
                try {
                    b.addDisallowedApplication(packageName)
                } catch (e: Exception) {
                    Log.e(TAG, "Error: ${e.message}", e)
                }
                for (p in apps!!) {
                    if (TextUtils.isEmpty(p)) continue
                    try {
                        b.addDisallowedApplication(p.trim { it <= ' ' })
                    } catch (e: Exception) {
                        Log.e(TAG, "Error: ${e.message}", e)
                    }
                }
            } else {
                for (p in apps!!) {
                    if (TextUtils.isEmpty(p) || p.trim { it <= ' ' } == packageName) continue
                    try {
                        b.addAllowedApplication(p.trim { it <= ' ' })
                    } catch (e: Exception) {
                        Log.e(TAG, "Error: ${e.message}", e)
                    }
                }
            }
        }

        mInterface = b.establish()
        if (mInterface == null) {
            Log.e(TAG, "VpnService.Builder.establish() returned null")
        } else {
            Log.d(TAG, "VpnService established with fd=${mInterface!!.fd}")
        }
    }

    private fun start(fd: Int, server: String?, port: Int, user: String?, passwd: String?, dns: String?, dnsPort: Int, ipv6: Boolean, udpgw: String?) {
        // configure()/establish() run on the main thread (VpnService.Builder API).
        // Everything blocking below — config write, pdnsd spawn, hostname
        // resolution, tun2socks spawn, sendfd poll — runs on a background thread
        // so startup does not stall the main thread.
        // Clear any stale failure text from a previous attempt before connecting.
        mError = null
        val libDir = applicationInfo.nativeLibraryDir
        val dir = filesDir.absolutePath
        Thread {
            try {
                // NetShield policy: AdGuard cloud upstream where reachable,
                // local exclude lists in blocked/unknown countries.
                val netshield = Utility.netshieldPolicy(this, server, user)
                mNetshieldPolicy = netshield
                Utility.makePdnsdConf(this, dns ?: "8.8.8.8", dnsPort, netshield.exclusions, netshield.upstream)

                // Launch pdnsd non-blocking: no waitFor() (pdnsd.conf sets
                // daemon=on so it forks into the background). It only needs to be
                // running by the time the tunnel carries the first DNS query. Keep
                // the Process reference so stopMe() can destroy it.
                if (!launchPdnsd(dir, libDir)) {
                    runOnMainThread { stopMe("pdnsd_start_failed") }
                    return@Thread
                }

                // FIX #5: resolve the SOCKS server hostname once on this background
                // thread and pass the resolved IP to tun2socks so the native binary
                // does NOT perform its own getaddrinfo during bring-up.
                val serverIp = try {
                    java.net.InetAddress.getByName(server).hostAddress
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to resolve SOCKS server '$server', using as-is", e)
                    server
                }
                mResolvedServer = serverIp

                // NAT64/DNS64 mobile networks resolve IPv4-only proxy hostnames
                // to an IPv6 (64:ff9b::/96) literal. tun2socks's BAddr parser
                // requires IPv6 in brackets ([addr]:port) or it exits immediately;
                // the Java probes below handle raw IPv6 fine, so only the native
                // command line needs the brackets.
                val socksAddr = if (serverIp != null && serverIp.contains(":")) "[$serverIp]:$port" else "$serverIp:$port"
                val command = mutableListOf(
                    "$libDir/libtun2socks.so",
                    "--netif-ipaddr", "10.10.10.2",
                    "--netif-netmask", "255.255.255.0",
                    "--socks-server-addr", socksAddr,
                    "--tunfd", fd.toString(),
                    "--tunmtu", "1500",
                    "--loglevel", "3"
                )

                if (!user.isNullOrEmpty()) {
                    command.add("--username")
                    command.add(user!!)
                    command.add("--password")
                    command.add(passwd ?: "")
                }

                if (ipv6) {
                    command.add("--netif-ip6addr")
                    command.add("fdfe:dcba:9876::2")
                }

                command.add("--dnsgw")
                command.add("10.10.10.1:8091")

                if (udpgw != null && udpgw.isNotEmpty()) {
                    command.add("--udpgw-remote-server-addr")
                    command.add(udpgw)
                }

                val loggable = command.mapIndexed { i, arg ->
                    if (i > 0 && command[i - 1] == "--password") "***" else arg
                }.joinToString(" ")
                Log.d(TAG, "tun2socks full command: $loggable")

                // Start tun2socks non-blocking (no daemonization). Store Process for later cleanup.
                try {
                    val pb = ProcessBuilder(command)
                    pb.redirectErrorStream(true)
                    val process = pb.start()
                    mTun2socksProcess = process
                    Log.d(TAG, "tun2socks process started with PID awareness")

                    // Consume stdout/stderr on a background thread to prevent buffer deadlock
                    Thread {
                        try {
                            val reader = java.io.BufferedReader(java.io.InputStreamReader(process.inputStream))
                            var line = reader.readLine()
                            while (line != null) {
                                Log.d(TAG, "tun2socks: $line")
                                line = reader.readLine()
                            }
                            val exitCode = process.waitFor()
                            Log.d(TAG, "tun2socks process exited with: $exitCode")
                            if (exitCode != 0 && mRunning) {
                                Log.e(TAG, "tun2socks exited unexpectedly with code $exitCode")
                                // Only stop if we haven't already initiated shutdown
                                runOnMainThread { stopMe("tun2socks_exited:$exitCode") }
                            }
                        } catch (e: Exception) {
                            Log.e(TAG, "tun2socks monitor error: ${e.message}")
                        }
                    }.apply { isDaemon = true }.start()
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to start tun2socks process", e)
                    runOnMainThread { stopMe("tun2socks_start_failed:${e.message}") }
                    return@Thread
                }

                // FIX #1: short fixed poll for sendfd instead of a 1s..5s sleep ramp
                // (up to 15s on the main thread). Poll every 50ms up to 100 attempts
                // (~5s cap).
                var attempts = 0
                while (attempts < 100) {
                    val sendResult = System.sendfd(fd)
                    if (sendResult != -1) {
                        Log.d(TAG, "sendfd succeeded on attempt ${attempts + 1}/100")
                        mRunning = true
                        // FIX #2: connected is now immediate on tunnel-up; the IP check
                        // is posted below as async enrichment.
                        runOnMainThread { postStartOnMain() }
                        return@Thread
                    }
                    attempts++
                    Log.d(TAG, "sendfd attempt $attempts/100 returned: $sendResult")
                    try {
                        Thread.sleep(50)
                    } catch (e: Exception) {
                        Log.e(TAG, "Error: ${e.message}", e)
                    }
                }

                Log.e(TAG, "sendfd failed after 100 attempts, stopping VPN")
                runOnMainThread { stopMe("sendfd_failed_5_attempts") }
                return@Thread
            } catch (e: Exception) {
                Log.e(TAG, "Vpn startup failed", e)
                runOnMainThread { stopMe("start_failed:${e.message}") }
            }
        }.apply { isDaemon = true }.start()
    }

    private fun consumeProcessOutput(process: java.lang.Process?) {
        process ?: return
        Thread {
            try {
                val reader = java.io.BufferedReader(java.io.InputStreamReader(process.inputStream))
                var line = reader.readLine()
                while (line != null) {
                    if (line.isNotEmpty()) Log.d(TAG, "pdnsd: $line")
                    line = reader.readLine()
                }
                process.waitFor()
            } catch (_: Exception) {
            }
        }.apply { isDaemon = true }.start()
    }

    private fun launchPdnsd(dir: String, libDir: String): Boolean {
        return try {
            val pdnsdPb = ProcessBuilder(
                "$libDir/libpdnsd.so",
                "-c",
                "$dir/pdnsd.conf"
            )
            pdnsdPb.redirectErrorStream(true)
            val pdnsd = pdnsdPb.start()
            mPdnsdProcess = pdnsd
            consumeProcessOutput(pdnsd)
            Log.d(TAG, "pdnsd started non-blocking")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start pdnsd process", e)
            false
        }
    }

    /**
     * Re-evaluates the NetShield upstream once the real exit country is known
     * (ip-api check) and applies a new pdnsd config + restart when the policy
     * changed (e.g. residential rotation moved the exit to/from a blocked
     * country). Runs on the main thread; pdnsd config write + restart are fast.
     */
    private fun reconcileNetshield() {
        if (!mRunning) return
        val applied = mNetshieldPolicy ?: return
        if (applied.upstream == null && applied.exclusions.isEmpty()) return
        val server = mServer
        if (server.isNullOrEmpty()) return

        val policy = Utility.netshieldPolicy(this, server, mUsername, mCountryCode)
        if (policy == applied) return

        mNetshieldPolicy = policy
        val dir = filesDir.absolutePath
        val libDir = applicationInfo.nativeLibraryDir
        Utility.makePdnsdConf(this, mDns ?: "8.8.8.8", mDnsPort, policy.exclusions, policy.upstream)
        try {
            mPdnsdProcess?.destroy()
        } catch (e: Exception) {
            Log.e(TAG, "Error destroying pdnsd for reconcile: ${e.message}")
        }
        mPdnsdProcess = null
        if (launchPdnsd(dir, libDir)) {
            Log.d(TAG, "NetShield policy reconciled: upstream=${policy.upstream} exclusions=${policy.exclusions.size}")
        } else {
            Log.e(TAG, "pdnsd restart failed during NetShield reconcile")
        }
    }

    private fun postStartOnMain() {
        if (!mRunning) return
        // FIX #2: connected is marked at tunnel-up, not after the HTTP IP check.
        mConnectedSince = java.lang.System.currentTimeMillis()
        mError = null
        mProxyVerified = false
        mIpCheckFailures = 0
        loadProfileBytes(mProfileName)
        mReceivedBytes = 0L
        mSentBytes = 0L
        mBaseRx = TrafficStats.getUidRxBytes(Process.myUid()).coerceAtLeast(0L)
        mBaseTx = TrafficStats.getUidTxBytes(Process.myUid()).coerceAtLeast(0L)
        mStatsHandler.post(mStatsRunnable)
        mIpCheckHandler.post(mIpCheckRunnable)

        val prefs = PreferenceManager.getDefaultSharedPreferences(this)
        if (prefs.getBoolean(PREF_AUTO_STOP, false)) {
            val filter = IntentFilter(Intent.ACTION_SCREEN_OFF)
            registerReceiverCompat(mScreenOffReceiver, filter)
        }
        registerReceiverCompat(mScreenOnReceiver, IntentFilter(Intent.ACTION_SCREEN_ON))
    }

    private fun runOnMainThread(action: () -> Unit) {
        Handler(Looper.getMainLooper()).post(action)
    }

    companion object {
        private const val TAG = "SocksVpnService"
        private const val CHANNEL_ID = "floating_control"
        private const val NOTIFICATION_ID = 2
        private const val IP_CHECK_INTERVAL = 30000L
        private const val IP_INFO_RETRY = 500L
        private const val IP_CHECK_RETRY = 5000L
        private const val MAX_IP_CHECK_FAILURES = 3
        private const val DOZE_CHECK_INTERVAL = 60000L
        private const val STATS_INTERVAL = 1000L
        private const val USAGE_PERSIST_TICKS = 5L
    }
}
