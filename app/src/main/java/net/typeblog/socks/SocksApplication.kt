package net.typeblog.socks

import android.app.Application
import androidx.preference.PreferenceManager
import net.typeblog.socks.util.Constants.PREF_ADV_APP_BYPASS
import net.typeblog.socks.util.Constants.PREF_ADV_APP_LIST
import net.typeblog.socks.util.Constants.PREF_ADV_PER_APP
import net.typeblog.socks.util.Constants.PREF_SPLIT_SINGLE_MODE_MIGRATED
import net.typeblog.socks.util.ProfileManager

class SocksApplication : Application() {
    override fun onCreate() {
        super.onCreate()

        // Ensure default preference values are set before reading
        PreferenceManager.setDefaultValues(this, R.xml.settings, false)
        migrateSplitSingleMode()
    }

    /**
     * One-time migration for the single-mode (Include-only) split-tunneling
     * rework: wipes split-tunnel config (global keys + per-profile perapp /
     * appbypass / applist) while keeping proxy profiles (server, port,
     * credentials, route, dns) untouched, so split tunneling starts OFF for
     * updaters. Idempotent; safe to run in every process.
     */
    private fun migrateSplitSingleMode() {
        val prefs = PreferenceManager.getDefaultSharedPreferences(this)
        if (prefs.getBoolean(PREF_SPLIT_SINGLE_MODE_MIGRATED, false)) return
        prefs.edit()
            .remove(PREF_ADV_PER_APP)
            .remove(PREF_ADV_APP_BYPASS)
            .remove(PREF_ADV_APP_LIST)
            .putBoolean(PREF_SPLIT_SINGLE_MODE_MIGRATED, true)
            .apply()
        try {
            val manager = ProfileManager.getInstance(this)
            for (name in manager.getProfiles()) {
                val profile = manager.getProfile(name) ?: continue
                if (profile.isPerApp() || profile.isBypassApp() || profile.getAppList().isNotEmpty()) {
                    profile.setIsPerApp(false)
                    profile.setIsBypassApp(false)
                    profile.setAppList("")
                }
            }
        } catch (_: Exception) {
            // Profiles stay as-is; engine treats any surviving perapp as
            // Include-only, so no Exclude behavior can leak through.
        }
    }
}
