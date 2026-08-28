package net.typeblog.socks.util

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import net.typeblog.socks.R
import net.typeblog.socks.util.Constants.PREF
import net.typeblog.socks.util.Constants.PREF_LAST_PROFILE
import net.typeblog.socks.util.Constants.PREF_PROFILE

class ProfileManager private constructor(context: Context) {
    private val mContext: Context = context.applicationContext
    private val mPref: SharedPreferences
    private val mFactory: ProfileFactory
    private val mProfiles = ArrayList<String>()

    init {
        mPref = try {
            createEncryptedPrefs(mContext)
        } catch (e: Exception) {
            try {
                mContext.deleteSharedPreferences(PREF)
                try {
                    val ks = java.security.KeyStore.getInstance("AndroidKeyStore")
                    ks.load(null)
                    ks.deleteEntry(MasterKey.DEFAULT_MASTER_KEY_ALIAS)
                } catch (_: Exception) {}
            } catch (_: Exception) {}
            createEncryptedPrefs(mContext)
        }
        mFactory = ProfileFactory.getInstance(mContext, mPref)
        reload()
    }

    private fun createEncryptedPrefs(ctx: Context): SharedPreferences {
        val masterKey = MasterKey.Builder(ctx)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        return EncryptedSharedPreferences.create(
            ctx,
            PREF,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    @Synchronized
    fun reload() {
        mProfiles.clear()
        val defaultName = mContext.getString(R.string.prof_default)
        mProfiles.add(defaultName)
        val raw = mPref.getString(PREF_PROFILE, "") ?: ""
        val profiles = if (raw.isEmpty()) emptyList() else raw.split("\n").filter { it.isNotEmpty() }
        for (p in profiles) {
            if (p != defaultName) {
                mProfiles.add(p)
            }
        }
    }

    @Synchronized
    fun getProfiles(): Array<String> {
        return mProfiles.toTypedArray()
    }

    fun getProfile(name: String): Profile? {
        synchronized(this) { if (!mProfiles.contains(name)) return null }
        return mFactory.getProfile(name)
    }

    fun getDefault(): Profile {
        val key = synchronized(this) { mPref.getString(PREF_LAST_PROFILE, mProfiles[0])!! }
        return getProfile(key)!!
    }

    fun switchDefault(name: String) {
        synchronized(this) { if (!mProfiles.contains(name)) return }
        mPref.edit().putString(PREF_LAST_PROFILE, name).apply()
    }

    @Synchronized
    fun addProfile(name: String): Profile? {
        if (mProfiles.contains(name)) return null
        mProfiles.add(name)
        mPref.edit()
            .putString(PREF_PROFILE, Utility.join(mProfiles.drop(1), "\n"))
            .putString(PREF_LAST_PROFILE, name)
            .apply()
        reload()
        return getDefault()
    }

    @Synchronized
    fun removeProfile(name: String): Boolean {
        if (name == mProfiles[0] || !mProfiles.contains(name)) return false
        getProfile(name)!!.delete()
        mProfiles.remove(name)
        mPref.edit()
            .putString(PREF_PROFILE, Utility.join(mProfiles.drop(1), "\n"))
            .remove(PREF_LAST_PROFILE)
            .apply()
        reload()
        return true
    }

    @Synchronized
    fun renameProfile(oldName: String, newName: String): Boolean {
        if (oldName == newName) return true
        if (!mProfiles.contains(oldName)) return false
        if (oldName == mProfiles[0] || mProfiles.contains(newName)) return false
        val oldProfile = getProfile(oldName)!!
        oldProfile.copyTo(newName)
        oldProfile.delete()
        mProfiles[mProfiles.indexOf(oldName)] = newName
        val editor = mPref.edit()
            .putString(PREF_PROFILE, Utility.join(mProfiles.drop(1), "\n"))
        if (mPref.getString(PREF_LAST_PROFILE, null) == oldName) {
            editor.putString(PREF_LAST_PROFILE, newName)
        }
        editor.apply()
        reload()
        return true
    }

    companion object {
        @Volatile private var sInstance: ProfileManager? = null

        @Synchronized
        fun getInstance(context: Context): ProfileManager {
            sInstance?.let { return it }
            val inst = ProfileManager(context.applicationContext)
            sInstance = inst
            return inst
        }
    }
}
