package net.typeblog.socks.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import net.typeblog.socks.ui.theme.GeistMonoFonts
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Connection detail table. Values are technical facts (IP, ASN, timezone)
 * so they're set in monospace to read as data rather than prose.
 */
@Composable
fun ConnectionStatusCard(
    isConnected: Boolean,
    ip: String?,
    countryCode: String?,
    country: String?,
    city: String?,
    region: String?,
    isp: String?,
    org: String?,
    asName: String?,
    timezone: String?,
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
                .padding(horizontal = 16.dp, vertical = 16.dp)
        ) {
            Text(
                text = "Connection details",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(modifier = Modifier.height(12.dp))

            if (!isConnected) {
                Text(
                    text = "Not connected. Tap the connect button to see your proxy details.",
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                return@Column
            }

            val rows = buildList {
                add("IP Address" to ip.orEmpty())
                if (!countryCode.isNullOrEmpty()) {
                    add(
                        "Country" to if (!country.isNullOrEmpty()) "$country ($countryCode)" else countryCode
                    )
                }
                if (!region.isNullOrEmpty()) add("Region" to region)
                if (!city.isNullOrEmpty()) add("City" to city)
                if (!timezone.isNullOrEmpty()) add("Timezone" to timezone)
                if (!isp.isNullOrEmpty()) add("ISP" to isp)
                if (!org.isNullOrEmpty()) add("Org" to org)
                if (!asName.isNullOrEmpty()) add("AS" to asName)
            }

            rows.forEachIndexed { index, (label, value) ->
                StatusRow(label = label, value = value)
                if (index != rows.lastIndex) {
                    HorizontalDivider(
                        modifier = Modifier.padding(top = 6.dp),
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                    )
                }
            }
        }
    }
}

@Composable
private fun StatusRow(
    label: String,
    value: String
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.Top
    ) {
        Text(
            text = label,
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(90.dp)
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = value,
            fontSize = 13.sp,
            fontFamily = GeistMonoFonts.Family,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
            textAlign = TextAlign.Start
        )
    }
}
