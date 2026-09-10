package net.typeblog.socks.ui.screens

import android.content.SharedPreferences
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
import androidx.compose.runtime.DisposableEffect
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
import androidx.preference.PreferenceManager
import net.typeblog.socks.R
import net.typeblog.socks.ui.components.ProtonSwitch
import net.typeblog.socks.util.Constants.PREF_VPN_ACCELERATOR

/**
 * VPN Accelerator, experimental connect-time page for our SOCKS5 client.
 *
 * Same name as Proton on purpose, different meaning: no server fleet here,
 * so this cannot boost throughput. When wired up later it only speeds up
 * repeat connects to the same proxy (DNS cache + optimistic verified IP).
 * For now this page is a UI toggle only and changes no engine behavior.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VpnAcceleratorScreen(
    onNavigateBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val prefs = PreferenceManager.getDefaultSharedPreferences(context)

    var acceleratorEnabled by remember {
        mutableStateOf(prefs.getBoolean(PREF_VPN_ACCELERATOR, false))
    }
    DisposableEffect(context) {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == PREF_VPN_ACCELERATOR) {
                acceleratorEnabled = prefs.getBoolean(PREF_VPN_ACCELERATOR, false)
            }
        }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        onDispose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }

    Scaffold(
        modifier = modifier,
        contentWindowInsets = WindowInsets(0),
        topBar = {
            TopAppBar(
                title = { Text("VPN Accelerator") },
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
                    painter = painterResource(R.drawable.lucide_arrows_right_left),
                    contentDescription = null,
                    modifier = Modifier.size(64.dp),
                    tint = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = "VPN Accelerator",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(top = 16.dp)
                )
                Text(
                    text = "Experimental. Speeds up repeat connects to the same proxy. When off, connects work exactly as before.",
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
                        text = "VPN Accelerator",
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

            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}
