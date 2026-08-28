package net.typeblog.socks.util

import android.content.Context
import android.content.SharedPreferences
import java.lang.ref.WeakReference

internal class ProfileFactory private constructor(
    private val mContext: Context,
    private val mPref: SharedPreferences
) {
    private val mMap = HashMap<String, WeakReference<Profile>>()

    @Synchronized
    fun getProfile(name: String): Profile {
        var p = mMap[name]
        if (p == null || p.get() == null) {
            p = WeakReference(Profile(mContext, mPref, name))
            mMap[name] = p
        }
        return p.get()!!
    }

    companion object {
        @Volatile private var sInstance: ProfileFactory? = null

        @Synchronized
        fun getInstance(context: Context, pref: SharedPreferences): ProfileFactory {
            sInstance?.let { return it }
            val inst = ProfileFactory(context.applicationContext, pref)
            sInstance = inst
            return inst
        }
    }
}
