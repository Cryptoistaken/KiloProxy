package net.typeblog.socks.ui.screens

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.net.VpnService
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import android.content.SharedPreferences
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.preference.PreferenceManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.typeblog.socks.BuildConfig
import net.typeblog.socks.R
import net.typeblog.socks.ui.components.SettingsItem
import net.typeblog.socks.ui.components.ThemePickerDialog
import net.typeblog.socks.ui.components.UpdateDialog
import net.typeblog.socks.util.Constants.BUBBLE_STYLE_LOCK
import net.typeblog.socks.util.Constants.PREF_ADV_PER_APP
import net.typeblog.socks.util.Constants.PREF_BUBBLE_STYLE
import net.typeblog.socks.util.Constants.PREF_FLOATING_CONTROL
import net.typeblog.socks.util.Constants.PREF_NETSHIELD_ENABLED
import net.typeblog.socks.util.Constants.PREF_THEME_MODE
import net.typeblog.socks.util.UpdateChecker

@Composable
fun SettingsScreen(
    onNavigateToSplitTunneling: () -> Unit,
    onNavigateToBubbleSettings: () -> Unit,
    onNavigateToNetShield: () -> Unit,
    onNavigateToDebugLogs: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val prefs = PreferenceManager.getDefaultSharedPreferences(context)

    var themeMode by remember {
        mutableStateOf(prefs.getString(PREF_THEME_MODE, "light") ?: "light")
    }
    var floatingControl by remember {
        mutableStateOf(prefs.getBoolean(PREF_FLOATING_CONTROL, false))
    }
    var bubbleStyle by remember {
        mutableStateOf(prefs.getString(PREF_BUBBLE_STYLE, BUBBLE_STYLE_LOCK) ?: BUBBLE_STYLE_LOCK)
    }
    var netShieldEnabled by remember {
        mutableStateOf(prefs.getBoolean(PREF_NETSHIELD_ENABLED, false))
    }
    var splitEnabled by remember {
        mutableStateOf(prefs.getBoolean(PREF_ADV_PER_APP, false))
    }
    DisposableEffect(context) {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            when (key) {
                PREF_THEME_MODE -> themeMode = prefs.getString(PREF_THEME_MODE, "light") ?: "light"
                PREF_FLOATING_CONTROL -> floatingControl = prefs.getBoolean(PREF_FLOATING_CONTROL, false)
                PREF_BUBBLE_STYLE -> bubbleStyle = prefs.getString(PREF_BUBBLE_STYLE, BUBBLE_STYLE_LOCK) ?: BUBBLE_STYLE_LOCK
                PREF_NETSHIELD_ENABLED -> netShieldEnabled = prefs.getBoolean(PREF_NETSHIELD_ENABLED, false)
                PREF_ADV_PER_APP -> splitEnabled = prefs.getBoolean(PREF_ADV_PER_APP, false)
            }
        }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        onDispose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }
    var showThemeDialog by remember { mutableStateOf(false) }
    var checkingUpdates by remember { mutableStateOf(false) }
    var updateInfo by remember { mutableStateOf<UpdateChecker.UpdateInfo?>(null) }
    var whatsNewLoading by remember { mutableStateOf(false) }
    var whatsNewError by remember { mutableStateOf(false) }
    var whatsNewInfo by remember { mutableStateOf<UpdateChecker.UpdateInfo?>(null) }
    val scope = rememberCoroutineScope()

    val themeLabel = when (themeMode) {
        "dark" -> "Dark"
        "system" -> "Device theme"
        else -> "Light"
    }

    fun saveString(key: String, value: String) {
        prefs.edit().putString(key, value).apply()
    }

    fun saveBoolean(key: String, value: Boolean) {
        prefs.edit().putBoolean(key, value).apply()
        if (key == PREF_FLOATING_CONTROL && value) {
            if (prefs.getString(PREF_BUBBLE_STYLE, null) == null) {
                prefs.edit().putString(PREF_BUBBLE_STYLE, BUBBLE_STYLE_LOCK).apply()
            }
        }
    }

    if (showThemeDialog) {
        ThemePickerDialog(
            current = themeMode,
            onSelect = { value ->
                themeMode = value
                saveString(PREF_THEME_MODE, value)
                showThemeDialog = false
            },
            onDismiss = { showThemeDialog = false }
        )
    }

    updateInfo?.let { info ->
        UpdateDialog(info = info, onDismiss = { updateInfo = null })
    }

    if (whatsNewLoading) {
        AlertDialog(
            onDismissRequest = {},
            title = { Text(text = context.getString(R.string.whats_new_dialog_title)) },
            text = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
                    Spacer(modifier = Modifier.width(16.dp))
                    Text(text = context.getString(R.string.whats_new_loading))
                }
            },
            confirmButton = {}
        )
    }

    if (whatsNewError) {
        AlertDialog(
            onDismissRequest = { whatsNewError = false },
            title = { Text(text = context.getString(R.string.whats_new_dialog_title)) },
            text = { Text(text = context.getString(R.string.whats_new_error)) },
            confirmButton = {
                TextButton(onClick = { whatsNewError = false }) {
                    Text(text = "OK")
                }
            }
        )
    }

    whatsNewInfo?.let { info ->
        AlertDialog(
            onDismissRequest = { whatsNewInfo = null },
            title = { Text(text = info.tag) },
            text = { Text(text = info.body.ifBlank { context.getString(R.string.whats_new_error) }) },
            confirmButton = {
                TextButton(onClick = { whatsNewInfo = null }) {
                    Text(text = "OK")
                }
            }
        )
    }

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp)
    ) {
        item {
            Text(
                text = "Settings",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(top = 16.dp, bottom = 4.dp)
            )
        }

        // ═══ Features (NetShield, Split tunneling — mirrors ProtonVPN Features group) ═══
        item {
            SectionTitle(text = "Features")
            SettingsGroup {
                SettingsItem(
                    icon = painterResource(
                        if (netShieldEnabled) R.drawable.feature_netshield_on
                        else R.drawable.feature_netshield_off
                    ),
                    label = "NetShield",
                    description = "Block ads, trackers & malware",
                    value = if (netShieldEnabled) "On" else "Off",
                    onClick = onNavigateToNetShield
                )
                SettingsItem(
                    icon = painterResource(
                        if (splitEnabled) R.drawable.feature_splittunneling_on
                        else R.drawable.feature_splittunneling_off
                    ),
                    label = "Enable split tunneling",
                    description = if (splitEnabled) "On" else "Off",
                    onClick = onNavigateToSplitTunneling
                )
            }
        }

        // ═══ General (Theme, Floating Bubble — like Split Tunneling page) ═══
        item {
            SectionTitle(text = "General")
            SettingsGroup {
                SettingsItem(
                    icon = painterResource(R.drawable.ic_proton_circle_half_filled),
                    label = "Theme Mode",
                    description = themeLabel,
                    onClick = { showThemeDialog = true }
                )
                SettingsItem(
                    icon = painterResource(R.drawable.ic_proton_mobile),
                    label = "Floating Bubble",
                    description = if (floatingControl) {
                        if (bubbleStyle == BUBBLE_STYLE_LOCK) "Lock • On — tap to configure style & preview" else "Classic • On — tap to configure"
                    } else "Off — Tap to enable and choose style",
                    value = if (floatingControl) "On" else "Off",
                    onClick = onNavigateToBubbleSettings
                )
            }
        }

        // ═══ Support ═══
        item {
            SectionTitle(text = "Support")
            SettingsGroup {
                SettingsItem(
                    icon = painterResource(R.drawable.ic_proton_arrow_out_square),
                    label = "Updates",
                    description = "Download and install the latest version",
                    onClick = {
                        if (!checkingUpdates) {
                            scope.launch {
                                checkingUpdates = true
                                Toast.makeText(context, "Checking for updates…", Toast.LENGTH_SHORT).show()
                                val info = withContext(Dispatchers.IO) { UpdateChecker.check() }
                                checkingUpdates = false
                                if (info == null) {
                                    Toast.makeText(context, "You're up to date", Toast.LENGTH_SHORT).show()
                                } else {
                                    updateInfo = info
                                }
                            }
                        }
                    }
                )
                SettingsItem(
                    icon = painterResource(R.drawable.ic_proton_code),
                    label = "Debug Logs",
                    description = "View and share app logs for troubleshooting",
                    onClick = onNavigateToDebugLogs
                )
                SettingsItem(
                    icon = painterResource(R.drawable.ic_proton_earth),
                    label = context.getString(R.string.settings_whats_new),
                    description = "Release notes for the latest version",
                    onClick = {
                        if (!whatsNewLoading) {
                            scope.launch {
                                whatsNewLoading = true
                                whatsNewError = false
                                whatsNewInfo = null
                                val info = withContext(Dispatchers.IO) {
                                    UpdateChecker.fetchLatestNotes()
                                }
                                whatsNewLoading = false
                                if (info == null) {
                                    whatsNewError = true
                                } else {
                                    whatsNewInfo = info
                                }
                            }
                        }
                    }
                )
                SettingsItem(
                    icon = painterResource(R.drawable.lucide_send),
                    label = context.getString(R.string.settings_report_issue),
                    description = "Chat with us on Telegram",
                    onClick = {
                        context.startActivity(
                            Intent(Intent.ACTION_VIEW, Uri.parse("https://t.me/Cryptoistaken"))
                        )
                    }
                )
            }
        }

        // ═══ About ═══
        item {
            SectionTitle(text = "About")
            SettingsGroup {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 56.dp)
                        .padding(horizontal = 16.dp, vertical = 16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "KiloProxy",
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = "Version ${BuildConfig.VERSION_NAME}",
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 6.dp)
                        )
                    }
                }
            }
        }

        item { Spacer(modifier = Modifier.height(16.dp)) }
    }
}

@Composable
private fun RowDivider() {
    HorizontalDivider(
        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)
    )
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        fontWeight = FontWeight.Medium,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 24.dp, bottom = 8.dp)
    )
}

@Composable
private fun SettingsGroup(content: @Composable () -> Unit) {
    Column(modifier = Modifier.fillMaxWidth()) {
        content()
    }
}