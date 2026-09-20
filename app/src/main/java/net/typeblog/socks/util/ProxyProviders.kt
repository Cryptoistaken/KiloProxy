package net.typeblog.socks.util

/**
 * Provider identification for country-aware proxy services.
 *
 * A profile is attributed to a known provider when its host matches the
 * provider's gateway domain OR its username carries the provider's country
 * marker (host takes priority). Unknown providers fall back to a generic
 * detection: a username whose last segment is a 2-letter ISO country code
 * preceded by `-` or `_` (e.g. `user-US`, `acct_de`).
 *
 *   OwlProxy   host *.owlproxy.com            username ..._custom_zone_<cc>
 *   RapidProxy host *.rapidproxy.io           username ...-residential-<cc>
 *   ClipProxy  host *.cliproxy.io             username ...-region-<cc>
 *   IpDeep     host *.ipdeep.com              username ...-res-country-<cc>[-state-<city>]-session-<id>-sessiontime-<t>]
 *              (the -state-<city> segment is optional; sticky = -session- block present)
 *   ProxyRise  host *.proxyrise.com            username res-<cc> (e.g. res-aq)
 *              (country zone is exactly res-<cc>; base is always "res")
 *   Generic    any                           username ...[-_]<cc>
  */
object ProxyProviders {
    const val TYPE_CUSTOM = "custom"
    const val TYPE_OWL = "owl"
    const val TYPE_RAPID = "rapid"
    const val TYPE_CLIP = "clip"
    const val TYPE_IPDEEP = "ipdeep"
    const val TYPE_PROXYRISE = "proxyrise"
    const val TYPE_GENERIC = "generic"

    data class GenericParts(
        val base: String,
        val separator: String,
        val upper: Boolean,
        val country: String
    )

    fun detectType(host: String, username: String): String = when {
        isOwl(host, username) -> TYPE_OWL
        isRapid(host, username) -> TYPE_RAPID
        isClip(host, username) -> TYPE_CLIP
        isIpDeep(host, username) -> TYPE_IPDEEP
        isProxyRise(host, username) -> TYPE_PROXYRISE
        genericParts(username) != null -> TYPE_GENERIC
        else -> TYPE_CUSTOM
    }

    fun isOwl(host: String, username: String): Boolean =
        hostEndsWith(host, "owlproxy.com") || username.contains("_custom_zone_")

    fun isRapid(host: String, username: String): Boolean =
        hostEndsWith(host, "rapidproxy.io") ||
            Regex("^(.+)-residential-[a-zA-Z]{2}(.*)$").matches(username)

    fun isClip(host: String, username: String): Boolean =
        hostEndsWith(host, "cliproxy.io") ||
            Regex("^(.+)-region-[a-zA-Z]{2}(.*)$").matches(username)

    fun isIpDeep(host: String, username: String): Boolean =
        hostEndsWith(host, "ipdeep.com") ||
            Regex("^(.+)-res-country-[a-zA-Z]{2}(.*)$").matches(username)

    fun isProxyRise(host: String, username: String): Boolean =
        hostEndsWith(host, "proxyrise.com") ||
            Regex("^res-[a-zA-Z]{2}$").matches(username.trim())

    fun label(type: String): String = when (type) {
        TYPE_OWL -> "OwlProxy"
        TYPE_RAPID -> "RapidProxy"
        TYPE_CLIP -> "ClipProxy"
        TYPE_IPDEEP -> "IpDeep"
        TYPE_PROXYRISE -> "Proxyrise"
        TYPE_GENERIC -> "Custom"
        else -> "Custom"
    }

    /**
     * Splits a generic country-code username, e.g. `user-US` or `acct_de`.
     * Requires the trailing 2-letter token to be a real ISO country code.
     */
    fun genericParts(username: String): GenericParts? {
        val m = Regex("^(.+)([-_])([A-Za-z]{2})$").find(username) ?: return null
        val token = m.groupValues[3]
        val cc = token.uppercase()
        if (Countries.ALL.none { it.code == cc }) return null
        return GenericParts(m.groupValues[1], m.groupValues[2], token.all { it.isUpperCase() }, cc)
    }

    fun parseCountry(username: String, type: String): String? = when (type) {
        TYPE_OWL -> Regex("^(.+?)_custom_zone_([a-zA-Z]{2})(.*)$").find(username)?.groupValues?.get(2)
        TYPE_RAPID -> Regex("^(.+)-residential-([a-zA-Z]{2})(.*)$").find(username)?.groupValues?.get(2)
        TYPE_CLIP -> Regex("^(.+)-region-([a-zA-Z]{2})(.*)$").find(username)?.groupValues?.get(2)
        TYPE_IPDEEP -> Regex("^(.+)-res-country-([a-zA-Z]{2})(.*)$").find(username)?.groupValues?.get(2)
        TYPE_PROXYRISE -> Regex("^res-([a-zA-Z]{2})$").find(username.trim())?.groupValues?.get(1)
        TYPE_GENERIC -> genericParts(username)?.country
        else -> null
    }

    /**
     * Display country code (uppercased) for a server+username pair, or null.
     * Same derivation everywhere so cards, sheets, and screens agree.
     */
    fun displayCountry(host: String, username: String): String? {
        val type = detectType(host, username)
        return parseCountry(username, type)?.uppercase()
    }

    fun extractBase(username: String, type: String): String? = when (type) {
        TYPE_OWL -> Regex("^(.+?)_custom_zone_[a-zA-Z]{2}(.*)$").find(username)?.groupValues?.get(1)
        TYPE_RAPID -> Regex("^(.+)-residential-[a-zA-Z]{2}(.*)$").find(username)?.groupValues?.get(1)
        TYPE_CLIP -> Regex("^(.+)-region-[a-zA-Z]{2}(.*)$").find(username)?.groupValues?.get(1)
        TYPE_IPDEEP -> Regex("^(.+)-res-country-[a-zA-Z]{2}(.*)$").find(username)?.groupValues?.get(1)
        TYPE_PROXYRISE -> "res"
        TYPE_GENERIC -> genericParts(username)?.base
        else -> null
    }

    /**
     * Rewrites an IpDeep username to a new country, preserving any trailing
     * block (`-state-<city>-session-<id>-sessiontime-<t>` or the stateless
     * `-session-<id>-sessiontime-<t>`) untouched.
     */
    fun switchIpDeepCountry(username: String, countryCode: String): String? {
        val m = Regex("^(.+)-res-country-[a-zA-Z]{2}(.*)$").find(username) ?: return null
        return "${m.groupValues[1]}-res-country-${countryCode.lowercase()}${m.groupValues[2]}"
    }

    /** IpDeep sticky session = a `-session-<id>` block is present. */
    fun isIpDeepSticky(username: String): Boolean =
        Regex("-session-\\d+").containsMatchIn(username)

    /** Session id from an IpDeep sticky username, if present. */
    fun ipdeepSessionId(username: String): String? =
        Regex("-session-(\\d+)").find(username)?.groupValues?.get(1)

    /** Stick time (minutes) from an IpDeep sticky username, if present. */
    fun parseIpDeepTime(username: String): Int? =
        Regex("-sessiontime-(\\d+)").find(username)?.groupValues?.get(1)?.toIntOrNull()

    /**
     * Builds an IpDeep username. Sticky keeps (or mints) a numeric session id
     * and appends `-session-<id>-sessiontime-<t>`; unique strips the block.
     */
    fun buildIpDeep(
        base: String,
        countryCode: String,
        mode: String,
        time: Int,
        sessionId: String?
    ): String {
        val zone = "$base-res-country-${countryCode.lowercase()}"
        if (mode != "sticky") return zone
        val sid = sessionId ?: (1000000000L..9999999999L).random().toString()
        return "$zone-session-$sid-sessiontime-$time"
    }

    /**
     * Rebuilds a username for the given country. Known providers embed the
     * country in a fixed format (Owl lower case, Rapid/Clip upper case). For
     * generic profiles the original separator and casing are preserved.
     */
    fun buildUsername(
        base: String,
        type: String,
        countryCode: String,
        mode: String = "unique",
        time: Int = 5,
        separator: String = "-",
        upper: Boolean = true
    ): String? = when (type) {
        TYPE_OWL -> {
            val zone = "_custom_zone_${countryCode.lowercase()}"
            if (mode == "sticky") {
                val sid = (10000000..99999999).random()
                "${base}${zone}_st__city_sid_${sid}_time_${time}"
            } else {
                "${base}${zone}"
            }
        }
        TYPE_RAPID -> "${base}-residential-${countryCode.uppercase()}"
        TYPE_CLIP -> "${base}-region-${countryCode.uppercase()}"
        TYPE_IPDEEP -> "${base}-res-country-${countryCode.lowercase()}"
        TYPE_PROXYRISE -> "res-${countryCode.lowercase()}"
        TYPE_GENERIC -> "$base$separator${if (upper) countryCode.uppercase() else countryCode.lowercase()}"
        else -> null
    }

    private fun hostEndsWith(host: String, domain: String): Boolean {
        val h = host.trim().lowercase()
        return h == domain || h.endsWith(".$domain")
    }

    /**
     * Display name derived from a hostname: the second-level domain label
     * with the first letter capitalized. `gw.proxyrise.com` -> "Proxyrise",
     * `proxy.example.com` -> "Example". Returns null for IPs, single
     * labels, and blanks so callers can fall back to a fixed label.
     */
    fun nameFromHost(host: String): String? {
        val h = host.trim().lowercase()
        if (h.isEmpty() || h.matches(Regex("^[0-9.]+$")) || h.matches(Regex("^\\[[0-9a-fA-F:]+\\]$"))) return null
        val parts = h.split(".").filter { it.isNotEmpty() }
        if (parts.size < 2) return null
        val sld = parts[parts.size - 2].replace(Regex("[^a-z0-9]"), "")
        if (sld.isEmpty()) return null
        return sld.replaceFirstChar { it.uppercase() }
    }

    /**
     * Rewrite a username to a new provider country zone, or null when the
     * profile type does not support country switching. Pure string logic;
     * callers persist the result and restart the VPN themselves.
     */
    fun switchCountry(host: String, username: String, countryCode: String): String? {
        return when (val type = detectType(host, username)) {
            TYPE_OWL -> {
                // Preserve sticky suffix if present; rebuild only the country zone.
                val match = Regex("^(.+?)_custom_zone_[a-zA-Z]{2}(_st__city_sid_\\d+_time_\\d+)?$")
                    .find(username) ?: return null
                val base = match.groupValues[1]
                "${base}_custom_zone_${countryCode.lowercase()}${match.groupValues[2]}"
            }
            TYPE_RAPID, TYPE_CLIP -> {
                val base = extractBase(username, type) ?: return null
                buildUsername(base, type, countryCode)
            }
            TYPE_IPDEEP -> switchIpDeepCountry(username, countryCode)
            TYPE_PROXYRISE -> if (Regex("^res-[a-zA-Z]{2}$").matches(username.trim())) {
                buildUsername("res", type, countryCode)
            } else {
                null
            }
            TYPE_GENERIC -> {
                val parts = genericParts(username) ?: return null
                buildUsername(
                    parts.base, type, countryCode,
                    separator = parts.separator, upper = parts.upper
                )
            }
            else -> null
        }
    }
}
