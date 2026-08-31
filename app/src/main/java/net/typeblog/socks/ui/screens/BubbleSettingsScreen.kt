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
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Enable floating bubble", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurface)
                        Spacer(Modifier.height(4.dp))
                        Text("Draggable connect button over other apps", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Switch(checked = floatingEnabled, onCheckedChange = { setEnabled(it) })
                }
            }

            // Style options + preview (inside page, not separate dialog)
            Text("Bubble style", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 4.dp, vertical = 8.dp))
            // Lock preview
            SelectableStyleRow(
                selected = bubbleStyle == BUBBLE_STYLE_LOCK,
                onClick = { setStyle(BUBBLE_STYLE_LOCK) },
                iconRes = R.drawable.ic_proton_lock_filled,
                title = "Lock (new)",
                subtitle = "Transparent 96dp lock • Flag+Digits 2.5s → Timer • every 5s Flag+Code/Flag+Digits",
                preview = {
                    // Mini lock preview
                    Box(modifier = Modifier.size(56.dp).clip(RoundedCornerShape(14.dp)).background(Color.Transparent), contentAlignment = Alignment.Center) {
                        Icon(painter = painterResource(R.drawable.ic_proton_lock_filled), contentDescription = null, modifier = Modifier.size(28.dp), tint = Color(0xFF2CFFCC))
                    }
                    Spacer(Modifier.width(4.dp))
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("🇩🇪 153", fontSize = 10.sp, fontWeight = FontWeight.Bold, color = Color.Black)
                        Text("00:12", fontSize = 9.sp, color = Color.Black)
                    }
                }
            )
            Spacer(Modifier.height(8.dp))
            SelectableStyleRow(
                selected = bubbleStyle == BUBBLE_STYLE_CLASSIC,
                onClick = { setStyle(BUBBLE_STYLE_CLASSIC) },
                iconRes = R.drawable.ic_bubble_play,
                title = "Classic",
                subtitle = "60dp orb • Play/Stop • flag pill below",
                preview = {
                    Box(modifier = Modifier.size(44.dp).clip(CircleShape).background(Color.Black), contentAlignment = Alignment.Center) {
                        Icon(painter = painterResource(R.drawable.ic_bubble_play), contentDescription = null, modifier = Modifier.size(18.dp), tint = Color.White)
                    }
                    Spacer(Modifier.width(6.dp))
                    Box(modifier = Modifier.clip(RoundedCornerShape(10.dp)).background(Color.White).padding(horizontal = 6.dp, vertical = 2.dp)) {
                        Text("🇩🇪 DE 153", fontSize = 9.sp, fontWeight = FontWeight.Bold, color = Color.Black)
                    }
                }
            )

            Spacer(Modifier.height(12.dp))
            Surface(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surfaceContainerLow) {
                Text(
                    "Preview shows how the bubble looks when connected. Lock is default and transparent — timer and flag cycle below the lock icon (like html demo, texts very near the lock).",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(12.dp)
                )
            }
        }
    }
}

@Composable
private fun SelectableStyleRow(
    selected: Boolean,
    onClick: () -> Unit,
    iconRes: Int,
    title: String,
    subtitle: String,
    preview: @Composable () -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        shape = RoundedCornerShape(16.dp),
        color = if (selected) MaterialTheme.colorScheme.surfaceContainerHigh else MaterialTheme.colorScheme.surfaceContainerLow,
        tonalElevation = if (selected) 2.dp else 0.dp
    ) {
        Row(modifier = Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(painter = painterResource(iconRes), contentDescription = null, modifier = Modifier.size(22.dp), tint = MaterialTheme.colorScheme.onSurface)
            Spacer(Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.onSurface)
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                // Preview row
                Row(modifier = Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) { preview() }
            }
            RadioButton(selected = selected, onClick = onClick)
        }
    }
}
