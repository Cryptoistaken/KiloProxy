package net.typeblog.socks.ui.screens

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.net.VpnService
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import android.content.SharedPreferences
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.preference.PreferenceManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.typeblog.socks.BuildConfig
import net.typeblog.socks.R
import net.typeblog.socks.ui.components.SettingsItem
import net.typeblog.socks.ui.components.UpdateDialog
import net.typeblog.socks.util.Constants.PREF_ADV_PER_APP
import net.typeblog.socks.util.Constants.PREF_FLOATING_CONTROL
import net.typeblog.socks.util.Constants.PREF_THEME_MODE
import net.typeblog.socks.util.Constants.PREF_VPN_ACCELERATOR
import net.typeblog.socks.util.UpdateChecker

@Composable
fun SettingsScreen(
    onNavigateToSplitTunneling: () -> Unit,
    onNavigateToTheme: () -> Unit,
    onNavigateToBubbleSettings: () -> Unit,
    onNavigateToDebugLogs: () -> Unit,
    onNavigateToVpnAccelerator: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val prefs = PreferenceManager.getDefaultSharedPreferences(context)

    var themeMode by remember {
        mutableStateOf(prefs.getString(PREF_THEME_MODE, "system") ?: "system")
    }
    var floatingControl by remember {
        mutableStateOf(prefs.getBoolean(PREF_FLOATING_CONTROL, false))
    }
    var splitEnabled by remember {
        mutableStateOf(prefs.getBoolean(PREF_ADV_PER_APP, false))
    }
    var acceleratorEnabled by remember {
        mutableStateOf(prefs.getBoolean(PREF_VPN_ACCELERATOR, false))
    }
    DisposableEffect(context) {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            when (key) {
                PREF_THEME_MODE -> themeMode = prefs.getString(PREF_THEME_MODE, "system") ?: "system"
                PREF_FLOATING_CONTROL -> floatingControl = prefs.getBoolean(PREF_FLOATING_CONTROL, false)
                PREF_ADV_PER_APP -> splitEnabled = prefs.getBoolean(PREF_ADV_PER_APP, false)
                PREF_VPN_ACCELERATOR -> acceleratorEnabled = prefs.getBoolean(PREF_VPN_ACCELERATOR, false)
            }
        }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        onDispose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }
    var checkingUpdates by remember { mutableStateOf(false) }
    var updateInfo by remember { mutableStateOf<UpdateChecker.UpdateInfo?>(null) }
    var updateResult by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    // Update check response shows in the row description for a few seconds.
    LaunchedEffect(updateResult) {
        if (updateResult != null) {
            delay(3000)
            updateResult = null
        }
    }

    val themeLabel = when (themeMode) {
        "dark" -> "Dark"
        "system" -> "Device theme"
        else -> "Light"
    }

    val useDarkTheme = when (themeMode) {
        "dark" -> true
        "light" -> false
        else -> isSystemInDarkTheme()
    }
    val bubbleGreen = if (useDarkTheme) Color(0xFF2CFFCC) else Color(0xFF1C9C7C)
    val bubbleRed = if (useDarkTheme) Color(0xFFF08FA4) else Color(0xFFCC2D4F)

    updateInfo?.let { info ->
        UpdateDialog(info = info, onDismiss = { updateInfo = null })
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

        // ═══ Features (Split tunneling, Theme, Floating Bubble) ═══
        item {
            SectionTitle(text = "Features")
            SettingsGroup {
                SettingsItem(
                    icon = painterResource(
                        if (splitEnabled) R.drawable.feature_splittunneling_on
                        else R.drawable.feature_splittunneling_off
                    ),
                    label = "Split tunneling",
                    description = if (splitEnabled) "On" else "Off",
                    showChevron = false,
                    iconTint = Color.Unspecified,
                    onClick = onNavigateToSplitTunneling
                )
                SettingsItem(
                    icon = painterResource(R.drawable.ic_proton_circle_half_filled),
                    label = "Theme",
                    description = themeLabel,
                    showChevron = false,
                    onClick = onNavigateToTheme
                )
                SettingsItem(
                    icon = painterResource(
                        if (floatingControl) R.drawable.ic_proton_lock_filled
                        else R.drawable.ic_proton_lock_open_filled_2
                    ),
                    label = "Floating Bubble",
                    description = if (floatingControl) "On" else "Off",
                    iconTint = if (floatingControl) bubbleGreen else bubbleRed,
                    onClick = onNavigateToBubbleSettings
                )
                SettingsItem(
                    icon = painterResource(R.drawable.lucide_arrows_right_left),
                    label = "VPN Accelerator",
                    description = if (acceleratorEnabled) "On" else "Off",
                    showChevron = false,
                    onClick = onNavigateToVpnAccelerator
                )
            }
        }

        // ═══ Support ═══
        item {
            SectionTitle(text = "Support")
            SettingsGroup {
                SettingsItem(
                    icon = painterResource(R.drawable.ic_update),
                    label = "Update",
                    description = if (checkingUpdates) "Checking"
                    else updateResult ?: "Update to the latest version",
                    iconSpinning = checkingUpdates,
                    onClick = {
                        if (!checkingUpdates) {
                            scope.launch {
                                checkingUpdates = true
                                updateResult = null
                                val info = withContext(Dispatchers.IO) { UpdateChecker.check() }
                                checkingUpdates = false
                                if (info == null) {
                                    updateResult = "You're up to date"
                                } else {
                                    updateResult = "Update available"
                                    updateInfo = info
                                }
                            }
                        }
                    }
                )
                SettingsItem(
                    icon = painterResource(R.drawable.ic_proton_code),
                    label = "Debug Logs",
                    description = "View logs",
                    onClick = onNavigateToDebugLogs
                )
                SettingsItem(
                    icon = painterResource(R.drawable.ic_telegram),
                    label = context.getString(R.string.settings_report_issue),
                    description = "Telegram",
                    iconTint = Color.Unspecified,
                    onClick = {
                        context.startActivity(
                            Intent(Intent.ACTION_VIEW, Uri.parse("https://t.me/Cryptoistaken"))
                        )
                    }
                )
            }
        }

        // ═══ Version (bottom center) ═══
        item {
            Text(
                text = "Version ${BuildConfig.VERSION_NAME}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 24.dp),
                textAlign = TextAlign.Center
            )
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