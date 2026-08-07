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
 *   Generic    any                           username ...[-_]<cc>
 */
object ProxyProviders {
    const val TYPE_CUSTOM = "custom"
    const val TYPE_OWL = "owl"
    const val TYPE_RAPID = "rapid"
    const val TYPE_CLIP = "clip"
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

    fun label(type: String): String = when (type) {
        TYPE_OWL -> "OwlProxy"
        TYPE_RAPID -> "RapidProxy"
        TYPE_CLIP -> "ClipProxy"
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
        TYPE_GENERIC -> genericParts(username)?.country
        else -> null
    }

    fun extractBase(username: String, type: String): String? = when (type) {
        TYPE_OWL -> Regex("^(.+?)_custom_zone_[a-zA-Z]{2}(.*)$").find(username)?.groupValues?.get(1)
        TYPE_RAPID -> Regex("^(.+)-residential-[a-zA-Z]{2}(.*)$").find(username)?.groupValues?.get(1)
        TYPE_CLIP -> Regex("^(.+)-region-[a-zA-Z]{2}(.*)$").find(username)?.groupValues?.get(1)
        TYPE_GENERIC -> genericParts(username)?.base
        else -> null
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
        TYPE_GENERIC -> "$base$separator${if (upper) countryCode.uppercase() else countryCode.lowercase()}"
        else -> null
    }

    private fun hostEndsWith(host: String, domain: String): Boolean {
        val h = host.trim().lowercase()
        return h == domain || h.endsWith(".$domain")
    }
}
