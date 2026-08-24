package net.typeblog.socks.util

import android.content.Context
import androidx.preference.PreferenceManager

object KiloProxyAuth {
    private const val KEY_UID = "kiloproxy_telegram_id"
    private const val KEY_USERNAME = "kiloproxy_username"
    private const val KEY_DEVICE = "kiloproxy_device_id"

    fun isLoggedIn(context: Context): Boolean {
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        return prefs.getString(KEY_UID, null)?.isNotEmpty() == true
    }

    fun getUid(context: Context): String? {
        return PreferenceManager.getDefaultSharedPreferences(context).getString(KEY_UID, null)
    }

    fun getUsername(context: Context): String? {
        return PreferenceManager.getDefaultSharedPreferences(context).getString(KEY_USERNAME, null)
    }

    fun saveLogin(context: Context, uid: String, username: String?) {
        PreferenceManager.getDefaultSharedPreferences(context).edit()
            .putString(KEY_UID, uid)
            .putString(KEY_USERNAME, username ?: "")
            .apply()
    }

    fun clear(context: Context) {
        PreferenceManager.getDefaultSharedPreferences(context).edit()
            .remove(KEY_UID)
            .remove(KEY_USERNAME)
            .remove(KEY_DEVICE)
            .apply()
    }

    fun getOrCreateDeviceId(context: Context): String {
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        var id = prefs.getString(KEY_DEVICE, null)
        if (id.isNullOrEmpty()) {
            id = (0..15).map { (('a'..'z') + ('0'..'9')).random() }.joinToString("")
            prefs.edit().putString(KEY_DEVICE, id).apply()
        }
        return id
    }

    fun getCountry(context: Context): String {
        // Try multiple sources: resources locale, default locale, SIM, network
        try {
            val localeCountry = try { context.resources.configuration.locales.get(0).country } catch (_: Exception) { "" }
            if (localeCountry.isNotEmpty() && localeCountry.length == 2) return localeCountry.uppercase()
        } catch (_: Exception) {}
        try {
            val def = java.util.Locale.getDefault().country
            if (def.isNotEmpty() && def.length == 2) return def.uppercase()
        } catch (_: Exception) {}
        try {
            val tm = context.getSystemService(Context.TELEPHONY_SERVICE) as? android.telephony.TelephonyManager
            val sim = tm?.simCountryIso?.trim()?.uppercase()
            if (!sim.isNullOrEmpty() && sim.length == 2) return sim
            val net = tm?.networkCountryIso?.trim()?.uppercase()
            if (!net.isNullOrEmpty() && net.length == 2) return net
        } catch (_: Exception) {}
        return "Unknown"
    }
}
