package net.typeblog.socks.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.draw.clip
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog

/**
 * Theme picker. Opens a dialog listing each theme (Light/Dark) as a full-width
 * radio row in the ProtonVPN Theme sub-settings style: title left, radio right,
 * selected row highlighted. Tapping a row applies it immediately and dismisses.
 */
@Composable
fun ThemePickerDialog(
    current: String,
    onSelect: (String) -> Unit,
    onDismiss: () -> Unit
) {
    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(24.dp),
            color = MaterialTheme.colorScheme.surface,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp)
            ) {
                Text(
                    text = "Theme",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(modifier = Modifier.height(12.dp))

                ThemeRow(
                    label = "Light",
                    selected = current == "light",
                    onClick = { onSelect("light") }
                )
                Spacer(modifier = Modifier.height(6.dp))
                ThemeRow(
                    label = "Dark",
                    selected = current == "dark",
                    onClick = { onSelect("dark") }
                )
                Spacer(modifier = Modifier.height(6.dp))
                ThemeRow(
                    label = "Device theme",
                    selected = current == "system",
                    onClick = { onSelect("system") }
                )
            }
        }
    }
}

@Composable
private fun ThemeRow(
    label: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .selectable(
                selected = selected,
                role = Role.RadioButton,
                onClick = onClick
            ),
        shape = RoundedCornerShape(16.dp),
        color = if (selected) {
            MaterialTheme.colorScheme.surfaceVariant
        } else {
            MaterialTheme.colorScheme.surface
        }
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            ThemePreview(label)
            Text(
                text = label,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f)
            )
            RadioButton(
                selected = selected,
                onClick = null
            )
        }
    }
}

@Composable
private fun ThemePreview(label: String) {
    val dark = label == "Dark"
    val auto = label == "Device theme"
    if (auto) {
        Row(
            modifier = Modifier
                .size(width = 48.dp, height = 68.dp)
                .clip(RoundedCornerShape(8.dp))
        ) {
            Box(Modifier.weight(1f).fillMaxHeight().background(Color(0xFFF4F4F4)))
            Box(Modifier.weight(1f).fillMaxHeight().background(Color(0xFF202024)))
        }
    } else {
        Box(
            modifier = Modifier
                .size(width = 48.dp, height = 68.dp)
                .background(if (dark) Color(0xFF202024) else Color(0xFFF4F4F4), RoundedCornerShape(8.dp))
        )
    }
}
