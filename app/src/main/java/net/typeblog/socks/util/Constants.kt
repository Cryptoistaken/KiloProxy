package net.typeblog.socks.util

object Constants {
    const val ROUTE_ALL = "all"
    const val ROUTE_CHN = "chn"
    const val ROUTE_RU = "ru"
    const val ROUTE_RU_CHN = "ru_chn"
    const val INTENT_PREFIX = "SOCKS"
    const val INTENT_NAME = INTENT_PREFIX + "NAME"
    const val INTENT_SERVER = INTENT_PREFIX + "SERV"
    const val INTENT_PORT = INTENT_PREFIX + "PORT"
    const val INTENT_USERNAME = INTENT_PREFIX + "UNAME"
    const val INTENT_PASSWORD = INTENT_PREFIX + "PASSWD"
    const val INTENT_ROUTE = INTENT_PREFIX + "ROUTE"
    const val INTENT_DNS = INTENT_PREFIX + "DNS"
    const val INTENT_DNS_PORT = INTENT_PREFIX + "DNSPORT"
    const val INTENT_PER_APP = INTENT_PREFIX + "PERAPP"
    const val INTENT_APP_BYPASS = INTENT_PREFIX + "APPBYPASS"
    const val INTENT_APP_LIST = INTENT_PREFIX + "APPLIST"
    const val INTENT_IPV6_PROXY = INTENT_PREFIX + "IPV6"
    const val INTENT_UDP_GW = INTENT_PREFIX + "UDPGW"
    const val PREF = "profile"
    const val PREF_PROFILE = "profile"
    const val PREF_LAST_PROFILE = "last_profile"
    const val PREF_ADV_PER_APP = "adv_per_app"
    const val PREF_ADV_APP_BYPASS = "adv_app_bypass"
    const val PREF_ADV_APP_LIST = "adv_app_list"
    const val PREF_THEME_MODE = "theme_mode"
    const val PREF_AUTO_STOP = "auto_stop"

    const val PREF_FLOATING_CONTROL = "floating_control"
    const val PREF_BUBBLE_STYLE = "bubble_style"
    const val BUBBLE_STYLE_LOCK = "lock"
    const val BUBBLE_STYLE_CLASSIC = "classic"
    const val PREF_BUBBLE_X = "bubble_x"
    const val PREF_BUBBLE_Y = "bubble_y"
    const val PREF_SKIPPED_UPDATE_VERSION = "skipped_update_version"
    const val PREF_NETSHIELD_ENABLED = "netshield_enabled"
    const val PREF_NETSHIELD_BLOCK_ADULT = "netshield_block_adult"
    // Split-tunnel: global keys (PREF_ADV_*) are single source of truth (written by SplitTunnelingScreen);
    // Profile keys "perapp"/"appbypass"/"applist" are legacy per-profile aliases kept for compat.
    // Unique to this app's package so a sibling app built from the same base
    // code can never wake our receivers with its own broadcasts (and vice versa).
    const val ACTION_STOP_VPN = "com.kiloproxy.app.STOP_VPN"
    const val ACTION_START_VPN = "com.kiloproxy.app.START_VPN"
    const val ACTION_NETSHIELD_CHANGED = "com.kiloproxy.app.NETSHIELD_CHANGED"
}
