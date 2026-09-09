package net.typeblog.socks.util

import android.content.Context
import android.content.res.Configuration
import androidx.preference.PreferenceManager

/**
 * Single source of truth for the effective app theme.
 *
 * Settings > Theme stores a manual override ([Constants.PREF_THEME_MODE]:
 * "light" / "dark" / "system"). Compose reads it in KiloProxyTheme, but the
 * floating bubble, its popup menu, and the status label live outside Compose
 * (WindowManager views in FloatingControlService / BubbleMenuOverlay) and used
 * to read only the SYSTEM night bit — so a manual Dark/Light pick, or a phone
 * theme change while set to Device theme, never reached them.
 *
 * Every theme-aware element must go through [isDarkTheme]; View inflation that
 * relies on -night resources must use [themedContext].
 */
object ThemeMode {
    fun isDarkTheme(context: Context): Boolean {
        val mode = try {
            PreferenceManager.getDefaultSharedPreferences(context)
                .getString(Constants.PREF_THEME_MODE, "light") ?: "light"
        } catch (_: Exception) {
            "light"
        }
        return when (mode) {
            "dark" -> true
            "light" -> false
            else -> (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
                Configuration.UI_MODE_NIGHT_YES
        }
    }

    /**
     * Context whose night qualifier matches the effective theme, so -night
     * resources (popup panel, search field, rows, icons) follow the manual
     * Theme setting instead of the raw system uiMode.
     */
    fun themedContext(context: Context): Context {
        val wantNight = isDarkTheme(context)
        val resNightYes =
            (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
                Configuration.UI_MODE_NIGHT_YES
        if (wantNight == resNightYes) return context
        val config = Configuration(context.resources.configuration)
        config.uiMode = (config.uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or
            if (wantNight) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO
        return context.createConfigurationContext(config)
    }
}
