package net.typeblog.socks.util

import android.content.Context
import android.content.SharedPreferences
import net.typeblog.socks.util.Constants.ROUTE_ALL

class Profile internal constructor(
    private val mContext: Context,
    private val mPref: SharedPreferences,
    name: String
) {
    private val mName = name
    private val mPrefix = prefPrefix(name)

    init {
        val legacy = mName.replace("_", "__").replace(" ", "_")
        if (legacy != mPrefix) {
            val all = mPref.all
            if (all.containsKey(legacy + "server")) {
                val ed = mPref.edit()
                for ((k, v) in all) {
                    if (k.startsWith(legacy)) {
                        val suffix = k.removePrefix(legacy)
                        when (v) {
                            is String -> ed.putString(mPrefix + suffix, v)
                            is Int -> ed.putInt(mPrefix + suffix, v)
                            is Boolean -> ed.putBoolean(mPrefix + suffix, v)
                            is Float -> ed.putFloat(mPrefix + suffix, v)
                            is Long -> ed.putLong(mPrefix + suffix, v)
                        }
                        ed.remove(k)
                    }
                }
                ed.apply()
            }
        }
    }

    fun getName(): String = mName

    fun getServer(): String {
        return mPref.getString(key("server"), "127.0.0.1")!!
    }

    fun setServer(server: String) {
        mPref.edit().putString(key("server"), server).apply()
    }

    fun getPort(): Int {
        return mPref.getInt(key("port"), 1080)
    }

    fun setPort(port: Int) {
        mPref.edit().putInt(key("port"), port).apply()
    }

    fun isUserPw(): Boolean {
        return mPref.getBoolean(key("userpw"), false)
    }

    fun setIsUserpw(isUserpw: Boolean) {
        mPref.edit().putBoolean(key("userpw"), isUserpw).apply()
    }

    fun getUsername(): String {
        return mPref.getString(key("username"), "")!!
    }

    fun setUsername(username: String) {
        mPref.edit().putString(key("username"), username).apply()
    }

    fun getPassword(): String {
        return mPref.getString(key("password"), "")!!
    }

    fun setPassword(password: String) {
        mPref.edit().putString(key("password"), password).apply()
    }

    fun getRoute(): String {
        return mPref.getString(key("route"), ROUTE_ALL)!!
    }

    fun setRoute(route: String) {
        mPref.edit().putString(key("route"), route).apply()
    }

    fun getDns(): String {
        return mPref.getString(key("dns"), "8.8.8.8")!!
    }

    fun setDns(dns: String) {
        mPref.edit().putString(key("dns"), dns).apply()
    }

    fun getDnsPort(): Int {
        return mPref.getInt(key("dns_port"), 53)
    }

    fun setDnsPort(port: Int) {
        mPref.edit().putInt(key("dns_port"), port).apply()
    }

    fun isPerApp(): Boolean {
        return mPref.getBoolean(key("perapp"), false)
    }

    fun setIsPerApp(isPerApp: Boolean) {
        mPref.edit().putBoolean(key("perapp"), isPerApp).apply()
    }

    fun isBypassApp(): Boolean {
        return mPref.getBoolean(key("appbypass"), false)
    }

    fun setIsBypassApp(isBypassApp: Boolean) {
        mPref.edit().putBoolean(key("appbypass"), isBypassApp).apply()
    }

    fun getAppList(): String {
        return mPref.getString(key("applist"), "")!!
    }

    fun setAppList(list: String) {
        mPref.edit().putString(key("applist"), list).apply()
    }

    fun hasIPv6(): Boolean {
        return mPref.getBoolean(key("ipv6"), false)
    }

    fun setHasIPv6(has: Boolean) {
        mPref.edit().putBoolean(key("ipv6"), has).apply()
    }

    fun hasUDP(): Boolean {
        return mPref.getBoolean(key("udp"), false)
    }

    fun setHasUDP(has: Boolean) {
        mPref.edit().putBoolean(key("udp"), has).apply()
    }

    fun getUDPGW(): String {
        return mPref.getString(key("udpgw"), "127.0.0.1:7300")!!
    }

    fun setUDPGW(gw: String) {
        mPref.edit().putString(key("udpgw"), gw).apply()
    }

    fun autoConnect(): Boolean {
        return mPref.getBoolean(key("auto"), false)
    }

    fun setAutoConnect(auto: Boolean) {
        mPref.edit().putBoolean(key("auto"), auto).apply()
    }

    internal fun delete() {
        mPref.edit()
            .remove(key("server"))
            .remove(key("port"))
            .remove(key("userpw"))
            .remove(key("username"))
            .remove(key("password"))
            .remove(key("route"))
            .remove(key("dns"))
            .remove(key("dns_port"))
            .remove(key("perapp"))
            .remove(key("appbypass"))
            .remove(key("applist"))
            .remove(key("ipv6"))
            .remove(key("udp"))
            .remove(key("udpgw"))
            .remove(key("auto"))
            .apply()
    }

    internal fun copyTo(newName: String) {
        val newPrefix = prefPrefix(newName)
        val editor = mPref.edit()
        for (k in arrayOf(
            "server", "port", "userpw", "username", "password",
            "route", "dns", "dns_port", "perapp", "appbypass", "applist",
            "ipv6", "udp", "udpgw", "auto"
        )) {
            val value = mPref.all[mPrefix + k]
            if (value != null) {
                when (value) {
                    is String -> editor.putString(newPrefix + k, value)
                    is Int -> editor.putInt(newPrefix + k, value)
                    is Boolean -> editor.putBoolean(newPrefix + k, value)
                    is Float -> editor.putFloat(newPrefix + k, value)
                    is Long -> editor.putLong(newPrefix + k, value)
                }
            }
        }
        editor.apply()
    }

    private fun key(k: String): String = mPrefix + k

    companion object {
        private fun prefPrefix(name: String): String {
            return java.net.URLEncoder.encode(name, "UTF-8")
        }
    }
}
