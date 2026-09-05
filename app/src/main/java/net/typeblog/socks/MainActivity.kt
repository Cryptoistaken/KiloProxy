package net.typeblog.socks

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.preference.PreferenceManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.typeblog.socks.ui.components.UpdateDialog
import net.typeblog.socks.ui.navigation.AppNavigation
import net.typeblog.socks.ui.theme.KiloProxyTheme
import net.typeblog.socks.util.Constants.PREF_FLOATING_CONTROL
import net.typeblog.socks.util.Constants.PREF_SKIPPED_UPDATE_VERSION
import net.typeblog.socks.util.UpdateChecker

class MainActivity : ComponentActivity() {
    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            startFloatingControlIfPersisted()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        startFloatingControlIfPersisted()

        setContent {
            val context = this@MainActivity
            var updatePrompt by remember { mutableStateOf<UpdateChecker.UpdateInfo?>(null) }

            // Proactive update check: on launch, look for a newer release and prompt
            // the user once per version (they can update or skip). Runs on a
            // background thread so it never blocks first frame.
            LaunchedEffect(Unit) {
                val info = withContext(Dispatchers.IO) { UpdateChecker.check() }
                if (info != null) {
                    val prefs = PreferenceManager.getDefaultSharedPreferences(context)
                    if (info.versionCode > prefs.getInt(PREF_SKIPPED_UPDATE_VERSION, 0)) {
                        updatePrompt = info
                    }
                }
            }

            KiloProxyTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    AppNavigation()
                }
                updatePrompt?.let { info ->
                    UpdateDialog(
                        info = info,
                        dismissLabel = "Skip",
                        onDismiss = {
                            updatePrompt = null
                            PreferenceManager.getDefaultSharedPreferences(context)
                                .edit()
                                .putInt(PREF_SKIPPED_UPDATE_VERSION, info.versionCode)
                                .apply()
                        }
                    )
                }
            }
        }
    }

    private fun startFloatingControlIfPersisted() {
        val prefs = PreferenceManager.getDefaultSharedPreferences(this)
        if (!prefs.getBoolean(PREF_FLOATING_CONTROL, false)) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        FloatingControlService.start(this)
    }
}
