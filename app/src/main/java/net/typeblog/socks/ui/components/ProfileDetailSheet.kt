package net.typeblog.socks.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import net.typeblog.socks.R
import net.typeblog.socks.ui.theme.GeistMonoFonts
import net.typeblog.socks.util.ProxyProviders
import net.typeblog.socks.util.SocksTester
import net.typeblog.socks.util.Utility

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfileDetailSheet(
    profileName: String,
    server: String,
    port: Int,
    username: String,
    password: String,
    isConnected: Boolean,
    onEdit: () -> Unit,
    onDuplicate: () -> Unit,
    onSelectMode: () -> Unit,
    onDelete: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    liveUsageRx: Long = 0L,
    liveUsageTx: Long = 0L
) {
    val providerType = remember(username, server) { ProxyProviders.detectType(server, username) }
    val countryCode = remember(username, providerType) {
        ProxyProviders.parseCountry(username, providerType)?.uppercase()
    }
    val providerLabel = remember(providerType) { ProxyProviders.label(providerType) }
    val sub = remember(countryCode, providerType) {
        if (countryCode != null) {
            net.typeblog.socks.util.Countries.fromCode(countryCode)?.name ?: countryCode
        } else {
            providerLabel
        }
    }
    val displayUsed = profileDisplayUsage(profileName, password, isConnected, liveUsageRx, liveUsageTx)
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        modifier = modifier,
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 18.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Head: icon + name + sub.
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Box(
                    modifier = Modifier.width(38.dp),
                    contentAlignment = Alignment.Center
                ) {
                    if (countryCode != null) {
                        Text(
                            text = Utility.countryCodeToFlag(countryCode),
                            fontSize = 26.sp,
                            maxLines = 1
                        )
                    } else {
                        Icon(
                            painter = painterResource(R.drawable.lucide_server),
                            contentDescription = null,
                            modifier = Modifier.size(26.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = profileName,
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = sub,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            // Facts.
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                FactCell(label = "Provider", value = providerLabel, modifier = Modifier.weight(1f))
                FactCell(label = "Type", value = "SOCKS5", modifier = Modifier.weight(1f))
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Data Used",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = Utility.formatBytes(displayUsed),
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.tertiary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                FactCell(label = "Server", value = server, mono = true, modifier = Modifier.weight(1f))
            }

            // Actions.
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
            CopyRow(
                copyText = "$server:$port:$username:$password"
            )
            TestRow(
                server = server,
                port = port,
                username = username,
                password = password
            )
            SheetRow(icon = R.drawable.ic_sheet_edit, label = "Edit", onClick = onEdit)
            SheetRow(icon = R.drawable.ic_sheet_duplicate, label = "Duplicate", onClick = onDuplicate)
            SheetRow(icon = R.drawable.ic_sheet_select, label = "Select", onClick = onSelectMode)
            SheetRow(
                icon = R.drawable.ic_sheet_delete,
                label = "Delete",
                danger = true,
                onClick = onDelete
            )
            }
        }
    }
}

@Composable
private fun FactCell(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    mono: Boolean = false
) {
    Column(modifier = modifier) {
        Text(
            text = label,
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = value,
            fontSize = 14.sp,
            fontWeight = FontWeight.Medium,
            fontFamily = if (mono) GeistMonoFonts.Family else null,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun SheetRow(
    icon: Int,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    danger: Boolean = false
) {
    val contentColor = if (danger) MaterialTheme.colorScheme.error
    else MaterialTheme.colorScheme.onSurface
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                role = Role.Button,
                onClickLabel = label,
                onClick = onClick
            )
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Icon(
            painter = painterResource(icon),
            contentDescription = null,
            modifier = Modifier.size(22.dp),
            tint = contentColor
        )
        Text(
            text = label,
            fontSize = 15.sp,
            color = contentColor,
            modifier = Modifier.weight(1f)
        )
    }
}

private enum class TestPhase { Idle, Testing, Works, Failed }

// Copies host:port:user:pass to the clipboard. Feedback is the row itself
// flipping to bold "Copied" in text color with a tap-scale pop — no Toast.
@Composable
private fun CopyRow(
    copyText: String,
    modifier: Modifier = Modifier
) {
    val clipboard = LocalClipboardManager.current
    val scope = rememberCoroutineScope()
    var copied by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(if (copied) 0.96f else 1f, label = "copyPop")
    val contentColor = MaterialTheme.colorScheme.onSurface
    Row(
        modifier = modifier
            .fillMaxWidth()
            .graphicsLayer(scaleX = scale, scaleY = scale)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                role = Role.Button,
                onClickLabel = if (copied) "Copied" else "Copy",
                onClick = {
                    if (copied) return@clickable
                    clipboard.setText(AnnotatedString(copyText))
                    copied = true
                    scope.launch {
                        delay(1200)
                        copied = false
                    }
                }
            )
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Icon(
            painter = painterResource(R.drawable.ic_copy),
            contentDescription = null,
            modifier = Modifier.size(22.dp),
            tint = contentColor
        )
        Text(
            text = if (copied) "Copied" else "Copy",
            fontSize = 15.sp,
            fontWeight = if (copied) FontWeight.Bold else FontWeight.Normal,
            color = contentColor,
            modifier = Modifier.weight(1f)
        )
    }
}

@Composable
private fun TestRow(
    server: String,
    port: Int,
    username: String,
    password: String,
    modifier: Modifier = Modifier
) {
    val scope = rememberCoroutineScope()
    var phase by remember { mutableStateOf(TestPhase.Idle) }
    val contentColor = when (phase) {
        TestPhase.Works -> MaterialTheme.colorScheme.tertiary
        TestPhase.Failed -> MaterialTheme.colorScheme.error
        else -> MaterialTheme.colorScheme.onSurface
    }
    val label = when (phase) {
        TestPhase.Testing -> "Testing"
        TestPhase.Works -> "Works"
        TestPhase.Failed -> "Failed"
        TestPhase.Idle -> "Test"
    }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                role = Role.Button,
                onClickLabel = label,
                onClick = {
                    if (phase != TestPhase.Idle) return@clickable
                    phase = TestPhase.Testing
                    scope.launch {
                        val result = SocksTester.testProxy(server, port, username, password)
                        phase = if (result == SocksTester.TEST_OK) TestPhase.Works else TestPhase.Failed
                        delay(3000)
                        phase = TestPhase.Idle
                    }
                }
            )
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        if (phase == TestPhase.Testing) {
            WifiScanIcon(tint = contentColor)
        } else {
            Icon(
                painter = painterResource(R.drawable.ic_sheet_test),
                contentDescription = null,
                modifier = Modifier.size(22.dp),
                tint = contentColor
            )
        }
        Text(
            text = label,
            fontSize = 15.sp,
            color = contentColor,
            modifier = Modifier.weight(1f)
        )
    }
}

// Wifi arcs lighting inner -> outer (1, 12, 123) while a test runs.
// Stepped by coroutine: this icon only exists during testing, so the loop
// dies with the composition — no animation APIs needed.
@Composable
private fun WifiScanIcon(
    tint: Color,
    modifier: Modifier = Modifier
) {
    var step by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(300)
            step = (step + 1) % 3
        }
    }
    Canvas(modifier = modifier.size(22.dp)) {
        val cx = size.width / 2f
        val cy = size.height * 0.74f
        val dim = tint.copy(alpha = 0.22f)
        val stroke = 1.8.dp.toPx()
        fun arc(radiusPx: Float, start: Float, sweep: Float, color: Color) {
            val d = radiusPx * 2f
            drawArc(
                color = color,
                startAngle = start,
                sweepAngle = sweep,
                useCenter = false,
                topLeft = Offset(cx - radiusPx, cy - radiusPx),
                size = Size(d, d),
                style = Stroke(width = stroke, cap = StrokeCap.Round)
            )
        }
        arc(8.2.dp.toPx(), 225f, 90f, if (step >= 2) tint else dim)
        arc(5.6.dp.toPx(), 235f, 70f, if (step >= 1) tint else dim)
        arc(3.0.dp.toPx(), 245f, 50f, tint)
        drawCircle(color = tint, radius = 1.5.dp.toPx(), center = Offset(cx, cy))
    }
}
