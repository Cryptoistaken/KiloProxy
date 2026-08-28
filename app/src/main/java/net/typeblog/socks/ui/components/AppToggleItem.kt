package net.typeblog.socks.ui.components

import android.graphics.Bitmap
import android.graphics.drawable.Drawable
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.painter.BitmapPainter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.typeblog.socks.ui.theme.GeistMonoFonts
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * A per-app toggle row for split tunneling.
 *
 * Shows a colored circle with the first letter of the app name,
 * the app name and package name, and a Material3 Switch on the right.
 * Matches the `.app-toggle-item` pattern from the redesign mockup.
 */
private val AppColors = listOf(
    Color(0xFF4285F4), // Google Blue
    Color(0xFFFF0000), // YouTube Red
    Color(0xFF25D366), // WhatsApp Green
    Color(0xFFE4405F), // Instagram Pink
    Color(0xFF00BCD4), // Maps Cyan
    Color(0xFF34A853), // Google Green
    Color(0xFFFBBC04), // Google Yellow
    Color(0xFF1DA1F2), // Twitter Blue
    Color(0xFF0088CC), // Telegram Blue
    Color(0xFF5865F2), // Discord Blue
    Color(0xFFFF5722), // Deep Orange
    Color(0xFF9C27B0), // Purple
    Color(0xFF607D8B), // Blue Grey
    Color(0xFF795548), // Brown
    Color(0xFFE91E63)  // Pink
)

@Composable
fun AppToggleItem(
    appName: String,
    packageName: String,
    isAllowed: Boolean,
    onToggle: (Boolean) -> Unit,
    icon: Drawable? = null,
    modifier: Modifier = Modifier
) {
    val colorIndex = kotlin.math.abs(packageName.hashCode()) % AppColors.size
    val appColor = AppColors[colorIndex]
    val firstLetter = appName.firstOrNull()?.uppercase() ?: "?"

    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 52.dp)
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Colored circle with first letter of app name OR actual app icon
        Box(
            modifier = Modifier
                .size(32.dp)
                .clip(CircleShape)
                .background(if (icon != null) Color.Transparent else appColor),
            contentAlignment = Alignment.Center
        ) {
            if (icon != null) {
                val bitmap by produceState<Bitmap?>(initialValue = null, icon) {
                    value = withContext(Dispatchers.IO) {
                        val bmp = Bitmap.createBitmap(
                            icon.intrinsicWidth.coerceAtLeast(1),
                            icon.intrinsicHeight.coerceAtLeast(1),
                            Bitmap.Config.ARGB_8888
                        )
                        val canvas = android.graphics.Canvas(bmp)
                        icon.setBounds(0, 0, canvas.width, canvas.height)
                        icon.draw(canvas)
                        bmp
                    }
                }
                if (bitmap != null) {
                    Icon(
                        painter = BitmapPainter(bitmap!!.asImageBitmap()),
                        contentDescription = appName,
                        modifier = Modifier.size(24.dp),
                        tint = Color.Unspecified
                    )
                }
            } else {
                Text(
                    text = firstLetter,
                    color = Color.White,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }

        Spacer(modifier = Modifier.width(10.dp))

        // App name + package name
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = appName,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = packageName,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = GeistMonoFonts.Family,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        // Material3 Switch
        Switch(
            checked = isAllowed,
            onCheckedChange = onToggle
        )
    }
}
