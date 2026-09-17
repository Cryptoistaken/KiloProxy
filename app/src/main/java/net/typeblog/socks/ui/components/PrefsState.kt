package net.typeblog.socks.ui.components

import android.content.SharedPreferences
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember

/**
 * Mirrors one SharedPreferences entry as Compose state. Reads once, then
 * refreshes on external pref changes. Replaces the repeated
 * remember-plus-OnSharedPreferenceChangeListener blocks across screens.
 * Local writes still go through prefs.edit() directly; the listener echo
 * sets the same value back and is harmless.
 */
@Composable
fun <T> rememberPref(
    prefs: SharedPreferences,
    key: String,
    read: (SharedPreferences) -> T
): MutableState<T> {
    val state = remember(key) { mutableStateOf(read(prefs)) }
    DisposableEffect(prefs, key) {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, k ->
            if (k == key) state.value = read(prefs)
        }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        onDispose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }
    return state
}
