package net.typeblog.socks

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.VpnService
import android.os.Build
import android.util.Log
import androidx.preference.PreferenceManager
import net.typeblog.socks.util.Constants
import net.typeblog.socks.util.Constants.ACTION_START_VPN
import net.typeblog.socks.util.Constants.ACTION_STOP_VPN
import net.typeblog.socks.util.ProfileManager
import net.typeblog.socks.util.Utility

/**
 * Headless automation door: start/stop the tunnel from adb or scripts,
 * no UI taps. Engine logic is reused untouched.
 *
 *   adb shell am broadcast -a com.kiloproxy.app.START_VPN -p com.kiloproxy.app
 *   adb shell am broadcast -a com.kiloproxy.app.STOP_VPN  -p com.kiloproxy.app
 *   adb shell am broadcast -a com.kiloproxy.app.TOGGLE_VPN -p com.kiloproxy.app
 *
 * Manifest-exported, so it also fires when the app process is dead (the
 * system starts it). With the bubble enabled it forwards package-scoped to
 * FloatingControlService, whose NOT_EXPORTED receiver accepts same-uid
 * senders. With the bubble disabled it drives the engine directly, same as
 * BootReceiver does at boot.
 */
class ToggleReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val action = when (intent?.action) {
            ACTION_STOP_VPN -> ACTION_STOP_VPN
            ACTION_TOGGLE_VPN -> if (isVpnUp(context)) ACTION_STOP_VPN else ACTION_START_VPN
            else -> ACTION_START_VPN
        }
        if (isBubbleEnabled(context)) {
            ensureBubbleService(context)
            context.sendBroadcast(Intent(action).setPackage(context.packageName))
        } else {
            headless(context, action)
        }
        Log.d(TAG, "handled $action")
    }

    private fun isBubbleEnabled(context: Context): Boolean =
        PreferenceManager.getDefaultSharedPreferences(context)
            .getBoolean(Constants.PREF_FLOATING_CONTROL, false)

    private fun ensureBubbleService(context: Context) {
        if (isServiceRunning(context, FloatingControlService::class.java.name)) return
        FloatingControlService.start(context)
        try {
            Thread.sleep(800)
        } catch (_: InterruptedException) {
        }
    }

    private fun headless(context: Context, action: String) {
        if (action == ACTION_STOP_VPN) {
            val stop = Intent(context, SocksVpnService::class.java).setAction(ACTION_STOP_VPN)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(stop)
            } else {
                context.startService(stop)
            }
            return
        }
        if (VpnService.prepare(context) != null) {
            Log.w(TAG, "VPN consent not granted — open the app once to approve")
            return
        }
        try {
            val profile = ProfileManager.getInstance(context.applicationContext).getDefault()
            Utility.startVpn(context, profile)
        } catch (e: Exception) {
            Log.e(TAG, "headless start failed", e)
        }
    }

    private fun isVpnUp(context: Context): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return false
        return cm.getNetworkCapabilities(cm.activeNetwork)
            ?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true
    }

    @Suppress("DEPRECATION")
    private fun isServiceRunning(context: Context, className: String): Boolean {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? android.app.ActivityManager
            ?: return false
        return am.getRunningServices(Int.MAX_VALUE).any { it.service.className == className }
    }

    companion object {
        private const val TAG = "ToggleReceiver"
        const val ACTION_TOGGLE_VPN = "com.kiloproxy.app.TOGGLE_VPN"
    }
}
