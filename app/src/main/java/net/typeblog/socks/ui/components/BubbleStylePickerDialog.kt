package net.typeblog.socks.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import net.typeblog.socks.R
import net.typeblog.socks.util.Constants.BUBBLE_STYLE_CLASSIC
import net.typeblog.socks.util.Constants.BUBBLE_STYLE_LOCK

@Composable
fun BubbleStylePickerDialog(
    current: String,
    onSelect: (String) -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(text = "Bubble style") },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                BubbleStyleRow(
                    iconRes = R.drawable.ic_proton_lock_filled,
                    title = "Lock",
                    subtitle = "Transparent 96dp lock, Flag+Digits 2.5s \u2192 Timer, Flag+Code/Flag+Digits every 5s (default)",
                    selected = current == BUBBLE_STYLE_LOCK,
                    onClick = { onSelect(BUBBLE_STYLE_LOCK) }
                )
                Spacer(modifier = Modifier.height(8.dp))
                BubbleStyleRow(
                    iconRes = R.drawable.ic_bubble_play,
                    title = "Classic",
                    subtitle = "60dp orb, Play/Stop, flag pill below",
                    selected = current == BUBBLE_STYLE_CLASSIC,
                    onClick = { onSelect(BUBBLE_STYLE_CLASSIC) }
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(text = "Close") }
        }
    )
}

@Composable
private fun BubbleStyleRow(
    iconRes: Int,
    title: String,
    subtitle: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick),
        shape = RoundedCornerShape(16.dp),
        color = if (selected) MaterialTheme.colorScheme.surfaceVariant else MaterialTheme.colorScheme.surface
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                painter = painterResource(iconRes),
                contentDescription = null,
                modifier = Modifier.size(24.dp),
                tint = MaterialTheme.colorScheme.onSurface
            )
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = if (title == "Lock") "Lock (new)" else title,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 2.dp)
                )
            }
            RadioButton(selected = selected, onClick = null)
        }
    }
}
