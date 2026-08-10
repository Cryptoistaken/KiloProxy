package net.typeblog.socks.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.preference.PreferenceManager
import kotlinx.coroutines.delay
import net.typeblog.socks.R
import net.typeblog.socks.ui.components.ConnectionStatusCard
import net.typeblog.socks.ui.components.DataUsageCard
import net.typeblog.socks.ui.viewmodel.VpnViewModel
import net.typeblog.socks.util.Constants
import net.typeblog.socks.util.ProfileManager
import net.typeblog.socks.util.ProxyProviders
import net.typeblog.socks.util.Utility

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StatusScreen(
    modifier: Modifier = Modifier,
    viewModel: VpnViewModel
) {
    val context = LocalContext.current
    val isRunning by viewModel.isRunning.collectAsState()
    val currentIp by viewModel.currentIp.collectAsState()
    val countryCode by viewModel.countryCode.collectAsState()
    val country by viewModel.country.collectAsState()
    val city by viewModel.city.collectAsState()
    val region by viewModel.region.collectAsState()
    val isp by viewModel.isp.collectAsState()
    val org by viewModel.org.collectAsState()
    val asName by viewModel.asName.collectAsState()
    val timezone by viewModel.timezone.collectAsState()
    val profiles by viewModel.profiles.collectAsState()
    val activeProfileName by viewModel.activeProfileName.collectAsState()
    val connectedSince by viewModel.connectedSince.collectAsState()
    val receivedBytes by viewModel.receivedBytes.collectAsState()
    val sentBytes by viewModel.sentBytes.collectAsState()
    val errorMessage by viewModel.errorMessage.collectAsState()
    val proxyVerified by viewModel.proxyVerified.collectAsState()
    val isConnecting by viewModel.isConnecting.collectAsState()

    var selectedProfile by remember { mutableStateOf<String?>(null) }
    var menuExpanded by remember { mutableStateOf(false) }

    val isActuallyConnected = isRunning && connectedSince > 0L && proxyVerified

    LaunchedEffect(errorMessage) {
        val message = errorMessage
        if (message != null) {
            Toast.makeText(context, message, Toast.LENGTH_LONG).show()
            viewModel.cancelConnect()
        }
    }

    LaunchedEffect(isConnecting) {
        if (isConnecting) {
            delay(20000)
            viewModel.onConnectTimeout()
        }
    }

    LaunchedEffect(profiles) {
        if (profiles.isNotEmpty() && (selectedProfile == null || selectedProfile !in profiles)) {
            val defaultName = try {
                ProfileManager.getInstance(context).getDefault().getName()
            } catch (_: Exception) {
                null
            }
            selectedProfile = if (defaultName != null && defaultName in profiles) {
                defaultName
            } else {
                profiles.firstOrNull()
            }
        }
    }

    LaunchedEffect(isRunning, activeProfileName) {
        if (isRunning && activeProfileName != null) {
            selectedProfile = activeProfileName
        }
    }

    val vpnPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == android.app.Activity.RESULT_OK) {
            viewModel.onVpnPermissionResult(context)
        } else {
            viewModel.cancelConnect()
        }
    }

    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { }

    val serverName = remember(activeProfileName, profiles) {
        if (activeProfileName != null) {
            try {
                val pm = ProfileManager.getInstance(context)
                val p = pm.getProfile(activeProfileName!!)
                if (p != null) "${p.getServer()}:${p.getPort()}" else ""
            } catch (_: Exception) {
                ""
            }
        } else {
            ""
        }
    }

    val displayProfileName = selectedProfile ?: activeProfileName ?: profiles.firstOrNull()
    val persistedUsage = remember(displayProfileName, receivedBytes, sentBytes, isRunning, proxyVerified) {
        if (isRunning && connectedSince > 0L && proxyVerified) {
            Triple(false, receivedBytes, sentBytes)
        } else {
            var rx = receivedBytes
            var tx = sentBytes
            if (rx <= 0L && tx <= 0L) {
                rx = 0L
                tx = 0L
                try {
                    val pm = ProfileManager.getInstance(context)
                    val p = displayProfileName?.let { pm.getProfile(it) }
                    if (p != null) {
                        val suffix = displayProfileName?.replace(Regex("[^A-Za-z0-9]"), "_") ?: ""
                        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
                        rx = prefs.getLong("usage_rx_${displayProfileName}_$suffix", 0L)
                        tx = prefs.getLong("usage_tx_${displayProfileName}_$suffix", 0L)
                    }
                } catch (_: Exception) {
                }
            }
            Triple(true, rx, tx)
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp)
    ) {
        Text(
            text = "KiloProxy",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 16.dp, bottom = 20.dp)
        )

        if (profiles.isEmpty()) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 40.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = "No proxy set up yet",
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                    textAlign = TextAlign.Center
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "Add a proxy on the Profiles tab to get started",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )
            }
        } else {
            val flagCode = remember(profiles, activeProfileName, countryCode, isActuallyConnected) {
                var code: String? = null
                if (isActuallyConnected) {
                    code = countryCode
                }
                if (code == null || code.isBlank()) {
                    code = PreferenceManager.getDefaultSharedPreferences(context)
                        .getString(Constants.PREF_SELECTED_COUNTRY, null)
                }
                if (code == null || code.isBlank()) {
                    code = try {
                        val pm = ProfileManager.getInstance(context)
                        val p = if (activeProfileName != null) pm.getProfile(activeProfileName!!) else pm.getDefault()
                        val username = p.getUsername()
                        ProxyProviders.parseCountry(username, ProxyProviders.detectType(p.getServer(), username))
                    } catch (_: Exception) {
                        null
                    }
                }
                code?.takeIf { it.isNotBlank() }
            }
            val flagEmoji = if (flagCode != null) Utility.countryCodeToFlag(flagCode) else "🌐"

            val statusText = when {
                isConnecting -> "Connecting…"
                isActuallyConnected -> "Connected"
                else -> "Not connected"
            }
            val detailText = serverName.ifBlank { country ?: "" }

            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = flagEmoji,
                    style = MaterialTheme.typography.displayLarge,
                    color = MaterialTheme.colorScheme.onSurface
                )

                Spacer(modifier = Modifier.height(16.dp))
                Box(
                    modifier = Modifier
                        .size(160.dp)
                        .clip(CircleShape)
                        .background(
                            if (isActuallyConnected) MaterialTheme.colorScheme.tertiary
                            else MaterialTheme.colorScheme.primary
                        )
                        .clickable {
                            if (isActuallyConnected) {
                                viewModel.stopVpn(context)
                            } else {
                                val targetProfile = selectedProfile ?: activeProfileName ?: profiles.firstOrNull()
                                if (targetProfile != null) {
                                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                                        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
                                        PackageManager.PERMISSION_GRANTED
                                    ) {
                                        notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                                    }
                                    viewModel.clearError()
                                    val intent = viewModel.prepareAndStartVpn(context, targetProfile)
                                    if (intent != null) {
                                        vpnPermissionLauncher.launch(intent)
                                    }
                                }
                            }
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Image(
                        painter = painterResource(
                            id = if (isActuallyConnected) R.drawable.lucide_square else R.drawable.lucide_play
                        ),
                        contentDescription = null,
                        modifier = Modifier.size(48.dp),
                        colorFilter = ColorFilter.tint(
                            if (isActuallyConnected) MaterialTheme.colorScheme.onTertiary
                            else MaterialTheme.colorScheme.onPrimary
                        )
                    )
                }

                Spacer(modifier = Modifier.height(20.dp))
                Text(
                    text = statusText,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                if (detailText.isNotBlank()) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = detailText,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                errorMessage?.let { message ->
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = message,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            ExposedDropdownMenuBox(
                expanded = menuExpanded,
                onExpandedChange = { if (!isRunning) menuExpanded = !menuExpanded }
            ) {
                OutlinedTextField(
                    value = selectedProfile ?: "",
                    onValueChange = {},
                    readOnly = true,
                    label = { Text("Profile") },
                    trailingIcon = {
                        ExposedDropdownMenuDefaults.TrailingIcon(expanded = menuExpanded)
                    },
                    enabled = !isRunning,
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier
                        .menuAnchor(MenuAnchorType.PrimaryNotEditable, enabled = true)
                        .fillMaxWidth()
                )
                ExposedDropdownMenu(
                    expanded = menuExpanded,
                    onDismissRequest = { menuExpanded = false }
                ) {
                    profiles.forEach { name ->
                        DropdownMenuItem(
                            text = { Text(name) },
                            onClick = {
                                selectedProfile = name
                                menuExpanded = false
                            }
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            DataUsageCard(
                receivedBytes = persistedUsage.second,
                sentBytes = persistedUsage.third,
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(modifier = Modifier.height(16.dp))

            ConnectionStatusCard(
                isConnected = isActuallyConnected,
                ip = currentIp,
                countryCode = countryCode,
                country = country,
                city = city,
                region = region,
                isp = isp,
                org = org,
                asName = asName,
                timezone = timezone,
                modifier = Modifier.fillMaxWidth()
            )
        }

        Spacer(modifier = Modifier.height(24.dp))
    }
}