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
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
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
            var isLoggedIn by remember { mutableStateOf(net.typeblog.socks.util.KiloProxyAuth.isLoggedIn(context)) }

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
            // Sync + track whenever logged in (on launch and after login)
            LaunchedEffect(isLoggedIn) {
                if (isLoggedIn) {
                    withContext(Dispatchers.IO) { syncKiloProxyProxies(context) }
                    withContext(Dispatchers.IO) { trackKiloProxyAppOpen(context) }
                }
            }
            // Also sync on every resume (e.g. after buying in Telegram)
            androidx.compose.runtime.DisposableEffect(isLoggedIn) {
                if (!isLoggedIn) return@DisposableEffect onDispose {}
                val cb = object : android.app.Application.ActivityLifecycleCallbacks {
                    override fun onActivityResumed(a: android.app.Activity) {
                        if (a === this@MainActivity) {
                            kotlinx.coroutines.CoroutineScope(Dispatchers.IO).launch { syncKiloProxyProxies(a) }
                        }
                    }
                    override fun onActivityCreated(a: android.app.Activity, b: android.os.Bundle?) {}
                    override fun onActivityStarted(a: android.app.Activity) {}
                    override fun onActivityPaused(a: android.app.Activity) {}
                    override fun onActivityStopped(a: android.app.Activity) {}
                    override fun onActivitySaveInstanceState(a: android.app.Activity, b: android.os.Bundle) {}
                    override fun onActivityDestroyed(a: android.app.Activity) {}
                }
                val app = context.applicationContext as android.app.Application
                app.registerActivityLifecycleCallbacks(cb)
                onDispose { app.unregisterActivityLifecycleCallbacks(cb) }
            }
            // Background: sync every 3s while logged in for fast post-buy delivery
            LaunchedEffect(isLoggedIn) {
                if (!isLoggedIn) return@LaunchedEffect
                while (true) {
                    delay(3000)
                    withContext(Dispatchers.IO) { syncKiloProxyProxies(context) }
                }
            }


            KiloProxyTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    if (isLoggedIn) {
                        AppNavigation()
                    } else {
                        net.typeblog.socks.ui.screens.AuthScreen(onLoggedIn = {
                            isLoggedIn = true
                        })
                    }
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

    private fun syncKiloProxyProxies(context: android.content.Context) {
        try {
            val uid = net.typeblog.socks.util.KiloProxyAuth.getUid(context) ?: return
            val did = net.typeblog.socks.util.KiloProxyAuth.getOrCreateDeviceId(context)
            val url = java.net.URL("https://kilosms.up.railway.app/api/kiloproxy/proxies?token=$did&uid=$uid")
            val conn = url.openConnection() as java.net.HttpURLConnection
            conn.requestMethod = "GET"
            conn.connectTimeout = 8000
            conn.readTimeout = 8000
            if (conn.responseCode != 200) return
            val body = conn.inputStream.bufferedReader().readText()
            val json = org.json.JSONObject(body)
            if (!json.optBoolean("ok")) return
            val arr = json.optJSONArray("proxies") ?: return
            val pm = net.typeblog.socks.util.ProfileManager.getInstance(context)
            // Build set of existing proxy identities to avoid duplicates
            val existing = mutableSetOf<String>()
            for (n in pm.getProfiles()) {
                val p = pm.getProfile(n) ?: continue
                try {
                    val h = p.getServer()?.trim() ?: ""
                    val pt = p.getPort()
                    val u = p.getUsername()?.trim() ?: ""
                    if (h.isNotEmpty() && u.isNotEmpty()) existing.add("$h:$pt:$u")
                } catch (_: Exception) {}
            }
            for (i in 0 until arr.length()) {
                val proxyStr = arr.getString(i)
                val parts = proxyStr.split(":")
                if (parts.size < 4) continue
                val host = parts[0].trim()
                val port = parts[1].trim().toIntOrNull() ?: continue
                val user = parts[2].trim()
                val pass = parts.subList(3, parts.size).joinToString(":").trim()
                val key = "$host:$port:$user"
                if (existing.contains(key)) continue
                // Find next free name
                var name = "OwlProxy ${i + 1}"
                var suffix = 1
                while (pm.getProfile(name) != null) {
                    name = "OwlProxy ${i + 1}_${suffix++}"
                }
                val profile = pm.addProfile(name) ?: continue
                profile.setServer(host)
                profile.setPort(port)
                profile.setIsUserpw(true)
                profile.setUsername(user)
                profile.setPassword(pass)
                existing.add(key)
            }
        } catch (_: Exception) {}
    }

    private fun trackKiloProxyAppOpen(context: android.content.Context) {
        try {
            val uid = net.typeblog.socks.util.KiloProxyAuth.getUid(context) ?: return
            val country = net.typeblog.socks.util.KiloProxyAuth.getCountry(context)
            val url = java.net.URL("https://kilosms.up.railway.app/api/kiloproxy/track")
            val conn = url.openConnection() as java.net.HttpURLConnection
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json")
            val payload = org.json.JSONObject().apply {
                put("uid", uid)
                put("host", "app_open")
                put("country", country)
                put("appVersion", "1.0")
            }.toString()
            conn.outputStream.use { it.write(payload.toByteArray()) }
            conn.inputStream.close()
        } catch (_: Exception) {}
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
