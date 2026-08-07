package net.typeblog.socks.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.preference.PreferenceManager
import kotlinx.coroutines.launch
import net.typeblog.socks.ui.theme.*
import net.typeblog.socks.util.ProxyProviders
import net.typeblog.socks.util.SocksTester
import net.typeblog.socks.util.Utility

@Composable
fun ProxyCard(
    profileName: String,
    server: String,
    port: Int,
    username: String,
    password: String,
    isConnected: Boolean,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
    liveUsageRx: Long = 0L,
    liveUsageTx: Long = 0L
) {
    var testing by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val providerType = remember(username, server) { ProxyProviders.detectType(server, username) }
    val countryCode = remember(username, providerType) {
        ProxyProviders.parseCountry(username, providerType)?.uppercase()
    }
    val providerLabel = remember(providerType) { ProxyProviders.label(providerType) }

    var usageRx by remember { mutableStateOf(0L) }
    var usageTx by remember { mutableStateOf(0L) }

    val usageSuffix = remember(password) { Integer.toHexString(password.hashCode()) }

    fun refreshPersistedUsage() {
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        usageRx = prefs.getLong("usage_rx_${profileName}_$usageSuffix", 0L)
        usageTx = prefs.getLong("usage_tx_${profileName}_$usageSuffix", 0L)
    }

    LaunchedEffect(profileName, password) {
        refreshPersistedUsage()
    }

    // Single total shown on the card. While connected it is the LIVE combined
    // figure from the VPN service (AIDL-backed flows updated every 200ms).
    // When disconnected, the card prefers the last session totals retained by
    // the view model (they survive the disconnect and are tied to the profile
    // that just ran), falling back to the persisted prefs figure only when
    // there is nothing retained. Persisted prefs are written by the :vpn
    // process, so the UI process may hold a stale in-memory copy after a
    // disconnect — the retained totals are the reliable source here.
    val liveTotal = liveUsageRx + liveUsageTx
    val displayUsed = if (isConnected) {
        liveTotal
    } else {
        if (liveTotal > 0L) liveTotal else usageRx + usageTx
    }

    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow
        ),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // Status dot
                Box(
                    modifier = Modifier
                        .size(10.dp)
                        .clip(CircleShape)
                        .then(
                            if (isConnected) {
                                Modifier.background(MaterialTheme.colorScheme.tertiary)
                            } else {
                                Modifier.background(MaterialTheme.colorScheme.outline)
                            }
                        )
                )

                // Name and address
                Column(
                    modifier = Modifier.weight(1f)
                ) {
                    Text(
                        text = profileName,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = "$server:$port",
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = GeistMonoFonts.Family,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                // Used total — its own right-aligned stat, never squeezed inline.
                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        text = Utility.formatBytes(displayUsed),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1
                    )
                    Text(
                        text = "Used",
                        fontSize = 10.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            // Meta chips on their own row so they never race the name/usage.
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                if (countryCode != null) {
                    Surface(
                        shape = RoundedCornerShape(9999.dp),
                        color = MaterialTheme.colorScheme.tertiaryContainer
                    ) {
                        Text(
                            text = "${Utility.countryCodeToFlag(countryCode)} $countryCode",
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                            fontSize = 10.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onTertiaryContainer
                        )
                    }
                }
                Surface(
                    shape = RoundedCornerShape(9999.dp),
                    color = MaterialTheme.colorScheme.primaryContainer
                ) {
                    Text(
                        text = providerLabel,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                        fontSize = 10.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }
                Surface(
                    shape = RoundedCornerShape(9999.dp),
                    color = MaterialTheme.colorScheme.primaryContainer
                ) {
                    Text(
                        text = "SOCKS5",
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                        fontSize = 10.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(
                    onClick = onEdit,
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(8.dp),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
                ) {
                    Text("Edit", fontSize = 13.sp)
                }

                OutlinedButton(
                    onClick = {
                        testing = true
                        scope.launch {
                            val result = SocksTester.testProxy(server, port, username, password)
                            android.widget.Toast.makeText(
                                context,
                                result,
                                android.widget.Toast.LENGTH_LONG
                            ).show()
                            testing = false
                        }
                    },
                    enabled = !testing,
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(8.dp),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
                ) {
                    Text(
                        text = if (testing) "Testing…" else "Test",
                        fontSize = 13.sp
                    )
                }

                OutlinedButton(
                    onClick = onDelete,
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(8.dp),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.error),
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = MaterialTheme.colorScheme.error
                    )
                ) {
                    Text("Delete", fontSize = 13.sp)
                }
            }
        }
    }
}
