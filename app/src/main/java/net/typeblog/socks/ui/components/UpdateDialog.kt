package net.typeblog.socks.ui.components

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.typeblog.socks.R
import net.typeblog.socks.util.UpdateChecker

/**
 * Update-available dialog shared between the in-app "check updates" flow and the
 * proactive launch-time prompt. Shows the release notes, then live download
 * progress once the user taps Update, and hands off to the package installer
 * when the APK finishes downloading. [onDismiss] is called when the user picks
 * "Later"/"Skip" or after the download+install hand-off completes.
 */
@Composable
fun UpdateDialog(
    info: UpdateChecker.UpdateInfo,
    onDismiss: () -> Unit,
    dismissLabel: String = "Later"
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var downloading by remember { mutableStateOf(false) }
    var downloadProgress by remember { mutableStateOf(0f) }
    var permissionPending by remember { mutableStateOf(false) }

    fun startDownload() {
        downloading = true
        downloadProgress = 0f
        scope.launch {
            val err = withContext(Dispatchers.IO) {
                UpdateChecker.downloadAndInstall(
                    context, info.apkUrl, info.sizeBytes
                ) { progress ->
                    scope.launch { downloadProgress = progress }
                }
            }
            downloading = false
            onDismiss()
            if (err != null) {
                Toast.makeText(context, err, Toast.LENGTH_LONG).show()
            }
        }
    }

    val unknownSourceLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { _ ->
        if (context.packageManager.canRequestPackageInstalls()) {
            startDownload()
        } else {
            Toast.makeText(
                context,
                "Please allow 'Install unknown apps' for KiloProxy, then try again",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    fun launchDownload() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !context.packageManager.canRequestPackageInstalls()) {
            permissionPending = true
        } else {
            startDownload()
        }
    }

    if (permissionPending) {
        AlertDialog(
            onDismissRequest = { permissionPending = false },
            title = { Text(text = "Allow installing updates?") },
            text = {
                Text(
                    text = "KiloProxy needs to install the update. " +
                        "You'll be taken to Settings to allow \"Install unknown apps\" for KiloProxy " +
                        "— this is required only once."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    permissionPending = false
                    unknownSourceLauncher.launch(
                        Intent(
                            Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                            Uri.parse("package:${context.packageName}")
                        )
                    )
                }) {
                    Text(text = "Allow")
                }
            },
            dismissButton = {
                TextButton(onClick = { permissionPending = false }) {
                    Text(text = "Cancel")
                }
            }
        )
    }

    AlertDialog(
        onDismissRequest = { if (!downloading) onDismiss() },
        title = {
            Text(text = if (downloading) "Downloading update…" else "Update available")
        },
        text = {
            Column {
                if (downloading) {
                    LinearProgressIndicator(
                        progress = { downloadProgress },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    val mb = info.sizeBytes / 1048576.0
                    Text(
                        text = "${(downloadProgress * 100).toInt()}% · " +
                            "%.1f / %.1f MB".format(downloadProgress * mb, mb),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    Text(
                        text = "v${info.tag} · ${"%.1f MB".format(info.sizeBytes / 1048576.0)} — " +
                            "install over the current version. Profiles and app data are preserved."
                    )
                    if (info.body.isNotBlank()) {
                        Spacer(modifier = Modifier.height(12.dp))
                        HorizontalDivider(
                            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            text = context.getString(R.string.whats_new_dialog_title),
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(text = info.body)
                    }
                }
            }
        },
        confirmButton = {
            if (!downloading) {
                TextButton(onClick = { launchDownload() }) {
                    Text(text = "Update")
                }
            }
        },
        dismissButton = {
            if (!downloading) {
                TextButton(onClick = onDismiss) {
                    Text(text = dismissLabel)
                }
            }
        }
    )
}
