package net.typeblog.socks.ui.components

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
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
    onDelete: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    liveUsageRx: Long = 0L,
    liveUsageTx: Long = 0L
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var testing by remember { mutableStateOf(false) }

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

    ModalBottomSheet(
        onDismissRequest = onDismiss,
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
            SheetRow(
                icon = R.drawable.ic_sheet_test,
                label = if (testing) "Testing" else "Test",
                trailing = testing,
                onClick = {
                    if (testing) return@SheetRow
                    testing = true
                    scope.launch {
                        val result = SocksTester.testProxy(server, port, username, password)
                        android.widget.Toast.makeText(
                            context, result, android.widget.Toast.LENGTH_LONG
                        ).show()
                        testing = false
                    }
                }
            )
            SheetRow(icon = R.drawable.ic_sheet_edit, label = "Edit", onClick = onEdit)
            SheetRow(icon = R.drawable.ic_sheet_duplicate, label = "Duplicate", onClick = onDuplicate)
            SheetRow(
                icon = R.drawable.ic_sheet_delete,
                label = "Delete",
                danger = true,
                onClick = onDelete
            )
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
    danger: Boolean = false,
    trailing: Boolean = false
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
        if (trailing) {
            CircularProgressIndicator(
                modifier = Modifier.size(16.dp),
                strokeWidth = 2.dp,
                color = contentColor
            )
        }
    }
}
