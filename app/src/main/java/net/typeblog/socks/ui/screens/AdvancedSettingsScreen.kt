package net.typeblog.socks.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
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
import androidx.compose.ui.window.Dialog
import androidx.preference.PreferenceManager
import net.typeblog.socks.R
import net.typeblog.socks.ui.components.ProtonDialogRadioRow
import net.typeblog.socks.ui.components.ProtonSwitch
import net.typeblog.socks.ui.components.SettingsItem
import net.typeblog.socks.ui.components.rememberPref
import net.typeblog.socks.util.Constants.ACCEL_PRIMARY_KILOIP
import net.typeblog.socks.util.Constants.ACCEL_PRIMARY_TRACE
import net.typeblog.socks.util.Constants.PREF_ACCEL_CACHE_IP
import net.typeblog.socks.util.Constants.PREF_ACCEL_DNS_CACHE
import net.typeblog.socks.util.Constants.PREF_ACCEL_PRIMARY
import net.typeblog.socks.util.Constants.PREF_ACCEL_PROBE
import net.typeblog.socks.util.Constants.PREF_VPN_ACCELERATOR

/**
 * Advanced Settings: experimental connect-time options for our SOCKS5
 * client. Same name idea as Proton on purpose, different meaning: no
 * server fleet here, so these only speed up repeat connects, never
 * throughput. The engine honors them only while Accelerator is on.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AdvancedSettingsScreen(
    onNavigateBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val prefs = PreferenceManager.getDefaultSharedPreferences(context)

    var acceleratorEnabled by rememberPref(prefs, PREF_VPN_ACCELERATOR) {
        it.getBoolean(PREF_VPN_ACCELERATOR, false)
    }
    var primary by rememberPref(prefs, PREF_ACCEL_PRIMARY) {
        it.getString(PREF_ACCEL_PRIMARY, ACCEL_PRIMARY_TRACE) ?: ACCEL_PRIMARY_TRACE
    }
    var cacheIp by rememberPref(prefs, PREF_ACCEL_CACHE_IP) {
        it.getBoolean(PREF_ACCEL_CACHE_IP, false)
    }
    var probe by rememberPref(prefs, PREF_ACCEL_PROBE) {
        it.getBoolean(PREF_ACCEL_PROBE, true)
    }
    var dnsCache by rememberPref(prefs, PREF_ACCEL_DNS_CACHE) {
        it.getBoolean(PREF_ACCEL_DNS_CACHE, true)
    }

    var showPrimaryDialog by remember { mutableStateOf(false) }

    if (showPrimaryDialog) {
        PrimaryDialog(
            primary = primary,
            onSelect = {
                primary = it
                prefs.edit().putString(PREF_ACCEL_PRIMARY, it).apply()
                showPrimaryDialog = false
            },
            onDismiss = { showPrimaryDialog = false }
        )
    }

    val primaryLabel = if (primary == ACCEL_PRIMARY_KILOIP) "Kilo IP" else "Trace"

    Scaffold(
        modifier = modifier,
        contentWindowInsets = WindowInsets(0),
        topBar = {
            TopAppBar(
                title = { Text("Advanced Settings") },
                windowInsets = WindowInsets(0),
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(
                            painter = painterResource(R.drawable.lucide_arrow_left),
                            contentDescription = "Back"
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    titleContentColor = MaterialTheme.colorScheme.onSurface
                )
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .verticalScroll(rememberScrollState())
        ) {
            // Feature header
            Column(modifier = Modifier.padding(horizontal = 20.dp, vertical = 16.dp)) {
                Icon(
                    painter = painterResource(R.drawable.ic_settings_sliders),
                    contentDescription = null,
                    modifier = Modifier.size(64.dp),
                    tint = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = "Advanced Settings",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(top = 16.dp)
                )
                Text(
                    text = "Experimental options for faster repeat connects. Applies when Accelerator is on.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp)
                )
            }

            // Master toggle card
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.surfaceContainerLow
            ) {
                val onToggle: (Boolean) -> Unit = { newValue ->
                    acceleratorEnabled = newValue
                    prefs.edit().putBoolean(PREF_VPN_ACCELERATOR, newValue).apply()
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onToggle(!acceleratorEnabled) }
                        .padding(horizontal = 20.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Accelerator",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.weight(1f)
                    )
                    ProtonSwitch(
                        checked = acceleratorEnabled,
                        onCheckedChange = onToggle
                    )
                }
            }

            SectionTitle(text = "Accelerator")
            SettingsItem(
                icon = painterResource(R.drawable.lucide_server),
                label = "Primary checker",
                description = primaryLabel,
                showChevron = false,
                onClick = { showPrimaryDialog = true }
            )
            SwitchRow(
                iconRes = R.drawable.lucide_eye,
                label = "Cache last IP",
                checked = cacheIp,
                onCheckedChange = {
                    cacheIp = it
                    prefs.edit().putBoolean(PREF_ACCEL_CACHE_IP, it).apply()
                }
            )
            SwitchRow(
                iconRes = R.drawable.lucide_send,
                label = "Proxy health probe",
                checked = probe,
                onCheckedChange = {
                    probe = it
                    prefs.edit().putBoolean(PREF_ACCEL_PROBE, it).apply()
                }
            )
            SwitchRow(
                iconRes = R.drawable.ic_proton_filter,
                label = "Cache proxy DNS",
                checked = dnsCache,
                onCheckedChange = {
                    dnsCache = it
                    prefs.edit().putBoolean(PREF_ACCEL_DNS_CACHE, it).apply()
                }
            )
            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        fontWeight = FontWeight.Medium,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 16.dp, top = 24.dp, bottom = 8.dp)
    )
}

@Composable
private fun SwitchRow(
    iconRes: Int,
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    SettingsItem(
        icon = painterResource(iconRes),
        label = label,
        description = if (checked) "On" else "Off",
        trailing = {
            ProtonSwitch(
                checked = checked,
                onCheckedChange = onCheckedChange
            )
        },
        onClick = { onCheckedChange(!checked) }
    )
}

@Composable
private fun PrimaryDialog(
    primary: String,
    onSelect: (String) -> Unit,
    onDismiss: () -> Unit
) {
    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(24.dp),
            color = MaterialTheme.colorScheme.surfaceContainerLow
        ) {
            Column(modifier = Modifier.padding(24.dp)) {
                Text(
                    text = "Primary checker",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(modifier = Modifier.height(12.dp))
                ProtonDialogRadioRow(
                    title = "Trace",
                    description = "Fast IP and country lookup for a quick connect display.",
                    selected = primary == ACCEL_PRIMARY_TRACE,
                    onClick = { onSelect(ACCEL_PRIMARY_TRACE) }
                )
                DialogHairline()
                ProtonDialogRadioRow(
                    title = "Kilo IP",
                    description = "Full location and network details for the status display.",
                    selected = primary == ACCEL_PRIMARY_KILOIP,
                    onClick = { onSelect(ACCEL_PRIMARY_KILOIP) }
                )
            }
        }
    }
}

@Composable
private fun DialogHairline() {
    Surface(
        modifier = Modifier.fillMaxWidth().height(1.dp),
        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)
    ) {}
}
