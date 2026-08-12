package net.typeblog.socks.ui.components

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
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import net.typeblog.socks.R

/**
 * Reusable settings row styled after the ProtonVPN settings screen.
 *
 * Layout: 24dp icon (32dp clear slot, no background box) | label + subtitle (column) | value | trailing | chevron
 * Rows are transparent, full-width, with 16dp padding and a 56dp minimum height.
 * Rows with a subtitle top-align their content; icon/leading content stays at the top.
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
    Row(
        modifier = modifier
            .fillMaxWidth()
            .then(
                if (onClick != null) Modifier.clickable(onClick = onClick)
                else Modifier
            )
            .heightIn(min = 56.dp)
            .padding(horizontal = 16.dp, vertical = 16.dp),
        verticalAlignment = if (description != null) Alignment.Top else Alignment.CenterVertically
    ) {
        // Icon slot — 32dp clear area holding a 24dp single-color icon (no box, no background)
        Box(
            modifier = Modifier.size(32.dp),
            contentAlignment = Alignment.Center
        ) {
Icon(
                    painter = icon,
                    contentDescription = null,
                    modifier = Modifier.size(24.dp),
                    tint = iconTint ?: MaterialTheme.colorScheme.onSurface
                )
        }

        Spacer(modifier = Modifier.width(16.dp))

        // Label + optional subtitle
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface
            )
            if (description != null) {
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 6.dp)
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
                    .padding(start = 8.dp, end = 8.dp)
                    .size(24.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
            )
        }
    }
}