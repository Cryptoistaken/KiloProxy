package net.typeblog.socks.ui.screens

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.typeblog.socks.R
import net.typeblog.socks.util.KiloProxyAuth
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

@Composable
fun AuthScreen(onLoggedIn: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var checking by remember { mutableStateOf(false) }
    var hasClicked by remember { mutableStateOf(false) }
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

    suspend fun startPolling() {
        checking = true
        var success = false
        for (i in 0 until 30) {
            if (checkLogin()) { success = true; break }
            delay(2000)
        }
        checking = false
        if (success) {
            trackLogin()
            Toast.makeText(context, "Login successful", Toast.LENGTH_SHORT).show()
            onLoggedIn()
        } else {
            Toast.makeText(context, "Not yet logged in. Please complete login in Telegram.", Toast.LENGTH_LONG).show()
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Image(
            painter = painterResource(id = R.mipmap.ic_launcher),
            contentDescription = "KiloProxy Logo",
            modifier = Modifier.size(96.dp)
        )
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = "KiloProxy",
            style = MaterialTheme.typography.headlineLarge,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary
        )
        Spacer(modifier = Modifier.height(32.dp))
        Button(
            onClick = {
                hasClicked = true
                val url = "https://t.me/KiloSMSBot?start=kp_login_$deviceId"
                try { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) } catch (_: Exception) {
                    Toast.makeText(context, "Cannot open Telegram", Toast.LENGTH_SHORT).show()
                }
                scope.launch { startPolling() }
            },
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
            enabled = !checking
        ) {
            Text("Login with Telegram", fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
        }
        if (hasClicked) {
            Spacer(modifier = Modifier.height(12.dp))
            OutlinedButton(
                onClick = {
                    scope.launch { startPolling() }
                },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                enabled = !checking
            ) {
                if (checking) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                    Text("  Checking...", fontSize = 13.sp)
                } else {
                    Text("I have logged in — Check", fontSize = 13.sp)
                }
            }
            if (checking) {
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = "Waiting for Telegram confirmation…",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )
            }
        }
    }
}
