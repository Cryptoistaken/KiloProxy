package net.typeblog.socks.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import android.content.SharedPreferences
import androidx.compose.ui.platform.LocalContext
import androidx.preference.PreferenceManager
import net.typeblog.socks.util.Constants.PREF_THEME_MODE

private val LightColorScheme = lightColorScheme(
    primary = LightPrimary,
    onPrimary = LightOnPrimary,
    primaryContainer = LightPrimaryContainer,
    onPrimaryContainer = LightOnPrimaryContainer,
    background = LightBackground,
    onBackground = LightOnBackground,
    surface = LightSurface,
    surfaceVariant = LightSurfaceVariant,
    surfaceTint = LightSurfaceTint,
    outline = LightOutline,
    outlineVariant = LightOutlineVariant,
    onSurface = LightOnSurface,
    onSurfaceVariant = LightOnSurfaceVariant,
    tertiary = LightTertiary,
    error = LightError,
    surfaceContainerLow = LightSurfaceContainerLow,
    surfaceContainerHighest = LightSurfaceContainerHighest
)

private val DarkColorScheme = darkColorScheme(
    primary = DarkPrimary,
    onPrimary = DarkOnPrimary,
    primaryContainer = DarkPrimaryContainer,
    onPrimaryContainer = DarkOnPrimaryContainer,
    background = DarkBackground,
    onBackground = DarkOnBackground,
    surface = DarkSurface,
    surfaceVariant = DarkSurfaceVariant,
    surfaceTint = DarkSurfaceTint,
    outline = DarkOutline,
    outlineVariant = DarkOutlineVariant,
    onSurface = DarkOnSurface,
    onSurfaceVariant = DarkOnSurfaceVariant,
    tertiary = DarkTertiary,
    error = DarkError,
    surfaceContainerLow = DarkSurfaceContainerLow,
    surfaceContainerHighest = DarkSurfaceContainerHighest
)

@Composable
fun KiloProxyTheme(content: @Composable () -> Unit) {
    val context = LocalContext.current
    val prefs = PreferenceManager.getDefaultSharedPreferences(context)
    var themeMode by remember { mutableStateOf(prefs.getString(PREF_THEME_MODE, "light") ?: "light") }

    DisposableEffect(context) {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            when (key) {
                PREF_THEME_MODE -> themeMode = prefs.getString(PREF_THEME_MODE, "light") ?: "light"
            }
        }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        onDispose {
            prefs.unregisterOnSharedPreferenceChangeListener(listener)
        }
    }

    val useDarkTheme = when (themeMode) {
        "dark" -> true
        "light" -> false
        else -> isSystemInDarkTheme()
    }

    val colorScheme = if (useDarkTheme) DarkColorScheme else LightColorScheme

    MaterialTheme(
        colorScheme = colorScheme,
        typography = KiloProxyTypography,
        content = content
    )
}
