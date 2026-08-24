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
        return try {
            val locale = context.resources.configuration.locales.get(0)
            locale.country.ifEmpty { "Unknown" }
        } catch (_: Exception) { "Unknown" }
    }
}
