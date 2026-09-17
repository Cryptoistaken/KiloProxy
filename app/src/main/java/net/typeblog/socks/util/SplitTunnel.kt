package net.typeblog.socks.util

import android.content.SharedPreferences
import net.typeblog.socks.util.Constants.PREF_ADV_APP_LIST
import net.typeblog.socks.util.Constants.PREF_ADV_PER_APP

/**
 * Single home for split-tunnel (Include-only) list handling. The newline-
 * joined `adv_app_list` pref is parsed and formatted only here, so the
 * engine guard, the UI guards, and the picker can never drift apart.
 */
object SplitTunnel {

    /** Parse the newline-joined app list pref into a clean set. */
    @JvmStatic
    fun parseAppList(raw: String?): Set<String> =
        raw?.split("\n")?.map { it.trim() }?.filter { it.isNotEmpty() }?.toSet()
            ?: emptySet()

    /** Format a set back to the newline-joined pref value. */
    @JvmStatic
    fun joinAppList(pkgs: Set<String>): String = pkgs.joinToString("\n")

    /**
     * True when split tunneling is ON but no effective app remains (empty
     * list, or only our own package, which the engine always skips). Callers
     * must refuse to connect and send the user to the apps list instead: an
     * empty allow-list would drag our own UID into the tunnel and deadlock
     * the proxy handshake.
     */
    @JvmStatic
    fun isIncludeEmpty(prefs: SharedPreferences, selfPackage: String): Boolean {
        if (!prefs.getBoolean(PREF_ADV_PER_APP, false)) return false
        return parseAppList(prefs.getString(PREF_ADV_APP_LIST, ""))
            .filter { it != selfPackage }
            .isEmpty()
    }
}
