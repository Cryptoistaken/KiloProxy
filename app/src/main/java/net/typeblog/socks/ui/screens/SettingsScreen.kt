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
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.preference.PreferenceManager
import net.typeblog.socks.BuildConfig
import net.typeblog.socks.FloatingControlService
import net.typeblog.socks.R
import net.typeblog.socks.ui.components.SettingsItem
import net.typeblog.socks.ui.components.ThemePickerDialog
import net.typeblog.socks.util.Constants.PREF_FLOATING_CONTROL
import net.typeblog.socks.util.Constants.PREF_NETSHIELD_MODE
import net.typeblog.socks.util.Constants.PREF_THEME_MODE

@Composable
fun SettingsScreen(
    onNavigateToSplitTunneling: () -> Unit,
    onNavigateToDebugLogs: () -> Unit,
    onNavigateToNetShield: () -> Unit,
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
    val netShieldMode by remember {
        mutableStateOf(prefs.getString(PREF_NETSHIELD_MODE, "off") ?: "off")
    }
    var showThemeDialog by remember { mutableStateOf(false) }

    val themeLabel = when (themeMode) {
        "dark" -> "Dark"
        else -> "Light"
    }

    val netShieldLabel = when (netShieldMode) {
        "standard" -> "Standard"
        "strict" -> "Strict"
        else -> "Off"
    }

    fun saveString(key: String, value: String) {
        prefs.edit().putString(key, value).apply()
    }

    fun saveBoolean(key: String, value: Boolean) {
        prefs.edit().putBoolean(key, value).apply()
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

    var pendingFloatingStart by remember { mutableStateOf(false) }

    fun canDrawOverlays(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(context)

    fun startFloatingControl(context: Context) {
        val intent = Intent(context, FloatingControlService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(intent)
        } else {
            context.startService(intent)
        }
    }

    val vpnPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (pendingFloatingStart) {
            pendingFloatingStart = false
            if (result.resultCode == android.app.Activity.RESULT_OK && canDrawOverlays(context)) {
                floatingControl = true
                saveBoolean(PREF_FLOATING_CONTROL, true)
                startFloatingControl(context)
            }
        }
    }

    fun requestVpnPermissionAndStart(context: Context) {
        val prepareIntent = VpnService.prepare(context)
        if (prepareIntent == null) {
            floatingControl = true
            saveBoolean(PREF_FLOATING_CONTROL, true)
            startFloatingControl(context)
        } else {
            pendingFloatingStart = true
            vpnPermissionLauncher.launch(prepareIntent)
        }
    }

    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (pendingFloatingStart) {
            pendingFloatingStart = false
            if (granted && canDrawOverlays(context)) {
                requestVpnPermissionAndStart(context)
            }
        }
    }

    val overlayPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) {
        if (!canDrawOverlays(context)) return@rememberLauncherForActivityResult
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            pendingFloatingStart = true
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            requestVpnPermissionAndStart(context)
        }
    }

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp)
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

        item {
            SectionTitle(text = "Features")
            SettingsGroup {
                SettingsItem(
                    icon = painterResource(R.drawable.lucide_eye_off),
                    label = "NetShield",
                    description = "Blocks ads, trackers and malware",
                    value = netShieldLabel,
                    iconTint = MaterialTheme.colorScheme.primary,
                    onClick = onNavigateToNetShield
                )
                RowDivider()
                SettingsItem(
                    icon = painterResource(R.drawable.lucide_arrows_right_left),
                    label = "Split tunneling",
                    description = "Choose which apps use the VPN",
                    iconTint = MaterialTheme.colorScheme.primary,
                    onClick = onNavigateToSplitTunneling
                )
            }
        }

        item {
            SectionTitle(text = "General")
            SettingsGroup {
                SettingsItem(
                    icon = painterResource(R.drawable.lucide_palette),
                    label = "Theme",
                    value = themeLabel,
                    iconTint = MaterialTheme.colorScheme.primary,
                    onClick = { showThemeDialog = true }
                )
                RowDivider()
                SettingsItem(
                    icon = painterResource(R.drawable.lucide_hand),
                    label = "Floating bubble",
                    description = "Quick access to the VPN from anywhere",
                    iconTint = MaterialTheme.colorScheme.primary,
                    trailing = {
                        Switch(
                            checked = floatingControl,
                            onCheckedChange = { enabled ->
                                if (enabled) {
                                    if (canDrawOverlays(context)) {
                                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                                            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
                                            PackageManager.PERMISSION_GRANTED
                                        ) {
                                            pendingFloatingStart = true
                                            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                                        } else {
                                            requestVpnPermissionAndStart(context)
                                        }
                                    } else {
                                        pendingFloatingStart = true
                                        val intent = Intent(
                                            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                            Uri.parse("package:${context.packageName}")
                                        )
                                        overlayPermissionLauncher.launch(intent)
                                    }
                                } else {
                                    floatingControl = false
                                    saveBoolean(PREF_FLOATING_CONTROL, false)
                                    context.stopService(Intent(context, FloatingControlService::class.java))
                                }
                            }
                        )
                    }
                )
            }
        }

        item {
            SectionTitle(text = "Support")
            SettingsGroup {
                SettingsItem(
                    icon = painterResource(R.drawable.lucide_settings),
                    label = "Debug logs",
                    description = "View and share logs for troubleshooting",
                    iconTint = MaterialTheme.colorScheme.primary,
                    onClick = onNavigateToDebugLogs
                )
                RowDivider()
                SettingsItem(
                    icon = painterResource(R.drawable.lucide_send),
                    label = "Support",
                    description = "Get help with KiloProxy",
                    iconTint = MaterialTheme.colorScheme.primary,
                    showChevron = false
                )
            }
        }

        item {
            SectionTitle(text = "About")
            SettingsGroup {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "KiloProxy",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
                RowDivider()
                SettingsItem(
                    icon = painterResource(R.drawable.lucide_server),
                    label = "Version",
                    value = "v${BuildConfig.VERSION_NAME}",
                    iconTint = MaterialTheme.colorScheme.primary,
                    showChevron = false
                )
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
        style = MaterialTheme.typography.labelSmall,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 2.dp, bottom = 8.dp)
    )
}

@Composable
private fun SettingsGroup(content: @Composable () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            content()
        }
    }
}