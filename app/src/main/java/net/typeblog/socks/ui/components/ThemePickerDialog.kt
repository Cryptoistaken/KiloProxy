package net.typeblog.socks.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import net.typeblog.socks.R

/**
 * Advanced theme picker. Opens a dialog where each theme (Light/Dark) is shown as
 * a tappable card with a live mini preview rendered in that theme's colors.
 * Tapping a card applies it immediately and dismisses the dialog.
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
                    text = "Choose theme",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = "Pick how KiloProxy looks",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(16.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    ThemeOptionCard(
                        label = "Light",
                        isLight = true,
                        selected = current != "dark",
                        modifier = Modifier.weight(1f),
                        onClick = { onSelect("light") }
                    )
                    ThemeOptionCard(
                        label = "Dark",
                        isLight = false,
                        selected = current == "dark",
                        modifier = Modifier.weight(1f),
                        onClick = { onSelect("dark") }
                    )
                }
            }
        }
    }
}

@Composable
private fun ThemeOptionCard(
    label: String,
    isLight: Boolean,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    val surface = if (isLight) Color(0xFFFFFFFF) else Color(0xFF0A0A0A)
    val onSurface = if (isLight) Color(0xFF000000) else Color(0xFFFFFFFF)
    val card = if (isLight) Color(0xFFF5F5F5) else Color(0xFF1A1A1A)
    val primary = if (isLight) Color(0xFF000000) else Color(0xFFFFFFFF)
    val onPrimary = if (isLight) Color(0xFFFFFFFF) else Color(0xFF000000)
    val success = if (isLight) Color(0xFF16A34A) else Color(0xFF22C55E)
    val error = if (isLight) Color(0xFFDC2626) else Color(0xFFEF4444)

    val borderColor = if (selected) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.outline.copy(alpha = 0.7f)
    }

    Column(
        modifier = modifier
            .clip(RoundedCornerShape(16.dp))
            .border(
                width = if (selected) 2.dp else 1.dp,
                color = borderColor,
                shape = RoundedCornerShape(16.dp)
            )
            .clickable(onClick = onClick)
            .padding(10.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Mini live preview of the theme
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .height(96.dp),
            shape = RoundedCornerShape(12.dp),
            color = surface
        ) {
            Column(modifier = Modifier.padding(10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(10.dp)
                            .clip(CircleShape)
                            .background(success)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .height(6.dp)
                            .clip(CircleShape)
                            .background(onSurface.copy(alpha = 0.85f))
                    )
                    Box(
                        modifier = Modifier
                            .size(10.dp)
                            .clip(CircleShape)
                            .background(error)
                    )
                }
                Spacer(modifier = Modifier.height(8.dp))
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(card)
                )
                Spacer(modifier = Modifier.height(8.dp))
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(20.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(primary),
                    contentAlignment = Alignment.Center
                ) {
                    Box(
                        modifier = Modifier
                            .width(26.dp)
                            .height(4.dp)
                            .clip(CircleShape)
                            .background(onPrimary)
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(modifier = Modifier.width(6.dp))
            if (selected) {
                Icon(
                    painter = painterResource(R.drawable.lucide_check),
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.primary
                )
            }
        }
    }
}
