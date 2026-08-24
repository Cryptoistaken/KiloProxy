package net.typeblog.socks.ui.screens

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.typeblog.socks.util.KiloProxyAuth
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

@Composable
fun AuthScreen(onLoggedIn: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var checking by remember { mutableStateOf(false) }
    var deviceId by remember { mutableStateOf(KiloProxyAuth.getOrCreateDeviceId(context)) }

    suspend fun checkLogin(): Boolean = withContext(Dispatchers.IO) {
        try {
            val url = URL("https://kilosms.up.railway.app/api/kiloproxy/auth/check?token=$deviceId")
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "GET"
            conn.connectTimeout = 8000
            conn.readTimeout = 8000
            val code = conn.responseCode
            if (code != 200) return@withContext false
            val body = conn.inputStream.bufferedReader().readText()
            val json = JSONObject(body)
            if (json.optBoolean("ok") && json.has("uid")) {
                val uid = json.getString("uid")
                val username = json.optString("username", "")
                KiloProxyAuth.saveLogin(context, uid, username)
                return@withContext true
            }
            false
        } catch (_: Exception) { false }
    }

    suspend fun trackLogin() = withContext(Dispatchers.IO) {
        try {
            val uid = KiloProxyAuth.getUid(context) ?: return@withContext
            val country = KiloProxyAuth.getCountry(context)
            val url = URL("https://kilosms.up.railway.app/api/kiloproxy/track")
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json")
            val payload = JSONObject().apply {
                put("uid", uid)
                put("host", "login")
                put("country", country)
                put("appVersion", "1.0")
            }.toString()
            conn.outputStream.use { it.write(payload.toByteArray()) }
            conn.inputStream.close()
        } catch (_: Exception) {}
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = "KiloProxy",
            style = MaterialTheme.typography.headlineLarge,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = "Telegram login required to use the app",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
        Spacer(modifier = Modifier.height(24.dp))
        Text(
            text = "You must log in with Telegram via @KiloSMSBot and complete the join steps. This links your proxy purchases to your app automatically.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            lineHeight = 18.sp
        )
        Spacer(modifier = Modifier.height(32.dp))
        Button(
            onClick = {
                val url = "https://t.me/KiloSMSBot?start=kp_login_$deviceId"
                try { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) } catch (_: Exception) {
                    Toast.makeText(context, "Cannot open Telegram", Toast.LENGTH_SHORT).show()
                }
            },
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp)
        ) {
            Text("Login with Telegram", fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
        }
        Spacer(modifier = Modifier.height(12.dp))
        OutlinedButton(
            onClick = {
                scope.launch {
                    checking = true
                    var success = false
                    repeat(15) {
                        if (checkLogin()) { success = true; return@repeat }
                        delay(2000)
                    }
                    checking = false
                    if (success) {
                        trackLogin()
                        Toast.makeText(context, "Login successful", Toast.LENGTH_SHORT).show()
                        onLoggedIn()
                    } else {
                        Toast.makeText(context, "Not yet logged in. Please tap Login with Telegram and complete in bot.", Toast.LENGTH_LONG).show()
                    }
                }
            },
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
            enabled = !checking
        ) {
            if (checking) {
                CircularProgressIndicator(modifier = Modifier.height(16.dp), strokeWidth = 2.dp)
                Text("  Checking...", fontSize = 13.sp)
            } else {
                Text("I have logged in  Check", fontSize = 13.sp)
            }
        }
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = "Proxies bought via @KiloSMSBot will be auto-added to your app. No import needed. We track only the host for analytics, not your full proxy.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            lineHeight = 16.sp
        )
    }
}
