package net.typeblog.socks

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.provider.Settings
import android.util.Log
import androidx.preference.PreferenceManager
import net.typeblog.socks.util.Constants.PREF_FLOATING_CONTROL
import net.typeblog.socks.util.Profile
import net.typeblog.socks.util.ProfileManager
import net.typeblog.socks.util.Utility
import net.typeblog.socks.BuildConfig.DEBUG

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val p: Profile = ProfileManager.getInstance(context).getDefault()

        if (p.autoConnect() && VpnService.prepare(context) == null) {
            if (DEBUG) {
                Log.d(TAG, "starting VPN service on boot")
            }

            Utility.startVpn(context, p)
        }

        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        if (prefs.getBoolean(PREF_FLOATING_CONTROL, false) &&
            (Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(context))
        ) {
            if (DEBUG) {
                Log.d(TAG, "starting floating control service on boot")
            }
            FloatingControlService.start(context)
        }
    }

    companion object {
        private const val TAG = "BootReceiver"
    }
}
