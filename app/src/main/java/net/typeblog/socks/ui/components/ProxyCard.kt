package net.typeblog.socks.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.preference.PreferenceManager
import net.typeblog.socks.R
import net.typeblog.socks.ui.theme.GeistMonoFonts
import net.typeblog.socks.util.ProxyProviders
import net.typeblog.socks.util.Utility

// Single total shared by the card and the detail sheet. While connected it
// is the LIVE combined figure from the VPN service; when disconnected it
// prefers the last session totals retained by the view model (they survive
// the disconnect), falling back to the persisted prefs figure.
@Composable
internal fun profileDisplayUsage(
    profileName: String,
    password: String,
    isConnected: Boolean,
    liveUsageRx: Long,
    liveUsageTx: Long
): Long {
    val context = LocalContext.current
    var usageRx by remember { mutableStateOf(0L) }
    var usageTx by remember { mutableStateOf(0L) }
    val usageSuffix = remember(profileName) { Utility.usageSuffix(profileName) }

    LaunchedEffect(profileName, password) {
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        usageRx = prefs.getLong("usage_rx_${profileName}_$usageSuffix", 0L)
        usageTx = prefs.getLong("usage_tx_${profileName}_$usageSuffix", 0L)
    }

    val liveTotal = liveUsageRx + liveUsageTx
    return if (isConnected) {
        liveTotal
    } else {
        if (liveTotal > 0L) liveTotal else usageRx + usageTx
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun ProxyCard(
    profileName: String,
    server: String,
    username: String,
    password: String,
    isConnected: Boolean,
    onSelect: () -> Unit,
    modifier: Modifier = Modifier,
    liveUsageRx: Long = 0L,
    liveUsageTx: Long = 0L,
    checked: Boolean = false,
    onLongPress: (() -> Unit)? = null
) {
    val providerType = remember(username, server) { ProxyProviders.detectType(server, username) }
    val countryCode = remember(username, providerType) {
        ProxyProviders.parseCountry(username, providerType)?.uppercase()
    }
    val displayUsed = profileDisplayUsage(profileName, password, isConnected, liveUsageRx, liveUsageTx)

    Card(
        modifier = modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onSelect, onLongClick = onLongPress),
        shape = RoundedCornerShape(0.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (checked) MaterialTheme.colorScheme.primaryContainer
            else MaterialTheme.colorScheme.surfaceContainerLow
        ),
        border = BorderStroke(
            1.dp,
            if (checked) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.outline
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Icon slot: flag only, bare glyph when no country.
            Box(
                modifier = Modifier.width(38.dp),
                contentAlignment = Alignment.Center
            ) {
                if (countryCode != null) {
                    Text(
                        text = Utility.countryCodeToFlag(countryCode),
                        fontSize = 26.sp,
                        maxLines = 1
                    )
                } else {
                    Icon(
                        painter = painterResource(R.drawable.lucide_server),
                        contentDescription = null,
                        modifier = Modifier.size(26.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            // Name + status, host without port.
            Column(modifier = Modifier.weight(1f)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = profileName,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                    // App signal_dot: plain 7dp oval, hidden when offline.
                    // Matches the bubble popup's connected dot (row_dot).
                    if (isConnected) {
                        Box(
                            modifier = Modifier
                                .size(7.dp)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.tertiary)
                        )
                    }
                }
                Text(
                    text = server,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = GeistMonoFonts.Family,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            // Used total only.
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
    }
}
