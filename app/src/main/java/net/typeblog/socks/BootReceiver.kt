package net.typeblog.socks

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.util.Log
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
    }

    companion object {
        private const val TAG = "BootReceiver"
    }
}
