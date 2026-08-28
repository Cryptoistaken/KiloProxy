package net.typeblog.socks.util

import android.content.Context
import android.content.SharedPreferences
import androidx.preference.PreferenceManager
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

object KiloProxyAuth {
    private const val KEY_UID = "kiloproxy_telegram_id"
    private const val KEY_USERNAME = "kiloproxy_username"
    private const val KEY_DEVICE = "kiloproxy_device_id"
    private const val SECURE_PREF = "kiloproxy_auth"

    private fun createEncryptedPrefs(ctx: Context): SharedPreferences {
        val masterKey = MasterKey.Builder(ctx)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        return EncryptedSharedPreferences.create(
            ctx,
            SECURE_PREF,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    private fun getSecurePrefs(context: Context): SharedPreferences {
        val appCtx = context.applicationContext
        return try {
            createEncryptedPrefs(appCtx)
        } catch (e: Exception) {
            try {
                appCtx.deleteSharedPreferences(SECURE_PREF)
                try {
                    val ks = java.security.KeyStore.getInstance("AndroidKeyStore")
                    ks.load(null)
                    ks.deleteEntry(MasterKey.DEFAULT_MASTER_KEY_ALIAS)
                } catch (_: Exception) {}
            } catch (_: Exception) {}
            createEncryptedPrefs(appCtx)
        }
    }

    private fun migratedGet(context: Context, key: String): String? {
        val secure = getSecurePrefs(context)
        var v = secure.getString(key, null)
        if (v == null) {
            val plain = PreferenceManager.getDefaultSharedPreferences(context.applicationContext).getString(key, null)
            if (!plain.isNullOrEmpty()) {
                secure.edit().putString(key, plain).apply()
                v = plain
            }
        }
        return v
    }

    fun isLoggedIn(context: Context): Boolean {
        return !migratedGet(context, KEY_UID).isNullOrEmpty()
    }

    fun getUid(context: Context): String? {
        return migratedGet(context, KEY_UID)
    }

    fun getUsername(context: Context): String? {
        return migratedGet(context, KEY_USERNAME)
    }

    fun saveLogin(context: Context, uid: String, username: String?) {
        getSecurePrefs(context).edit()
            .putString(KEY_UID, uid)
            .putString(KEY_USERNAME, username ?: "")
            .apply()
    }

    fun clear(context: Context) {
        getSecurePrefs(context).edit()
            .remove(KEY_UID)
            .remove(KEY_USERNAME)
            .remove(KEY_DEVICE)
            .apply()
        // also clear plaintext copies if any remain from pre-migration
        PreferenceManager.getDefaultSharedPreferences(context.applicationContext).edit()
            .remove(KEY_UID)
            .remove(KEY_USERNAME)
            .remove(KEY_DEVICE)
            .apply()
    }

    fun getOrCreateDeviceId(context: Context): String {
        val secure = getSecurePrefs(context)
        var id = secure.getString(KEY_DEVICE, null)
        if (!id.isNullOrEmpty()) return id
        // migrate from plaintext if present
        val plain = PreferenceManager.getDefaultSharedPreferences(context.applicationContext).getString(KEY_DEVICE, null)
        if (!plain.isNullOrEmpty()) {
            secure.edit().putString(KEY_DEVICE, plain).apply()
            return plain
        }
        // generate with SecureRandom, 16-char [a-z0-9]
        val chars = "abcdefghijklmnopqrstuvwxyz0123456789"
        val rnd = java.security.SecureRandom()
        val sb = StringBuilder(16)
        repeat(16) { sb.append(chars[rnd.nextInt(chars.length)]) }
        id = sb.toString()
        secure.edit().putString(KEY_DEVICE, id).apply()
        return id
    }

    fun getCountry(context: Context): String {
        // Prioritize SIM/network (real location) over locale (may be US English on BD device)
        try {
            val tm = context.getSystemService(Context.TELEPHONY_SERVICE) as? android.telephony.TelephonyManager
            val sim = tm?.simCountryIso?.trim()?.uppercase()
            if (!sim.isNullOrEmpty() && sim.length == 2) return sim
            val net = tm?.networkCountryIso?.trim()?.uppercase()
            if (!net.isNullOrEmpty() && net.length == 2) return net
        } catch (_: Exception) {}
        try {
            val localeCountry = try { context.resources.configuration.locales.get(0).country } catch (_: Exception) { "" }
            if (localeCountry.isNotEmpty() && localeCountry.length == 2) return localeCountry.uppercase()
        } catch (_: Exception) {}
        try {
            val def = java.util.Locale.getDefault().country
            if (def.isNotEmpty() && def.length == 2) return def.uppercase()
        } catch (_: Exception) {}
        return "Unknown"
    }
}
