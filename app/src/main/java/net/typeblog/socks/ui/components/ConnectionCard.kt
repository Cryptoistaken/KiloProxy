package net.typeblog.socks.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import net.typeblog.socks.ui.theme.GeistMonoFonts
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import net.typeblog.socks.util.Countries

/**
 * Hero connection control for the Connect tab.
 *
 * ProtonVPN-style connect card: a country row (flag chip + name) followed by a
 * full-width rounded Connect/Disconnect button, with the connection timer line
 * rendered directly under the button. Shows a "Connecting…" state while the VPN
 * is starting and until the proxy IP info has been populated.
 */
@Composable
fun ConnectionCard(
    isConnected: Boolean,
    isConnecting: Boolean,
    serverName: String,
    connectedSince: Long = 0L,
    onStartClick: () -> Unit,
    onStopClick: () -> Unit,
    modifier: Modifier = Modifier,
    countryCode: String? = null
) {
    val country = countryCode?.let { Countries.fromCode(it) }
    val countryName = country?.name

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
                .padding(horizontal = 24.dp, vertical = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(0.dp)
        ) {
            // Status label — centered, prominent.
            Text(
                text = if (isConnected) "Protected" else "Unprotected",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.Center
            )

            // Country row — only when a country can be parsed from the profile.
            if (countryName != null) {
                Spacer(modifier = Modifier.height(16.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Surface(
                        shape = RoundedCornerShape(4.dp),
                        color = MaterialTheme.colorScheme.surface,
                        modifier = Modifier.size(width = 30.dp, height = 20.dp)
                    ) {
                        Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = country.flag,
                                fontSize = 18.sp,
                                textAlign = TextAlign.Center
                            )
                        }
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                    Text(
                        text = countryName,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        // No weight(1f): a weight fills the whole row and defeats
                        // Arrangement.Center — cap the width instead so the
                        // flag + name group truly centers in the card.
                        modifier = Modifier.widthIn(max = 200.dp)
                    )
                }
            }

            // Host name — ALWAYS shown so the layout never shifts on connect.
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = serverName,
                fontSize = 12.sp,
                fontFamily = GeistMonoFonts.Family,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.fillMaxWidth(),
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(24.dp))

            val buttonColor = when {
                isConnecting -> MaterialTheme.colorScheme.primary.copy(alpha = 0.7f)
                isConnected -> MaterialTheme.colorScheme.error
                else -> MaterialTheme.colorScheme.primary
            }
            val buttonContentColor =
                if (isConnected) MaterialTheme.colorScheme.onError else MaterialTheme.colorScheme.onPrimary

            Surface(
                onClick = { if (isConnected) onStopClick() else onStartClick() },
                enabled = !isConnecting,
                shape = RoundedCornerShape(12.dp),
                color = buttonColor,
                contentColor = buttonContentColor,
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (isConnecting) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(22.dp),
                            color = buttonContentColor,
                            strokeWidth = 2.5.dp
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                    }
                    Text(
                        text = when {
                            isConnecting -> "Connecting…"
                            isConnected -> "Disconnect"
                            else -> "Connect"
                        },
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = buttonContentColor
                    )
                }
            }

            // Timer/status line — always rendered (same height) so nothing shifts.
            Spacer(modifier = Modifier.height(12.dp))
            val elapsed by produceState(initialValue = 0L, isConnected, connectedSince) {
                while (true) {
                    value = System.currentTimeMillis() - connectedSince
                    delay(1000)
                }
            }
            val totalSeconds = elapsed / 1000
            val elapsedText =
                "${(totalSeconds / 3600).toString().padStart(2, '0')}:" +
                    "${((totalSeconds % 3600) / 60).toString().padStart(2, '0')}:" +
                    "${(totalSeconds % 60).toString().padStart(2, '0')}"
            Text(
                text = when {
                    isConnecting -> "Establishing secure connection…"
                    isConnected && connectedSince > 0 -> "Connected $elapsedText"
                    isConnected -> "Connected"
                    else -> "Tap to connect"
                },
                fontSize = 12.sp,
                fontFamily = GeistMonoFonts.Family,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                modifier = Modifier.fillMaxWidth(),
                textAlign = TextAlign.Center
            )
        }
    }
}
