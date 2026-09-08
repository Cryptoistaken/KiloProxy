package net.typeblog.socks.ui.screens

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.net.VpnService
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.preference.PreferenceManager
import android.content.SharedPreferences
import net.typeblog.socks.FloatingControlService
import net.typeblog.socks.R
import net.typeblog.socks.util.Constants.BUBBLE_STYLE_CLASSIC
import net.typeblog.socks.util.Constants.BUBBLE_STYLE_LOCK
import net.typeblog.socks.util.Constants.PREF_BUBBLE_STYLE
import net.typeblog.socks.util.Constants.PREF_FLOATING_CONTROL

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BubbleSettingsScreen(
    onNavigateBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val prefs = PreferenceManager.getDefaultSharedPreferences(context)

    var floatingEnabled by remember { mutableStateOf(prefs.getBoolean(PREF_FLOATING_CONTROL, false)) }
    var bubbleStyle by remember { mutableStateOf(prefs.getString(PREF_BUBBLE_STYLE, BUBBLE_STYLE_LOCK) ?: BUBBLE_STYLE_LOCK) }

    DisposableEffect(context) {
        val l = SharedPreferences.OnSharedPreferenceChangeListener { _, k ->
            when (k) {
                PREF_FLOATING_CONTROL -> floatingEnabled = prefs.getBoolean(PREF_FLOATING_CONTROL, false)
                PREF_BUBBLE_STYLE -> bubbleStyle = prefs.getString(PREF_BUBBLE_STYLE, BUBBLE_STYLE_LOCK) ?: BUBBLE_STYLE_LOCK
            }
        }
        prefs.registerOnSharedPreferenceChangeListener(l)
        onDispose { prefs.unregisterOnSharedPreferenceChangeListener(l) }
    }

    fun canDrawOverlays(c: Context): Boolean = Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(c)
    fun startService(c: Context) {
        val i = Intent(c, FloatingControlService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) c.startForegroundService(i) else c.startService(i)
    }

    var pendingStart by remember { mutableStateOf(false) }
    val vpnLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        if (pendingStart && r.resultCode == android.app.Activity.RESULT_OK && canDrawOverlays(context)) {
            prefs.edit().putBoolean(PREF_FLOATING_CONTROL, true).putString(PREF_BUBBLE_STYLE, bubbleStyle).apply()
            floatingEnabled = true; startService(context)
        }
        pendingStart = false
    }
    val notifLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { g ->
        if (pendingStart && g && canDrawOverlays(context)) {
            val pi = VpnService.prepare(context)
            if (pi == null) { prefs.edit().putBoolean(PREF_FLOATING_CONTROL, true).apply(); floatingEnabled = true; startService(context) }
            else { pendingStart = true; vpnLauncher.launch(pi) }
        } else pendingStart = false
    }
    val overlayLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (!canDrawOverlays(context)) return@rememberLauncherForActivityResult
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            pendingStart = true; notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            val pi = VpnService.prepare(context)
            if (pi == null) { prefs.edit().putBoolean(PREF_FLOATING_CONTROL, true).apply(); floatingEnabled = true; startService(context) }
            else { pendingStart = true; vpnLauncher.launch(pi) }
        }
    }

    fun setEnabled(enabled: Boolean) {
        if (enabled) {
            if (prefs.getString(PREF_BUBBLE_STYLE, null) == null) prefs.edit().putString(PREF_BUBBLE_STYLE, BUBBLE_STYLE_LOCK).apply()
            if (canDrawOverlays(context)) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                    pendingStart = true; notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                } else {
                    val pi = VpnService.prepare(context)
                    if (pi == null) { prefs.edit().putBoolean(PREF_FLOATING_CONTROL, true).apply(); floatingEnabled = true; startService(context) }
                    else { pendingStart = true; vpnLauncher.launch(pi) }
                }
            } else {
                pendingStart = true
                overlayLauncher.launch(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${context.packageName}")))
            }
        } else {
            prefs.edit().putBoolean(PREF_FLOATING_CONTROL, false).apply()
            floatingEnabled = false
            context.stopService(Intent(context, FloatingControlService::class.java))
        }
    }

    fun setStyle(style: String) {
        prefs.edit().putString(PREF_BUBBLE_STYLE, style).apply()
        bubbleStyle = style
        if (floatingEnabled) { context.stopService(Intent(context, FloatingControlService::class.java)); startService(context) }
    }

    Scaffold(
        modifier = modifier,
        contentWindowInsets = WindowInsets(0),
        topBar = {
            TopAppBar(
                title = { Text("Floating Bubble") },
                windowInsets = WindowInsets(0),
                navigationIcon = {
                    androidx.compose.material3.IconButton(onClick = onNavigateBack) {
                        Icon(painter = painterResource(R.drawable.lucide_arrow_left), contentDescription = "Back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface, titleContentColor = MaterialTheme.colorScheme.onSurface)
            )
        }
    ) { pv ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(pv)
                .padding(horizontal = 16.dp)
        ) {
            // Master switch
            Surface(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp), shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceContainerLow) {
                Row(modifier = Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("Enable floating bubble", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.weight(1f))
                    Switch(checked = floatingEnabled, onCheckedChange = { setEnabled(it) })
                }
            }

            Text("Bubble style", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 4.dp, vertical = 8.dp))
            SelectableStyleRow(
                selected = bubbleStyle == BUBBLE_STYLE_LOCK,
                onClick = { setStyle(BUBBLE_STYLE_LOCK) },
                title = "Lock"
            )
            Spacer(Modifier.height(8.dp))
            SelectableStyleRow(
                selected = bubbleStyle == BUBBLE_STYLE_CLASSIC,
                onClick = { setStyle(BUBBLE_STYLE_CLASSIC) },
                title = "Classic"
            )
        }
    }
}

@Composable
private fun SelectableStyleRow(
    selected: Boolean,
    onClick: () -> Unit,
    title: String
) {
    Surface(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        shape = RoundedCornerShape(16.dp),
        color = if (selected) MaterialTheme.colorScheme.surfaceContainerHigh else MaterialTheme.colorScheme.surfaceContainerLow,
        tonalElevation = if (selected) 2.dp else 0.dp
    ) {
        Row(modifier = Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.weight(1f))
            RadioButton(selected = selected, onClick = onClick)
        }
    }
}
