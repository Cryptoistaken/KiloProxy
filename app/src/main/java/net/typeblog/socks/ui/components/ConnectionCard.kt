package net.typeblog.socks.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
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
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import net.typeblog.socks.R

/**
 * Hero connection control for the Connect tab.
 *
 * A large pill-shaped Start/Stop button carries the connection state,
 * matching the Cloudflare Turnstile CTA style: flat, fully rounded,
 * orange when idle, red when active. Shows a "Connecting…" state while
 * the VPN is starting and until the proxy IP info has been populated.
 */
@Composable
fun ConnectionCard(
    isConnected: Boolean,
    isConnecting: Boolean,
    serverName: String,
    connectedSince: Long = 0L,
    onStartClick: () -> Unit,
    onStopClick: () -> Unit,
    modifier: Modifier = Modifier
) {
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
                .padding(horizontal = 20.dp, vertical = 28.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = if (isConnected) "Protected" else "Unprotected",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )

            if (serverName.isNotEmpty()) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = serverName,
                    fontSize = 13.sp,
                    fontFamily = GeistMonoFonts.Family,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.fillMaxWidth(),
                    textAlign = TextAlign.Center
                )
            }

            if (isConnected && connectedSince > 0) {
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
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "Connected $elapsedText",
                    fontSize = 13.sp,
                    fontFamily = GeistMonoFonts.Family,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.fillMaxWidth(),
                    textAlign = TextAlign.Center
                )
            }

            Spacer(modifier = Modifier.height(20.dp))

            Surface(
                onClick = { if (isConnected) onStopClick() else onStartClick() },
                enabled = !isConnecting,
                shape = RoundedCornerShape(100.dp),
                color = when {
                    isConnecting -> MaterialTheme.colorScheme.primary.copy(alpha = 0.7f)
                    isConnected -> MaterialTheme.colorScheme.error
                    else -> MaterialTheme.colorScheme.primary
                },
                modifier = Modifier.fillMaxWidth()
            ) {

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 16.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (isConnecting) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(22.dp),
                            color = MaterialTheme.colorScheme.onPrimary,
                            strokeWidth = 2.5.dp
                        )
                    } else {
                        Icon(
                            painter = painterResource(
                                if (isConnected) R.drawable.lucide_square else R.drawable.lucide_play
                            ),
                            contentDescription = if (isConnected) "Stop" else "Start",
                            tint = MaterialTheme.colorScheme.onPrimary,
                            modifier = Modifier.size(22.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = when {
                            isConnecting -> "Connecting…"
                            isConnected -> "Disconnect"
                            else -> "Connect"
                        },
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onPrimary
                    )
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            Text(
                text = when {
                    isConnecting -> "Establishing secure connection…"
                    isConnected -> "Tap to disconnect"
                    else -> "Tap to connect"
                },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
