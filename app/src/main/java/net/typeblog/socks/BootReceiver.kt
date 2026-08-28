package net.typeblog.socks

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.UserManager
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
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            val um = context.getSystemService(Context.USER_SERVICE) as? UserManager
            if (um != null && !um.isUserUnlocked) return
        }
        val p: Profile = try {
            ProfileManager.getInstance(context.applicationContext).getDefault()
        } catch (_: Exception) {
            return
        }

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
