package net.typeblog.socks.util

/**
 * Notification text limits from the official Android docs, so the system
 * never truncates what we show.
 *
 * developer.android.com/design/ui/mobile/guides/home-screen/notifications:
 * content titles truncate to one line even when expanded (keep short, avoid
 * variables); body avoids exceeding 40 chars collapsed. Material 3
 * (m3.material.io/foundations/content-design/notifications): title <29,
 * collapsed body <40, buttons 1-2 words. Titles stay static and bodies are
 * hard-capped — no expanded (big) notifications by design.
 */
object NotifText {
    const val TITLE_MAX = 29
    const val BODY_MAX = 40

    /**
     * Hard-cap [text] to [max] chars with an ASCII ellipsis, so the system
     * has nothing left to truncate. Pure string logic, safe on any thread.
     */
    fun fit(text: String, max: Int = BODY_MAX): String {
        if (text.length <= max) return text
        if (max <= 3) return text.take(max.coerceAtLeast(0))
        return text.take(max - 3) + "..."
    }
}
