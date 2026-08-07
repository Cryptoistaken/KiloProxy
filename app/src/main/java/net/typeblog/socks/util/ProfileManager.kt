package net.typeblog.socks.util

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import net.typeblog.socks.R
import net.typeblog.socks.util.Constants.PREF
import net.typeblog.socks.util.Constants.PREF_LAST_PROFILE
import net.typeblog.socks.util.Constants.PREF_PROFILE

class ProfileManager private constructor(private val mContext: Context) {
    private val mPref: SharedPreferences
    private val mFactory: ProfileFactory
    private val mProfiles = ArrayList<String>()

    init {
        try {
            val masterKey = MasterKey.Builder(mContext)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
            mPref = EncryptedSharedPreferences.create(
                mContext,
                PREF,
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
        } catch (e: Exception) {
            throw RuntimeException("Failed to create EncryptedSharedPreferences", e)
        }
        mFactory = ProfileFactory.getInstance(mContext, mPref)
        reload()
    }

    fun reload() {
        mProfiles.clear()
        val defaultName = mContext.getString(R.string.prof_default)
        mProfiles.add(defaultName)

        val profiles = mPref.getString(PREF_PROFILE, "")!!.split("\n")

        for (p in profiles) {
            if (p.isNotEmpty() && p != defaultName) {
                mProfiles.add(p)
            }
        }
    }

    fun getProfiles(): Array<String> {
        return mProfiles.toTypedArray()
    }

    fun getProfile(name: String): Profile? {
        return if (!mProfiles.contains(name)) {
            null
        } else {
            mFactory.getProfile(name)
        }
    }

    fun getDefault(): Profile {
        return getProfile(mPref.getString(PREF_LAST_PROFILE, mProfiles[0])!!)!!
    }

    fun switchDefault(name: String) {
        if (mProfiles.contains(name))
            mPref.edit().putString(PREF_LAST_PROFILE, name).apply()
    }

    fun addProfile(name: String): Profile? {
        return if (mProfiles.contains(name)) {
            null
        } else {
            mProfiles.add(name)
            mProfiles.removeAt(0)
            mPref.edit()
                .putString(PREF_PROFILE, Utility.join(mProfiles, "\n"))
                .putString(PREF_LAST_PROFILE, name)
                .apply()
            reload()
            getDefault()
        }
    }

    fun removeProfile(name: String): Boolean {
        if (name.equals(mProfiles[0]) || !mProfiles.contains(name)) {
            return false
        }

        getProfile(name)!!.delete()

        mProfiles.removeAt(0)
        mProfiles.remove(name)

        mPref.edit()
            .putString(PREF_PROFILE, Utility.join(mProfiles, "\n"))
            .remove(PREF_LAST_PROFILE)
            .apply()
        reload()

        return true
    }

    fun renameProfile(oldName: String, newName: String): Boolean {
        if (oldName == newName) return true
        if (!mProfiles.contains(oldName)) return false
        if (oldName.equals(mProfiles[0]) || mProfiles.contains(newName)) return false

        val oldProfile = getProfile(oldName)!!
        oldProfile.copyTo(newName)
        oldProfile.delete()

        mProfiles[mProfiles.indexOf(oldName)] = newName

        val editor = mPref.edit()
            // Skip the leading "Default" placeholder so it isn't persisted as a real profile
            .putString(PREF_PROFILE, Utility.join(mProfiles.drop(1), "\n"))
        if (mPref.getString(PREF_LAST_PROFILE, null) == oldName) {
            editor.putString(PREF_LAST_PROFILE, newName)
        }
        editor.apply()
        reload()

        return true
    }

    companion object {
        private var sInstance: ProfileManager? = null

        fun getInstance(context: Context): ProfileManager {
            if (sInstance == null) {
                sInstance = ProfileManager(context)
            }
            return sInstance!!
        }
    }
}
