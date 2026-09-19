package net.typeblog.socks.ui.components

import android.content.Context
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import net.typeblog.socks.util.Countries
import net.typeblog.socks.util.Utility

/**
 * Recents list at the bottom of the Home tab (ProtonVPN-style rows).
 *
 * Each row matches the CountriesScreen row pattern: flag, name, CODE, and a
 * fixed-width right-aligned dial-code slot that swaps for a green dot plus
 * "Connected" label on the active row. Tapping a recent only selects that
 * country (same rewrite as the hero selector); "See all" jumps to the
 * Countries tab.
 */
@Composable
fun RecentsCard(
    context: Context,
    currentCountryCode: String?,
    onRecentClick: (String) -> Unit,
    modifier: Modifier = Modifier,
    onSeeAllClick: () -> Unit = {}
) {
    val recents = remember(currentCountryCode) { Utility.getRecentCountries(context).take(5) }
    if (recents.isEmpty()) return

    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow
        ),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Recents",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    text = "See all ›",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        role = Role.Button,
                        onClick = onSeeAllClick
                    )
                )
            }
            Spacer(modifier = Modifier.height(4.dp))
            recents.forEachIndexed { index, code ->
                val country = Countries.fromCode(code) ?: return@forEachIndexed
                val isActive = code == currentCountryCode
                if (index > 0) {
                    HorizontalDivider(
                        thickness = 0.5.dp,
                        color = MaterialTheme.colorScheme.outlineVariant
                    )
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            role = Role.Button,
                            onClick = { onRecentClick(code) }
                        )
                        .padding(vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier.size(24.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(text = country.flag, fontSize = 20.sp)
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                    // Flexible name block takes ALL leftover width so the dial
                    // slot is pinned to the row end on every row.
                    Row(
                        modifier = Modifier.weight(1f),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = country.name,
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = country.code,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Spacer(modifier = Modifier.width(16.dp))
                    if (isActive) {
                        Box(
                            modifier = Modifier
                                .size(7.dp)
                                .background(MaterialTheme.colorScheme.tertiary, CircleShape)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "Connected",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.tertiary
                        )
                    } else {
                        // Fixed-width end-aligned slot: short dials (+1) and
                        // long ones (+353) share the same right edge.
                        Box(
                            modifier = Modifier.width(52.dp),
                            contentAlignment = Alignment.CenterEnd
                        ) {
                            Text(
                                text = "+${country.phone}",
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1
                            )
                        }
                    }
                }
            }
        }
    }
}
