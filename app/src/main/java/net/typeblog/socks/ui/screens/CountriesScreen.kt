package net.typeblog.socks.ui.screens

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import net.typeblog.socks.R
import net.typeblog.socks.ui.viewmodel.VpnViewModel
import net.typeblog.socks.util.Countries
import net.typeblog.socks.util.ProfileManager
import net.typeblog.socks.util.ProxyProviders
import net.typeblog.socks.util.Utility

/**
 * Countries tab — a ProtonVPN-style country list with search, a "Recently Used"
 * section (Utility recent-countries file, same store the bubble menu uses) and
 * tap-to-connect. Tapping a country rewrites the default profile's username to
 * the provider country zone (same logic as the floating bubble's country
 * switch) and starts the VPN through [VpnViewModel].
 */
@Composable
fun CountriesScreen(viewModel: VpnViewModel, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val isRunning by viewModel.isRunning.collectAsState()
    val activeProfileName by viewModel.activeProfileName.collectAsState()
    val profiles by viewModel.profiles.collectAsState()

    var query by remember { mutableStateOf("") }
    var recentCountries by remember { mutableStateOf(Utility.getRecentCountries(context)) }

    // The country switch always targets the default profile, matching the
    // floating bubble's country menu.
    val defaultProfileName = remember(profiles) {
        try {
            val pm = ProfileManager.getInstance(context)
            pm.getDefault().getName()
        } catch (_: Exception) {
            null
        }
    }

    // Connected-country marking, same shape as ProxiesScreen's per-card
    // `isRunning && activeProfileName == profileName` check.
    val connectedCountryCode = remember(defaultProfileName, activeProfileName, isRunning) {
        if (!isRunning || activeProfileName != defaultProfileName || defaultProfileName == null) {
            null
        } else {
            try {
                val pm = ProfileManager.getInstance(context)
                val profile = pm.getProfile(defaultProfileName) ?: return@remember null
                val type = ProxyProviders.detectType(profile.getServer(), profile.getUsername())
                ProxyProviders.parseCountry(profile.getUsername(), type)?.uppercase()
            } catch (_: Exception) {
                null
            }
        }
    }

    // VPN-permission flow, identical to StatusScreen's ConnectionCard handler.
    val vpnPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == android.app.Activity.RESULT_OK) {
            viewModel.onVpnPermissionResult(context)
        } else {
            viewModel.cancelConnect()
        }
    }

    fun connectToProfile(name: String) {
        viewModel.clearError()
        val intent = viewModel.prepareAndStartVpn(context, name)
        if (intent != null) {
            vpnPermissionLauncher.launch(intent)
        }
    }

    // Replicates FloatingControlService.onBubbleCountrySelected: rewrite the
    // default profile's username to the provider country zone, store the recent,
    // then (re)start the VPN so the new zone takes effect.
    fun onCountryTap(code: String) {
        try {
            val pm = ProfileManager.getInstance(context)
            val profile = pm.getDefault()
            val username = profile.getUsername()
            val type = ProxyProviders.detectType(profile.getServer(), username)
            val newUsername: String? = when (type) {
                ProxyProviders.TYPE_OWL -> {
                    // Preserve sticky suffix if present; rebuild only the country zone.
                    val match = Regex("^(.+?)_custom_zone_[a-zA-Z]{2}(_st__city_sid_\\d+_time_\\d+)?$")
                        .find(username)
                    val base = match?.groupValues?.get(1) ?: return
                    "${base}_custom_zone_${code.lowercase()}${match.groupValues[2]}"
                }
                ProxyProviders.TYPE_RAPID, ProxyProviders.TYPE_CLIP -> {
                    val base = ProxyProviders.extractBase(username, type) ?: return
                    ProxyProviders.buildUsername(base, type, code) ?: return
                }
                ProxyProviders.TYPE_GENERIC -> {
                    val parts = ProxyProviders.genericParts(username) ?: return
                    ProxyProviders.buildUsername(
                        parts.base, type, code,
                        separator = parts.separator, upper = parts.upper
                    ) ?: return
                }
                else -> {
                    Toast.makeText(
                        context,
                        "Country switching is not available for this profile",
                        Toast.LENGTH_SHORT
                    ).show()
                    return
                }
            }
            profile.setUsername(newUsername)
            Utility.addRecentCountry(context, code)
            recentCountries = Utility.getRecentCountries(context)
            val target = defaultProfileName ?: return
            if (isRunning) {
                // Running tunnel keeps its old zone — restart to apply the switch.
                scope.launch {
                    viewModel.stopVpn(context)
                    delay(500)
                    connectToProfile(target)
                }
            } else {
                connectToProfile(target)
            }
        } catch (_: Exception) {
        }
    }

    val filteredCountries = remember(query) {
        if (query.isEmpty()) {
            Countries.ALL
        } else {
            val q = query.lowercase()
            val digits = query.filter { it.isDigit() }
            Countries.ALL.filter { country ->
                country.name.lowercase().contains(q) ||
                    country.code.lowercase().contains(q) ||
                    (digits.isNotEmpty() && (country.phone.startsWith(digits) || digits.startsWith(country.phone)))
            }
        }
    }
    val recentList = remember(recentCountries) {
        recentCountries.mapNotNull { code -> Countries.fromCode(code) }
    }
    val showRecents = query.isEmpty() && recentList.isNotEmpty()
    val allCountries = if (showRecents) {
        filteredCountries.filter { it.code !in recentCountries }
    } else {
        filteredCountries
    }

    Column(modifier = modifier.fillMaxSize()) {
        Text(
            text = "Countries",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(start = 16.dp, top = 16.dp, end = 16.dp, bottom = 4.dp)
        )

        if (profiles.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = "No proxy set up yet",
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface,
                        textAlign = TextAlign.Center
                    )
                    Spacer(modifier = Modifier.size(4.dp))
                    Text(
                        text = "Add a proxy on the Profiles tab to connect",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center
                    )
                }
            }
        } else {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                placeholder = { Text("Search countries...") },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 16.dp, end = 16.dp, bottom = 8.dp),
                shape = RoundedCornerShape(8.dp),
                singleLine = true
            )

            LazyColumn(modifier = Modifier.fillMaxSize()) {
                if (showRecents) {
                    item(key = "recent_header") {
                        Text(
                            text = "Recently Used",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(start = 16.dp, top = 8.dp, end = 16.dp, bottom = 4.dp)
                        )
                    }
                    items(recentList, key = { it.code }) { country ->
                        CountryRow(
                            country = country,
                            isConnected = connectedCountryCode == country.code,
                            onClick = { onCountryTap(country.code) }
                        )
                    }
                    item(key = "all_header") {
                        Text(
                            text = "All Countries",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(start = 16.dp, top = 8.dp, end = 16.dp, bottom = 4.dp)
                        )
                    }
                }
                if (allCountries.isEmpty()) {
                    item(key = "no_results") {
                        Text(
                            text = "No countries found",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 24.dp)
                        )
                    }
                } else {
                    items(allCountries, key = { it.code }) { country ->
                        CountryRow(
                            country = country,
                            isConnected = connectedCountryCode == country.code,
                            onClick = { onCountryTap(country.code) }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun CountryRow(
    country: Countries.Country,
    isConnected: Boolean,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Flag emoji in a fixed 24dp slot, matching the app's existing
        // country rows (ProxiesScreen country picker / bubble menu use the
        // same emoji-flag approach — no per-country drawables exist).
        Box(
            modifier = Modifier.size(24.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(text = country.flag, fontSize = 20.sp)
        }
        Spacer(modifier = Modifier.width(12.dp))
        Text(
            text = country.name,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        Spacer(modifier = Modifier.width(12.dp))
        if (isConnected) {
            Icon(
                painter = painterResource(R.drawable.lucide_check),
                contentDescription = "Connected",
                tint = MaterialTheme.colorScheme.tertiary,
                modifier = Modifier.size(18.dp)
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = "Connected",
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.tertiary
            )
        } else {
            Text(
                text = country.code,
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
    HorizontalDivider(
        thickness = 0.5.dp,
        color = MaterialTheme.colorScheme.outlineVariant
    )
}