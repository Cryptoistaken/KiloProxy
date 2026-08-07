package net.typeblog.socks.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import net.typeblog.socks.R

/**
 * Reusable settings row matching the KiloProxy design mockup.
 *
 * Layout: icon (32dp box, brand-tinted bg, 9dp rounded) | label + desc (column) | value | trailing
 * Height: 48dp minimum with 14dp horizontal padding. Clickable rows without their
 * own trailing content get a subtle chevron by default so tappability reads at a glance.
 */
@Composable
fun SettingsItem(
    icon: Painter,
    label: String,
    description: String? = null,
    value: String? = null,
    iconTint: Color? = null,
    showChevron: Boolean = true,
    trailing: @Composable RowScope.() -> Unit = {},
    onClick: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .then(
                if (onClick != null) Modifier.clickable(onClick = onClick)
                else Modifier
            ),
        color = MaterialTheme.colorScheme.surfaceVariant,
        tonalElevation = 0.dp
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .padding(horizontal = 14.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Icon box — 32dp square, brand-tinted bg, 9dp rounded
            Box(
                modifier = Modifier
                    .size(32.dp)
                    .clip(RoundedCornerShape(9.dp))
                    .background(
                        (iconTint ?: MaterialTheme.colorScheme.primary).copy(alpha = 0.12f)
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    painter = icon,
                    contentDescription = null,
                    modifier = Modifier.size(17.dp),
                    tint = iconTint ?: MaterialTheme.colorScheme.primary
                )
            }

            Spacer(modifier = Modifier.width(10.dp))

            // Label + optional description
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
                if (description != null) {
                    Text(
                        text = description,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 1.dp)
                    )
                }
            }

            // Value text on the right
            if (value != null) {
                Text(
                    text = value,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(end = 8.dp)
                )
            }

            // Trailing content (Switch, chevron, button, etc.)
            trailing()

            if (onClick != null && showChevron) {
                Icon(
                    painter = painterResource(R.drawable.lucide_chevron_right),
                    contentDescription = null,
                    modifier = Modifier
                        .padding(start = 2.dp)
                        .size(18.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                )
            }
        }
    }
}
