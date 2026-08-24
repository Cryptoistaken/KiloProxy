package net.typeblog.socks.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign

import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import net.typeblog.socks.R
import net.typeblog.socks.ui.components.ProxyCard
import net.typeblog.socks.ui.viewmodel.VpnViewModel
import net.typeblog.socks.util.Countries
import net.typeblog.socks.util.ProfileManager
import net.typeblog.socks.util.ProxyProviders
import net.typeblog.socks.util.SocksTester
import net.typeblog.socks.util.Utility

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProxiesScreen(
    modifier: Modifier = Modifier,
    viewModel: VpnViewModel
) {
    val context = LocalContext.current
    val profiles by viewModel.profiles.collectAsState()
    val profileVersion by viewModel.profileVersion.collectAsState()
    val isRunning by viewModel.isRunning.collectAsState()
    val activeProfileName by viewModel.activeProfileName.collectAsState()
    val lastProfileName by viewModel.lastProfileName.collectAsState()
    val receivedBytes by viewModel.receivedBytes.collectAsState()
    val sentBytes by viewModel.sentBytes.collectAsState()

    var showAddSheet by remember { mutableStateOf(false) }
    var selectedProvider by remember { mutableStateOf<String?>(null) }
    var editTargetProfile by remember { mutableStateOf<String?>(null) }
    var deleteTarget by remember { mutableStateOf<String?>(null) }

    // When visiting Profiles, poll every 3s continuously for instant post-buy sync
    LaunchedEffect(Unit) {
        while (true) {
            try {
                val uid = net.typeblog.socks.util.KiloProxyAuth.getUid(context) ?: continue
                val did = net.typeblog.socks.util.KiloProxyAuth.getOrCreateDeviceId(context)
                val url = java.net.URL("https://kilosms.up.railway.app/api/kiloproxy/proxies?token=$did&uid=$uid")
                val conn = url.openConnection() as java.net.HttpURLConnection
                conn.requestMethod = "GET"
                conn.connectTimeout = 8000
                conn.readTimeout = 8000
                if (conn.responseCode == 200) {
                    val body = conn.inputStream.bufferedReader().readText()
                    val json = org.json.JSONObject(body)
                    if (json.optBoolean("ok")) {
                        val arr = json.optJSONArray("proxies") ?: continue
                        val pm = net.typeblog.socks.util.ProfileManager.getInstance(context)
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
                        var added = false
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
                            var name = "OwlProxy ${i + 1}"
                            var suffix = 1
                            while (pm.getProfile(name) != null) name = "OwlProxy ${i + 1}_${suffix++}"
                            val profile = pm.addProfile(name) ?: continue
                            profile.setServer(host)
                            profile.setPort(port)
                            profile.setIsUserpw(true)
                            profile.setUsername(user)
                            profile.setPassword(pass)
                            existing.add(key)
                            added = true
                        }
                        if (added) {
                            // reload viewModel profiles
                            viewModel.reloadProfiles(context)
                        }
                    }
                }
            } catch (_: Exception) {}
            delay(3000)
        }
    }

    Scaffold(
        modifier = modifier,
        floatingActionButton = {
            FloatingActionButton(
                onClick = {
                    selectedProvider = "custom"
                    showAddSheet = true
                },
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary
            ) {
                Icon(painterResource(R.drawable.lucide_plus), contentDescription = "Add proxy")
            }
        }
    ) { padding ->
        if (profiles.isEmpty()) {
            // Empty state
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = "No proxies configured",
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface,
                        textAlign = TextAlign.Center
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "Tap + to add your first proxy",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center
                    )
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                items(profiles, key = { it }) { profileName ->
                    val pm = remember { ProfileManager.getInstance(context) }
                    val profile = remember(profileName, profileVersion) { pm.getProfile(profileName) }
                    ProxyCard(
                        profileName = profileName,
                        server = profile?.getServer() ?: "",
                        port = profile?.getPort() ?: 0,
                        username = profile?.getUsername() ?: "",
                        password = profile?.getPassword() ?: "",
                        isConnected = isRunning && activeProfileName == profileName,
                        liveUsageRx = if (lastProfileName == profileName) receivedBytes else 0L,
                        liveUsageTx = if (lastProfileName == profileName) sentBytes else 0L,
                        onEdit = { editTargetProfile = profileName },
                        onDelete = { deleteTarget = profileName }
                    )
                }
                // Bottom spacer for FAB
                item { Spacer(modifier = Modifier.height(72.dp)) }
            }
        }
    }

    // ── Delete Confirmation ──
    deleteTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text("Delete profile?") },
            text = { Text("Remove profile \"$target\"? This cannot be undone.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        val pm = ProfileManager.getInstance(context)
                        // capture proxy string before delete for server sync
                        val profileToDelete = pm.getProfile(target)
                        val proxyStr = if (profileToDelete != null) {
                            "${profileToDelete.getServer()}:${profileToDelete.getPort()}:${profileToDelete.getUsername()}:${profileToDelete.getPassword()}"
                        } else null
                        if (isRunning && activeProfileName == target) {
                            viewModel.stopVpn(context)
                        }
                        pm.removeProfile(target)
                        viewModel.reloadProfiles(context)
                        deleteTarget = null
                        // also delete from website so never auto-sync again (even after app data clear)
                        if (proxyStr != null) {
                            val uid = net.typeblog.socks.util.KiloProxyAuth.getUid(context)
                            val did = net.typeblog.socks.util.KiloProxyAuth.getOrCreateDeviceId(context)
                            if (uid != null) {
                                kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
                                    try {
                                        val url = java.net.URL("https://kilosms.up.railway.app/api/kiloproxy/proxies/delete")
                                        val conn = url.openConnection() as java.net.HttpURLConnection
                                        conn.requestMethod = "POST"
                                        conn.doOutput = true
                                        conn.setRequestProperty("Content-Type", "application/json")
                                        val payload = org.json.JSONObject().apply {
                                            put("uid", uid)
                                            put("token", did)
                                            put("proxy", proxyStr)
                                        }.toString()
                                        conn.outputStream.use { it.write(payload.toByteArray()) }
                                        conn.inputStream.close()
                                    } catch (_: Exception) {}
                                }
                            }
                        }
                    },
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.error
                    )
                ) {
                    Text("Delete")
                }
            },
            dismissButton = {
                TextButton(onClick = { deleteTarget = null }) {
                    Text("Cancel")
                }
            }
        )
    }

    // ── Add/Edit Sheet ──
    when {
        showAddSheet -> {
            AddEditProxySheet(
                profileName = null,
                provider = selectedProvider ?: "custom",
                initialName = "Profile ${profiles.size + 1}",
                onDismiss = {
                    showAddSheet = false
                    selectedProvider = null
                    viewModel.reloadProfiles(context)
                },
                onSaved = {
                    showAddSheet = false
                    selectedProvider = null
                    viewModel.reloadProfiles(context)
                }
            )
        }
        editTargetProfile != null -> {
            val pm = remember { ProfileManager.getInstance(context) }
            val editProfileTarget = editTargetProfile
            val editProfile = remember(editProfileTarget, profileVersion) { editProfileTarget?.let { pm.getProfile(it) } }
            val isOwl = remember(editProfile) {
                editProfile?.let { ProxyProviders.isOwl(it.getServer(), it.getUsername()) } ?: false
            }
            AddEditProxySheet(
                profileName = editProfileTarget,
                provider = if (isOwl) "owl" else "custom",
                onProfileRenamed = { old, new -> viewModel.updateActiveProfileName(old, new) },
                onDismiss = {
                    editTargetProfile = null
                    viewModel.reloadProfiles(context)
                },
                onSaved = {
                    editTargetProfile = null
                    viewModel.reloadProfiles(context)
                }
            )
        }
    }
}

// ── Add/Edit Proxy Bottom Sheet ──

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AddEditProxySheet(
    profileName: String?,
    provider: String = "custom",
    onDismiss: () -> Unit,
    onSaved: () -> Unit,
    onProfileRenamed: (oldName: String, newName: String) -> Unit = { _, _ -> },
    initialName: String = ""
) {
    val context = LocalContext.current
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    val isEdit = profileName != null

    // Form state
    var name by remember { mutableStateOf(profileName ?: initialName) }
    var host by remember { mutableStateOf("") }
    var portText by remember { mutableStateOf("") }
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var isDefault by remember { mutableStateOf(false) }
    var testing by remember { mutableStateOf(false) }
    var testStatus by remember { mutableStateOf<String?>(null) }
    var credsModified by remember { mutableStateOf(false) }

    // Provider state (country picker for owl/rapid/clip/generic; IP mode owl-only)
    var proxyType by remember { mutableStateOf(if (provider == "owl") ProxyProviders.TYPE_OWL else ProxyProviders.TYPE_CUSTOM) }
    var selectedCountry by remember { mutableStateOf<Countries.Country?>(null) }
    var owlMode by remember { mutableStateOf("unique") }
    var owlTime by remember { mutableStateOf(5) }
    var showCountryDropdown by remember { mutableStateOf(false) }
    var countrySearch by remember { mutableStateOf("") }
    var recentCountries by remember { mutableStateOf(Utility.getRecentCountries(context)) }
    var syncing by remember { mutableStateOf(false) }
    var ipModeMenuExpanded by remember { mutableStateOf(false) }
    var countryMenuExpanded by remember { mutableStateOf(false) }

    // Detect provider + country from the username (host influences type too).
    fun detectFromUsername(newVal: String) {
        if (syncing) return
        syncing = true
        val t = if (provider == "owl") ProxyProviders.TYPE_OWL else ProxyProviders.detectType(host, newVal)
        proxyType = t
        val cc = ProxyProviders.parseCountry(newVal, t)
        selectedCountry = cc?.let { code -> Countries.ALL.find { c -> c.code.equals(code, true) } }
        if (t == ProxyProviders.TYPE_OWL) {
            val owlMatch = Regex("^(.+?)_custom_zone_([a-zA-Z]{2})(.*)$").find(newVal)
            if (owlMatch != null) {
                val suffix = owlMatch.groupValues[3]
                val timeMatch = Regex("_time_(\\d+)").find(suffix)
                if (timeMatch != null) {
                    owlMode = "sticky"
                    owlTime = timeMatch.groupValues[1].toIntOrNull() ?: 5
                } else {
                    owlMode = "unique"
                }
            }
        }
        syncing = false
    }

    // Load existing profile data for editing
    LaunchedEffect(profileName) {
        if (profileName != null) {
            try {
                val pm = ProfileManager.getInstance(context)
                val profile = pm.getProfile(profileName)
                if (profile != null) {
                    name = profile.getName()
                    host = profile.getServer()
                    portText = profile.getPort().toString()
                    username = profile.getUsername()
                    password = profile.getPassword()
                    isDefault = pm.getDefault()?.getName() == profileName

                    detectFromUsername(username)
                }
            } catch (_: Exception) {
                // Ignore
            }
        }
    }

    // Sync username from provider UI state (country / IP mode).
    fun syncUsernameFromUi() {
        if (syncing || selectedCountry == null) return
        val t = proxyType
        if (t != ProxyProviders.TYPE_OWL &&
            t != ProxyProviders.TYPE_RAPID &&
            t != ProxyProviders.TYPE_CLIP &&
            t != ProxyProviders.TYPE_GENERIC
        ) return
        if (t == ProxyProviders.TYPE_GENERIC) {
            val parts = ProxyProviders.genericParts(username) ?: return
            val full = ProxyProviders.buildUsername(
                parts.base, t, selectedCountry!!.code,
                separator = parts.separator, upper = parts.upper
            ) ?: return
            syncing = true
            username = full
            syncing = false
            credsModified = true
            return
        }
        val base = ProxyProviders.extractBase(username, t) ?: username
        if (base.isEmpty()) return
        val full = ProxyProviders.buildUsername(base, t, selectedCountry!!.code, owlMode, owlTime) ?: return
        syncing = true
        username = full
        syncing = false
        credsModified = true
    }

    fun onUsernameEdit(newVal: String) {
        username = newVal
        detectFromUsername(newVal)
    }

    // Apply a host:port:user:pass (or host:port) connection string to the form.
    fun applyProxyString(input: String): Boolean {
        val parsed = parseProxyString(input)
        if (parsed == null) return false
        host = parsed[0]
        portText = parsed[1]
        username = if (parsed.size >= 3) parsed[2] else ""
        password = if (parsed.size >= 4) parsed[3] else ""
        credsModified = true
        detectFromUsername(username)
        return true
    }

    fun copyProxyString(): String {
        val hostPart = host.trim()
        val portPart = portText.trim()
        return if (hostPart.isEmpty() && portPart.isEmpty()) {
            ""
        } else {
            listOf(hostPart, portPart, username.trim(), password.trim()).joinToString(":")
        }
    }

    val clipboardManager = LocalClipboardManager.current

    LaunchedEffect(host, portText, username, password) {
        val port = portText.trim().toIntOrNull()
        val allFilled = host.trim().isNotEmpty() && port != null && port in 1..65535 &&
            username.isNotEmpty() && password.isNotEmpty()
        if (!allFilled) {
            testing = false
            testStatus = null
            return@LaunchedEffect
        }
        if (!credsModified) return@LaunchedEffect
        testing = true
        testStatus = null
        delay(800)
        val portAfter = portText.trim().toIntOrNull()
        if (host.trim().isNotEmpty() && portAfter != null && portAfter in 1..65535 &&
            username.isNotEmpty() && password.isNotEmpty() && credsModified
        ) {
            testStatus = testProxy(host.trim(), portAfter, username.trim(), password.trim())
        }
        testing = false
    }

    // Validation
    val hostValid = host.trim().isNotEmpty()
    val portValid = portText.trim().toIntOrNull()?.let { it in 1..65535 } ?: false
    val allFieldsFilled = hostValid && portValid && username.isNotEmpty() && password.isNotEmpty() && name.trim().isNotEmpty()
    val testPassed = testStatus?.startsWith("✓") == true
    // Editing an existing profile whose credentials were left untouched (e.g. a
    // pure rename) should save without re-running the connectivity test — the
    // proxy was presumably already reachable. Only a credential edit triggers the
    // mandatory test gate.
    val allValid = allFieldsFilled && (testPassed || (isEdit && !credsModified))

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(topStart = 14.dp, topEnd = 14.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 18.dp)
                .padding(bottom = 24.dp)
                .imePadding()
        ) {
            Text(
                text = if (isEdit) "Edit Proxy" else "Add Proxy",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(vertical = 14.dp)
            )

            // Copy / Paste connection string (works for both Custom and OwlProxy)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(
                    onClick = {
                        val text = copyProxyString()
                        if (text.isNotEmpty()) {
                            clipboardManager.setText(AnnotatedString(text))
                            android.widget.Toast.makeText(context, "Copied to clipboard", android.widget.Toast.LENGTH_SHORT).show()
                        }
                    },
                    enabled = host.trim().isNotEmpty() || portText.trim().isNotEmpty(),
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(8.dp),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
                ) {
                    Text("Copy", fontSize = 13.sp)
                }
                OutlinedButton(
                    onClick = {
                        val clip = clipboardManager.getText()?.text
                        if (clip != null && applyProxyString(clip)) {
                            android.widget.Toast.makeText(context, "Pasted from clipboard", android.widget.Toast.LENGTH_SHORT).show()
                        } else {
                            android.widget.Toast.makeText(context, "Clipboard has no valid proxy string", android.widget.Toast.LENGTH_SHORT).show()
                        }
                    },
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(8.dp),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
                ) {
                    Text("Paste", fontSize = 13.sp)
                }
            }

            // Profile Name
            FormField(
                label = "Profile Name",
                value = name,
                onValueChange = { name = it },
                placeholder = "e.g. My Proxy"
            )

            // Server details (Host : Port)
            Text(
                text = "Server",
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 14.dp, bottom = 4.dp)
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Column(modifier = Modifier.weight(3f)) {
                    OutlinedTextField(
                        value = host,
                        onValueChange = { newValue ->
                            if (applyProxyString(newValue)) {
                                // full connection string parsed and applied
                            } else {
                                credsModified = true
                                host = newValue
                                detectFromUsername(username)
                            }
                        },
                        label = { Text("Host") },
                        placeholder = { Text("proxy.example.com") },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(8.dp),
                        singleLine = true,
                        isError = host.isNotEmpty() && !hostValid
                    )
                }
                Column(modifier = Modifier.weight(1f)) {
                    OutlinedTextField(
                        value = portText,
                        onValueChange = {
                            credsModified = true
                            portText = it
                        },
                        label = { Text("Port") },
                        placeholder = { Text("1080") },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(8.dp),
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        isError = portText.isNotEmpty() && !portValid
                    )
                }
            }

            // Credentials : Username : Password
            Text(
                text = "Credentials",
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 14.dp, bottom = 4.dp)
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Column(modifier = Modifier.weight(3f)) {
                    OutlinedTextField(
                        value = username,
                        onValueChange = {
                            credsModified = true
                            onUsernameEdit(it)
                        },
                        label = { Text("Username") },
                        placeholder = { Text(if (provider == "owl") "Auto-generated" else "user") },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(8.dp),
                        singleLine = true
                    )
                }
                Column(modifier = Modifier.weight(1.8f)) {
                    PasswordFieldInline(
                        value = password,
                        onValueChange = {
                            credsModified = true
                            password = it
                        },
                        label = "Password",
                        placeholder = "pass",
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }

            // ── Provider Extras (country picker; IP mode only for OwlProxy) ──
            if (proxyType == ProxyProviders.TYPE_OWL ||
                proxyType == ProxyProviders.TYPE_RAPID ||
                proxyType == ProxyProviders.TYPE_CLIP ||
                proxyType == ProxyProviders.TYPE_GENERIC
            ) {
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = ProxyProviders.label(proxyType) + " Settings",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(bottom = 8.dp)
                )

                // Country : IP Mode row (single line)
                Text(
                    text = "Region",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 4.dp)
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    // Country (wider)
                    Column(modifier = Modifier.weight(if (proxyType == ProxyProviders.TYPE_OWL) 2f else 1f)) {
                        ExposedDropdownMenuBox(
                            expanded = countryMenuExpanded,
                            onExpandedChange = {
                                showCountryDropdown = true
                                countryMenuExpanded = false
                            }
                        ) {
                            OutlinedTextField(
                                value = if (selectedCountry != null) {
                                    "${selectedCountry!!.flag} ${selectedCountry!!.name}"
                                } else {
                                    "Country"
                                },
                                onValueChange = {},
                                readOnly = true,
                                singleLine = true,
                                maxLines = 1,
                                trailingIcon = {
                                    ExposedDropdownMenuDefaults.TrailingIcon(expanded = countryMenuExpanded)
                                },
                                modifier = Modifier
                                    .menuAnchor(MenuAnchorType.PrimaryNotEditable, enabled = true)
                                    .fillMaxWidth(),
                                shape = RoundedCornerShape(8.dp),
                                textStyle = MaterialTheme.typography.bodyLarge.copy(
                                    color = if (selectedCountry != null) MaterialTheme.colorScheme.onSurface
                                    else MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            )
                        }
                    }
                    // IP Mode dropdown (narrower)
                    if (proxyType == ProxyProviders.TYPE_OWL) {
                        Column(modifier = Modifier.weight(1f)) {
                            ExposedDropdownMenuBox(
                                expanded = ipModeMenuExpanded,
                                onExpandedChange = { ipModeMenuExpanded = !ipModeMenuExpanded }
                            ) {
                                OutlinedTextField(
                                    value = if (owlMode == "sticky") "Sticky" else "Unique",
                                    onValueChange = {},
                                    readOnly = true,
                                    singleLine = true,
                                    trailingIcon = {
                                        ExposedDropdownMenuDefaults.TrailingIcon(expanded = ipModeMenuExpanded)
                                    },
                                    modifier = Modifier
                                        .menuAnchor(MenuAnchorType.PrimaryNotEditable, enabled = true)
                                        .fillMaxWidth(),
                                    shape = RoundedCornerShape(8.dp)
                                )
                                ExposedDropdownMenu(
                                    expanded = ipModeMenuExpanded,
                                    onDismissRequest = { ipModeMenuExpanded = false }
                                ) {
                                    DropdownMenuItem(
                                        text = { Text("Unique") },
                                        onClick = {
                                            owlMode = "unique"
                                            ipModeMenuExpanded = false
                                            syncUsernameFromUi()
                                        }
                                    )
                                    DropdownMenuItem(
                                        text = { Text("Sticky") },
                                        onClick = {
                                            owlMode = "sticky"
                                            ipModeMenuExpanded = false
                                            syncUsernameFromUi()
                                        }
                                    )
                                }
                            }
                        }
                    }
                }

                // Time selector (only for sticky mode)
                if (proxyType == ProxyProviders.TYPE_OWL && owlMode == "sticky") {
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = "IP Stick Time (minutes)",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 4.dp)
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        listOf(5, 10, 15, 30, 60, 90).forEach { t ->
                            OutlinedButton(
                                onClick = {
                                    owlTime = t
                                    syncUsernameFromUi()
                                },
                                modifier = Modifier.weight(1f),
                                shape = RoundedCornerShape(8.dp),
                                border = BorderStroke(
                                    1.dp,
                                    if (owlTime == t) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.outline
                                )
                            ) {
                                Text(
                                    "${t}m",
                                    fontSize = 11.sp,
                                    color = if (owlTime == t) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }

            // Single test status line
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                when {
                    testing -> {
                        CircularProgressIndicator(
                            modifier = Modifier.size(14.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "Testing proxy…",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    testPassed -> {
                        Text(
                            text = "✓",
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.tertiary
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "Proxy is valid — ready to save",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.tertiary
                        )
                    }
                    testStatus != null -> {
                        Text(
                            text = "✗",
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.error
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "Proxy test failed",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                    else -> {
                        Text(
                            text = "Fill all required fields to test",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Cancel / Save buttons
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                TextButton(
                    onClick = {
                        scope.launch { sheetState.hide(); onDismiss() }
                    },
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text("Cancel", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                OutlinedButton(
                    onClick = {
                        val port = portText.trim().toIntOrNull()
                        if (port != null && port in 1..65535 &&
                            host.trim().isNotEmpty() && username.trim().isNotEmpty() && password.isNotEmpty()
                        ) {
                            credsModified = true
                            testing = true
                            testStatus = null
                            scope.launch {
                                testStatus = testProxy(
                                    host.trim(),
                                    port,
                                    username.trim(),
                                    password.trim()
                                )
                                testing = false
                            }
                        }
                    },
                    enabled = !testing && allFieldsFilled,
                    shape = RoundedCornerShape(8.dp),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
                ) {
                    Text(if (testing) "Testing…" else "Test")
                }
                Button(
                    onClick = {
                        val savedName = saveProfile(
                            context = context,
                            profileName = profileName,
                            newName = name.trim(),
                            host = host.trim(),
                            portText = portText.trim(),
                            username = username.trim(),
                            password = password.trim()
                        )
                        if (savedName != null && profileName != null && savedName != profileName) {
                            onProfileRenamed(profileName, savedName)
                        }
                        scope.launch {
                            sheetState.hide()
                            onSaved()
                        }
                    },
                    enabled = allValid,
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text("Save")
                }
            }

            Spacer(modifier = Modifier.height(8.dp))
        }
    }

    // ── Country Selection Dialog ──
    if (showCountryDropdown) {
        val filteredCountries = remember(countrySearch) {
            if (countrySearch.isEmpty()) {
                Countries.ALL
            } else {
                val query = countrySearch.lowercase()
                val digits = countrySearch.filter { it.isDigit() }
                Countries.ALL.filter { country ->
                    country.name.lowercase().contains(query) ||
                    country.code.lowercase().contains(query) ||
                    (digits.isNotEmpty() && (country.phone.startsWith(digits) || digits.startsWith(country.phone)))
                }
            }
        }
        val recentCountryList = remember(recentCountries) {
            recentCountries.mapNotNull { code -> Countries.ALL.find { it.code == code } }
        }

        AlertDialog(
            onDismissRequest = {
                showCountryDropdown = false
                countrySearch = ""
            },
            title = { Text("Select Country") },
            text = {
                Column(
                    modifier = Modifier.imePadding()
                ) {
                    // Search field
                    OutlinedTextField(
                        value = countrySearch,
                        onValueChange = { countrySearch = it },
                        placeholder = { Text("Search countries...") },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(8.dp),
                        singleLine = true
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    // Country list
                    LazyColumn(
                        modifier = Modifier.heightIn(max = 300.dp)
                    ) {
                        val showRecents = countrySearch.isEmpty() && recentCountryList.isNotEmpty()
                        if (showRecents) {
                            item {
                                Text(
                                    text = "Recently Used",
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.padding(top = 8.dp, bottom = 4.dp)
                                )
                            }
                            items(recentCountryList) { country ->
                                OutlinedButton(
                                    onClick = {
                                        selectedCountry = country
                                        syncUsernameFromUi()
                                        Utility.addRecentCountry(context, country.code)
                                        recentCountries = Utility.getRecentCountries(context)
                                        showCountryDropdown = false
                                        countrySearch = ""
                                    },
                                    modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                                    shape = RoundedCornerShape(8.dp),
                                    border = BorderStroke(
                                        1.dp,
                                        if (selectedCountry?.code == country.code) MaterialTheme.colorScheme.primary
                                        else MaterialTheme.colorScheme.outline
                                    )
                                ) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            text = country.flag,
                                            fontSize = 18.sp,
                                            modifier = Modifier.padding(end = 8.dp)
                                        )
                                        Text(
                                            text = country.name,
                                            modifier = Modifier.weight(1f)
                                        )
                                        Text(
                                            text = country.code,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            fontSize = 12.sp
                                        )
                                    }
                                }
                            }
                            item {
                                Text(
                                    text = "All Countries",
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.padding(top = 8.dp, bottom = 4.dp)
                                )
                            }
                        }
                        items(filteredCountries) { country ->
                            OutlinedButton(
                                onClick = {
                                    selectedCountry = country
                                    syncUsernameFromUi()
                                    Utility.addRecentCountry(context, country.code)
                                    recentCountries = Utility.getRecentCountries(context)
                                    showCountryDropdown = false
                                    countrySearch = ""
                                },
                                modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                                shape = RoundedCornerShape(8.dp),
                                border = BorderStroke(
                                    1.dp,
                                    if (selectedCountry?.code == country.code) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.outline
                                )
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = country.flag,
                                        fontSize = 18.sp,
                                        modifier = Modifier.padding(end = 8.dp)
                                    )
                                    Text(
                                        text = country.name,
                                        modifier = Modifier.weight(1f)
                                    )
                                    Text(
                                        text = country.code,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        fontSize = 12.sp
                                    )
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showCountryDropdown = false
                        countrySearch = ""
                    }
                ) {
                    Text("Cancel")
                }
            }
        )
    }
}

@Composable
private fun FormField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    enabled: Boolean = true
) {
    Text(
        text = label,
        fontSize = 12.sp,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 14.dp, bottom = 4.dp)
    )
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        placeholder = { Text(placeholder) },
        enabled = enabled,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        singleLine = true
    )
}

@Composable
private fun PasswordFieldInline(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    label: String = "Password",
    modifier: Modifier = Modifier
) {
    var visible by remember { mutableStateOf(false) }

    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        placeholder = { Text(placeholder) },
        modifier = modifier,
        label = { Text(label) },
        shape = RoundedCornerShape(8.dp),
        singleLine = true,
        visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        trailingIcon = {
            IconButton(onClick = { visible = !visible }) {
                Icon(
                    painter = painterResource(
                        if (visible) R.drawable.lucide_eye_off else R.drawable.lucide_eye
                    ),
                    contentDescription = if (visible) "Hide password" else "Show password",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    )
}

private fun parseProxyString(input: String): List<String>? {
    val trimmed = input.trim()
    if (trimmed.isEmpty()) return null
    val parts = trimmed.split(":")
    if (parts.size < 2) return null
    val host = parts[0].trim()
    val port = parts[1].trim()
    if (host.isEmpty() || port.toIntOrNull() !in 1..65535) return null
    return listOf(
        host,
        port,
        if (parts.size >= 3) parts[2].trim() else "",
        if (parts.size >= 4) parts[3].trim() else ""
    )
}

private fun saveProfile(
    context: android.content.Context,
    profileName: String?,
    newName: String,
    host: String,
    portText: String,
    username: String,
    password: String
): String? {
    try {
        val pm = ProfileManager.getInstance(context)
        val port = portText.toIntOrNull() ?: 1080

        if (profileName != null) {
            // Edit existing profile
            var effectiveName = profileName
            if (newName.isNotEmpty() && newName != profileName) {
                if (pm.renameProfile(profileName, newName)) {
                    effectiveName = newName
                }
            }
            val profile = pm.getProfile(effectiveName) ?: return null
            profile.setServer(host)
            profile.setPort(port)
            profile.setIsUserpw(true)
            profile.setUsername(username)
            profile.setPassword(password)
            return effectiveName
        } else {
            // Add new profile
            val profile = pm.addProfile(newName) ?: return null
            profile.setServer(host)
            profile.setPort(port)
            profile.setIsUserpw(true)
            profile.setUsername(username)
            profile.setPassword(password)
            return newName
        }
    } catch (_: Exception) {
        return null
    }
}

private suspend fun testProxy(
    server: String,
    port: Int,
    username: String,
    password: String
): String = SocksTester.testProxy(server, port, username, password)
