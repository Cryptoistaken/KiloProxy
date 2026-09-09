package net.typeblog.socks.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign

import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import net.typeblog.socks.R
import net.typeblog.socks.ui.components.ProfileDetailSheet
import net.typeblog.socks.ui.components.ProxyCard
import net.typeblog.socks.ui.components.SearchInput
import net.typeblog.socks.ui.viewmodel.ProxyDraft
import net.typeblog.socks.ui.viewmodel.VpnViewModel
import net.typeblog.socks.util.Countries
import net.typeblog.socks.util.ProfileManager
import net.typeblog.socks.util.ProxyProviders
import net.typeblog.socks.util.SocksTester

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProxiesScreen(
    modifier: Modifier = Modifier,
    viewModel: VpnViewModel,
    pickMode: Boolean = false,
    onPickProfile: ((String) -> Unit)? = null,
    onPickCountryClick: () -> Unit = {}
) {
    val context = LocalContext.current
    val profiles by viewModel.profiles.collectAsState()
    val profileVersion by viewModel.profileVersion.collectAsState()
    val isRunning by viewModel.isRunning.collectAsState()
    val activeProfileName by viewModel.activeProfileName.collectAsState()
    val lastProfileName by viewModel.lastProfileName.collectAsState()
    val receivedBytes by viewModel.receivedBytes.collectAsState()
    val sentBytes by viewModel.sentBytes.collectAsState()

    // Sheet controls are saveable so an open sheet survives the round-trip
    // to the Countries tab for country picking.
    var showAddSheet by rememberSaveable { mutableStateOf(false) }
    var selectedProvider by rememberSaveable { mutableStateOf<String?>(null) }
    var editTargetProfile by rememberSaveable { mutableStateOf<String?>(null) }
    var deleteTargets by rememberSaveable { mutableStateOf(listOf<String>()) }
    var detailTarget by rememberSaveable { mutableStateOf<String?>(null) }
    var duplicateTarget by rememberSaveable { mutableStateOf<String?>(null) }
    var selecting by rememberSaveable { mutableStateOf(false) }
    var selected by rememberSaveable { mutableStateOf(listOf<String>()) }
    var profileSearch by rememberSaveable { mutableStateOf("") }

    // Proxy auto-sync is paused — proxies are managed manually on this screen.
    val scope = rememberCoroutineScope()

    val filteredProfiles = remember(profiles, profileSearch) {
        val q = profileSearch.trim().lowercase()
        if (q.isEmpty()) profiles.toList()
        else profiles.filter { it.lowercase().contains(q) }
    }

    Scaffold(
        modifier = modifier,
        floatingActionButton = {
            if (!pickMode && !selecting) {
                IconButton(
                    onClick = {
                        selectedProvider = "custom"
                        showAddSheet = true
                    },
                    modifier = Modifier.size(56.dp)
                ) {
                    Icon(
                        painter = painterResource(R.drawable.fab_stack),
                        contentDescription = "Add proxy",
                        modifier = Modifier.size(42.dp),
                        tint = MaterialTheme.colorScheme.onSurface
                    )
                }
            }
        },
        bottomBar = {
            if (selecting && !pickMode) {
                BulkBar(
                    count = selected.size,
                    onSelectAll = {
                        selected =
                            if (filteredProfiles.isNotEmpty() && selected.size == filteredProfiles.size) {
                                emptyList()
                            } else {
                                filteredProfiles
                            }
                    },
                    onDelete = { deleteTargets = selected.toList() },
                    onDone = {
                        selecting = false
                        selected = emptyList()
                    }
                )
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
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(horizontal = 16.dp)
            ) {
                if (pickMode) {
                    Text(
                        text = "Select a profile",
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.padding(bottom = 8.dp)
                    )
                } else {
                    Text(
                        text = "Profiles",
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.padding(bottom = 8.dp)
                    )
                }
                SearchInput(
                    value = profileSearch,
                    onValueChange = { profileSearch = it },
                    placeholder = "Search profiles",
                    description = "Search profiles",
                    modifier = Modifier.padding(bottom = 8.dp)
                )
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    items(filteredProfiles, key = { it }) { profileName ->
                        val pm = remember { ProfileManager.getInstance(context) }
                        val profile = remember(profileName, profileVersion) { pm.getProfile(profileName) }
                        ProxyCard(
                            profileName = profileName,
                            server = profile?.getServer() ?: "",
                            username = profile?.getUsername() ?: "",
                            password = profile?.getPassword() ?: "",
                            isConnected = isRunning && activeProfileName == profileName,
                            liveUsageRx = if (lastProfileName == profileName) receivedBytes else 0L,
                            liveUsageTx = if (lastProfileName == profileName) sentBytes else 0L,
                        onSelect = if (pickMode) {
                            { onPickProfile?.invoke(profileName) }
                        } else if (selecting) {
                            {
                                selected = if (selected.contains(profileName)) {
                                    selected - profileName
                                } else {
                                    selected + profileName
                                }
                            }
                        } else {
                            { detailTarget = profileName }
                        },
                        selectionMode = selecting && !pickMode,
                        checked = selected.contains(profileName)
                        )
                    }
                    // Bottom spacer for FAB
                    item { Spacer(modifier = Modifier.height(72.dp)) }
                }
            }
        }
    }

    // ── Delete Confirmation (single or bulk) ──
    if (deleteTargets.isNotEmpty()) {
        val targets = deleteTargets
        AlertDialog(
            onDismissRequest = { deleteTargets = emptyList() },
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
            title = {
                Text(
                    if (targets.size == 1) "Delete profile?"
                    else "Delete ${targets.size} profiles?"
                )
            },
            text = {
                Text(
                    if (targets.size == 1) "Remove profile \"${targets[0]}\"? This cannot be undone."
                    else "Remove ${targets.size} profiles? This cannot be undone."
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val pm = ProfileManager.getInstance(context)
                        if (isRunning && targets.contains(activeProfileName)) {
                            viewModel.stopVpn(context)
                        }
                        targets.forEach { pm.removeProfile(it) }
                        viewModel.reloadProfiles(context)
                        val remaining = selected.filterNot { targets.contains(it) }
                        selected = remaining
                        if (remaining.isEmpty()) selecting = false
                        deleteTargets = emptyList()
                    },
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.error
                    )
                ) {
                    Text("Delete")
                }
            },
            dismissButton = {
                TextButton(onClick = { deleteTargets = emptyList() }) {
                    Text("Cancel")
                }
            }
        )
    }

    // ── Detail Sheet (tap card) ──
    detailTarget?.let { target ->
        val pm = remember { ProfileManager.getInstance(context) }
        val detailProfile = remember(target, profileVersion) { pm.getProfile(target) }
        detailProfile?.let { profile ->
            ProfileDetailSheet(
                profileName = target,
                server = profile.getServer(),
                port = profile.getPort(),
                username = profile.getUsername(),
                password = profile.getPassword(),
                isConnected = isRunning && activeProfileName == target,
                liveUsageRx = if (lastProfileName == target) receivedBytes else 0L,
                liveUsageTx = if (lastProfileName == target) sentBytes else 0L,
                onEdit = {
                    detailTarget = null
                    editTargetProfile = target
                },
                onDuplicate = {
                    detailTarget = null
                    duplicateTarget = target
                },
                onSelectMode = {
                    detailTarget = null
                    selected = emptyList()
                    selecting = true
                },
                onDelete = {
                    detailTarget = null
                    deleteTargets = listOf(target)
                },
                onDismiss = { detailTarget = null }
            )
        }
    }

    // ── Duplicate Rename ──
    duplicateTarget?.let { target ->
        val pm = remember { ProfileManager.getInstance(context) }
        var newName by remember(target) { mutableStateOf(firstFreeCopyName(pm, target)) }
        val trimmed = newName.trim()
        val nameTaken = remember(trimmed) { trimmed.isNotEmpty() && pm.getProfile(trimmed) != null }
        AlertDialog(
            onDismissRequest = { duplicateTarget = null },
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
            title = { Text("Duplicate profile?") },
            text = {
                OutlinedTextField(
                    value = newName,
                    onValueChange = { newName = it },
                    label = { Text("Name") },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(8.dp),
                    singleLine = true,
                    isError = trimmed.isEmpty() || nameTaken
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        if (duplicateProfileAs(pm, target, trimmed)) {
                            viewModel.reloadProfiles(context)
                            android.widget.Toast.makeText(
                                context, "Duplicated as \"$trimmed\"", android.widget.Toast.LENGTH_SHORT
                            ).show()
                        }
                        duplicateTarget = null
                    },
                    enabled = trimmed.isNotEmpty() && !nameTaken
                ) {
                    Text("Create")
                }
            },
            dismissButton = {
                TextButton(onClick = { duplicateTarget = null }) {
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
                viewModel = viewModel,
                onPickCountryClick = onPickCountryClick,
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
                viewModel = viewModel,
                onPickCountryClick = onPickCountryClick,
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
    viewModel: VpnViewModel,
    onPickCountryClick: () -> Unit = {},
    onProfileRenamed: (oldName: String, newName: String) -> Unit = { _, _ -> },
    initialName: String = ""
) {
    val context = LocalContext.current
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    val isEdit = profileName != null

    // Form state
    var name by remember { mutableStateOf(profileName ?: initialName) }
    var nameTouched by remember { mutableStateOf(false) }
    var host by remember { mutableStateOf("") }
    var portText by remember { mutableStateOf("") }
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var isDefault by remember { mutableStateOf(false) }
    var testing by remember { mutableStateOf(false) }
    var testStatus by remember { mutableStateOf<String?>(null) }
    var testFailedFlash by remember { mutableStateOf(false) }
    var credsModified by remember { mutableStateOf(false) }

    // Provider state (country picker for owl/rapid/clip/generic; IP mode owl-only)
    var proxyType by remember { mutableStateOf(if (provider == "owl") ProxyProviders.TYPE_OWL else ProxyProviders.TYPE_CUSTOM) }
    var selectedCountry by remember { mutableStateOf<Countries.Country?>(null) }
    var owlMode by remember { mutableStateOf("unique") }
    var owlTime by remember { mutableStateOf(5) }
    var ipdeepMode by remember { mutableStateOf("unique") }
    var ipdeepTime by remember { mutableStateOf(5) }
    var syncing by remember { mutableStateOf(false) }
    var ipModeMenuExpanded by remember { mutableStateOf(false) }

    // Snapshot/restore the whole draft so a country-pick round-trip through
    // the Countries tab returns to the sheet untouched.
    fun snapshotDraft(): ProxyDraft = ProxyDraft(
        profileName = profileName,
        provider = provider,
        initialName = initialName,
        name = name,
        host = host,
        portText = portText,
        username = username,
        password = password,
        isDefault = isDefault,
        credsModified = credsModified,
        proxyType = proxyType,
        countryCode = selectedCountry?.code,
        owlMode = owlMode,
        owlTime = owlTime,
        ipdeepMode = ipdeepMode,
        ipdeepTime = ipdeepTime
    )

    fun restoreDraft(d: ProxyDraft) {
        name = d.name
        host = d.host
        portText = d.portText
        username = d.username
        password = d.password
        isDefault = d.isDefault
        credsModified = d.credsModified
        proxyType = d.proxyType
        selectedCountry = d.countryCode?.let { code -> Countries.fromCode(code) }
        owlMode = d.owlMode
        owlTime = d.owlTime
        ipdeepMode = d.ipdeepMode
        ipdeepTime = d.ipdeepTime
    }

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
        if (t == ProxyProviders.TYPE_IPDEEP) {
            if (ProxyProviders.isIpDeepSticky(newVal)) {
                ipdeepMode = "sticky"
                ipdeepTime = ProxyProviders.parseIpDeepTime(newVal) ?: 5
            } else {
                ipdeepMode = "unique"
            }
        }
        // Adding (not editing) + pasted/typed a known provider + user never
        // touched the name: rename to "<Provider> <n>" with a free number.
        if (!isEdit && !nameTouched && proxyType != ProxyProviders.TYPE_CUSTOM) {
            name = freshProfileName(
                ProfileManager.getInstance(context),
                ProxyProviders.label(proxyType)
            )
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

    // Restore the draft snapshotted before leaving to the Countries tab.
    // Declared after the load above so it wins on the way back.
    // (Placed after syncUsernameFromUi below: local funs must be declared
    // before use, and the picked-country effect calls it.)
    val pendingDraft by viewModel.pendingDraft.collectAsState()
    val pickedCountry by viewModel.pickedCountry.collectAsState()

    // Sync username from provider UI state (country / IP mode).
    fun syncUsernameFromUi() {
        if (syncing || selectedCountry == null) return
        val t = proxyType
        if (t != ProxyProviders.TYPE_OWL &&
            t != ProxyProviders.TYPE_RAPID &&
            t != ProxyProviders.TYPE_CLIP &&
            t != ProxyProviders.TYPE_IPDEEP &&
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
        if (t == ProxyProviders.TYPE_IPDEEP) {
            val base = ProxyProviders.extractBase(username, t) ?: return
            if (base.isEmpty()) return
            val full = ProxyProviders.buildIpDeep(
                base, selectedCountry!!.code, ipdeepMode, ipdeepTime,
                ProxyProviders.ipdeepSessionId(username)
            )
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

    LaunchedEffect(pendingDraft) {
        val d = pendingDraft
        if (d != null && d.profileName == profileName && d.provider == provider &&
            d.initialName == initialName
        ) {
            restoreDraft(d)
            viewModel.setPendingDraft(null)
        }
    }

    // Country picked on the Countries tab: apply to the draft, then consume.
    // Declared after the restore above so it wins over the snapshotted value.
    LaunchedEffect(pickedCountry) {
        val code = pickedCountry
        if (code != null) {
            selectedCountry = Countries.fromCode(code)
            syncUsernameFromUi()
            viewModel.pickCountry(null)
        }
    }

    fun onUsernameEdit(newVal: String) {
        username = newVal
        detectFromUsername(newVal)
    }

    // Hoisted IP-mode state shared by the name section below.
    val hasIpMode = proxyType == ProxyProviders.TYPE_OWL ||
        proxyType == ProxyProviders.TYPE_IPDEEP
    val curMode = if (proxyType == ProxyProviders.TYPE_IPDEEP) ipdeepMode else owlMode
    val curTime = if (proxyType == ProxyProviders.TYPE_IPDEEP) ipdeepTime else owlTime
    fun setMode(m: String) {
        if (proxyType == ProxyProviders.TYPE_IPDEEP) ipdeepMode = m else owlMode = m
        ipModeMenuExpanded = false
        syncUsernameFromUi()
    }
    fun setTime(t: Int) {
        if (proxyType == ProxyProviders.TYPE_IPDEEP) ipdeepTime = t else owlTime = t
        syncUsernameFromUi()
    }
    fun runManualTest() {
        if (testing) return
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
                if (testStatus != SocksTester.TEST_OK) {
                    testFailedFlash = true
                }
            }
        } else {
            testFailedFlash = true
        }
    }

    // Apply a host:port:user:pass (or host:port) connection string to the form.

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
        // Pasting always starts in Unique mode even when the pasted
        // credential carries a sticky suffix (Owl _time_ / IpDeep -session-).
        // The parsed stick time is kept so switching to Sticky restores it.
        if (proxyType == ProxyProviders.TYPE_IPDEEP) {
            ipdeepMode = "unique"
            val base = ProxyProviders.extractBase(username, proxyType)
            val cc = selectedCountry?.code
            if (base != null && cc != null && base.isNotEmpty()) {
                val full = ProxyProviders.buildIpDeep(
                    base, cc, "unique", ipdeepTime,
                    ProxyProviders.ipdeepSessionId(username)
                )
                syncing = true
                username = full
                syncing = false
            }
        }
        if (proxyType == ProxyProviders.TYPE_OWL) {
            owlMode = "unique"
            val m = Regex("^(.+?)_custom_zone_([a-zA-Z]{2})(.*)$").find(username)
            if (m != null) {
                val base = m.groupValues[1]
                val cc = m.groupValues[2]
                if (base.isNotEmpty()) {
                    val full = ProxyProviders.buildUsername(
                        base, proxyType, cc, "unique", owlTime
                    )
                    if (full != null) {
                        syncing = true
                        username = full
                        syncing = false
                    }
                }
            }
        }
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

    // Manual-test failure shows red Failed on the Test button for 3s.
    LaunchedEffect(testFailedFlash) {
        if (testFailedFlash) {
            delay(3000)
            testFailedFlash = false
        }
    }

    // Validation
    val hostValid = host.trim().isNotEmpty()
    val portValid = portText.trim().toIntOrNull()?.let { it in 1..65535 } ?: false
    val allFieldsFilled = hostValid && portValid && username.isNotEmpty() && password.isNotEmpty() && name.trim().isNotEmpty()
    val testPassed = testStatus == SocksTester.TEST_OK
    // Editing an existing profile whose credentials were left untouched (e.g. a
    // pure rename) should save without re-running the connectivity test — the
    // proxy was presumably already reachable. Only a credential edit triggers the
    // mandatory test gate.
    val allValid = allFieldsFilled && (testPassed || (isEdit && !credsModified))

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
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
                modifier = Modifier.padding(top = 14.dp, bottom = 10.dp)
            )

            // Scrollable fields; status line + action buttons stay pinned
            // below so extra rows (e.g. sticky time chips) can never push
            // the buttons off-sheet or make them unclickable.
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f, fill = false)
                    .verticalScroll(rememberScrollState())
            ) {

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

            // Server details (Host : Port) - no grouping header, fields carry own names
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

            // Credentials : Username : Password - no grouping header
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
            Spacer(modifier = Modifier.height(8.dp))
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

            // ── Country picker only - no grouping header
            val showCountry = proxyType == ProxyProviders.TYPE_OWL ||
                proxyType == ProxyProviders.TYPE_RAPID ||
                proxyType == ProxyProviders.TYPE_CLIP ||
                proxyType == ProxyProviders.TYPE_IPDEEP ||
                proxyType == ProxyProviders.TYPE_GENERIC
            if (showCountry) {
                Spacer(modifier = Modifier.height(8.dp))

                // Country, own line - field carries own name
                Text(
                    text = "Country",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 4.dp)
                )
                // Country (own line) - tapping slides the sheet down first,
                // then opens the Countries tab; the draft is snapshotted
                // so the sheet restores untouched on return.
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .semantics {
                            contentDescription = "Country, ${selectedCountry?.name ?: "none selected"}"
                        }
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            role = Role.Button,
                            onClickLabel = "Select country",
                            onClick = {
                                viewModel.setPendingDraft(snapshotDraft())
                                scope.launch {
                                    try { sheetState.hide() } catch (_: Exception) { }
                                    onPickCountryClick()
                                }
                            }
                        )
                ) {
                    OutlinedTextField(
                        value = if (selectedCountry != null) {
                            "${selectedCountry!!.flag} ${selectedCountry!!.name}"
                        } else {
                            "Country"
                        },
                        onValueChange = {},
                        readOnly = true,
                        enabled = false,
                        singleLine = true,
                        maxLines = 1,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(8.dp),
                        // Disabled fields are greyed out by default; the tap
                        // target is the parent Box, so keep full-contrast colors.
                        colors = OutlinedTextFieldDefaults.colors(
                            disabledTextColor = MaterialTheme.colorScheme.onSurface,
                            disabledBorderColor = MaterialTheme.colorScheme.outline,
                            disabledPlaceholderColor = MaterialTheme.colorScheme.onSurfaceVariant
                        ),
                        textStyle = MaterialTheme.typography.bodyLarge.copy(
                            color = if (selectedCountry != null) MaterialTheme.colorScheme.onSurface
                            else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    )
                }
            }

            FormField(
                label = "Profile Name",
                value = name,
                onValueChange = {
                    name = it
                    nameTouched = true
                },
                placeholder = "e.g. My Proxy"
            )

                // IP Mode dropdown (own line) — Owl and IpDeep
                if (hasIpMode) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "IP Mode",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 4.dp)
                    )
                    ExposedDropdownMenuBox(
                        expanded = ipModeMenuExpanded,
                        onExpandedChange = { ipModeMenuExpanded = !ipModeMenuExpanded }
                    ) {
                            OutlinedTextField(
                                value = if (curMode == "sticky") "Sticky" else "Unique",
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
                                        text = {
                                            Column {
                                                Text("Unique")
                                                Text(
                                                    text = "Fresh IP on every connection.",
                                                    style = MaterialTheme.typography.bodySmall,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                                )
                                            }
                                        },
                                        onClick = { setMode("unique") }
                                    )
                                    DropdownMenuItem(
                                        text = {
                                            Column {
                                                Text("Sticky")
                                                Text(
                                                    text = "Keeps the same IP for the stick time.",
                                                    style = MaterialTheme.typography.bodySmall,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                                )
                                            }
                                        },
                                        onClick = { setMode("sticky") }
                                    )
                        }
                    }
                }

                // Time selector (only for sticky mode)
                if (hasIpMode && curMode == "sticky") {
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = "IP Stick Time in minutes",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 4.dp)
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        listOf(5, 10, 15, 30, 60, 90).forEach { t ->
                            OutlinedButton(
                                onClick = { setTime(t) },
                                modifier = Modifier.weight(1f).height(36.dp),
                                shape = RoundedCornerShape(8.dp),
                                border = BorderStroke(
                                    1.dp,
                                    if (curTime == t) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.outline
                                ),
                                contentPadding = PaddingValues(horizontal = 0.dp, vertical = 0.dp)
                            ) {
                                Text(
                                    "$t",
                                    fontSize = 12.sp,
                                    maxLines = 1,
                                    color = if (curTime == t) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Action buttons, colored by action.
            // Test button keeps Testing on one line (no wrap to second line).
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = {
                        scope.launch {
                            try { sheetState.hide() } catch (_: Exception) { }
                            onDismiss()
                        }
                    },
                    modifier = Modifier.weight(1f).height(42.dp),
                    shape = RoundedCornerShape(8.dp),
                    contentPadding = PaddingValues(horizontal = 2.dp, vertical = 0.dp),
                    // Neutral dismiss action: red is reserved for status.
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant,
                        contentColor = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                ) {
                    Text("Cancel", maxLines = 1, fontSize = 13.sp)
                }
                OutlinedButton(
                    onClick = { runManualTest() },
                    enabled = !testing,
                    modifier = Modifier.weight(1f).height(42.dp),
                    shape = RoundedCornerShape(8.dp),
                    contentPadding = PaddingValues(horizontal = 2.dp, vertical = 0.dp),
                    border = BorderStroke(
                        1.dp,
                        if (testFailedFlash) MaterialTheme.colorScheme.error
                        else MaterialTheme.colorScheme.onSurface
                    )
                ) {
                    if (testing) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(12.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Testing", maxLines = 1, fontSize = 13.sp)
                    } else if (testFailedFlash) {
                        Text("Failed", maxLines = 1, fontSize = 13.sp, color = MaterialTheme.colorScheme.error)
                    } else if (testPassed) {
                        Text("Works", maxLines = 1, fontSize = 13.sp, color = MaterialTheme.colorScheme.tertiary)
                    } else {
                        Text("Test", maxLines = 1, fontSize = 13.sp)
                    }
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
                            try { sheetState.hide() } catch (_: Exception) { }
                            onSaved()
                        }
                    },
                    enabled = allValid,
                    modifier = Modifier.weight(1f).height(42.dp),
                    shape = RoundedCornerShape(8.dp),
                    contentPadding = PaddingValues(horizontal = 2.dp, vertical = 0.dp)
                ) {
                    Text("Save", maxLines = 1, fontSize = 13.sp)
                }
            }

            Spacer(modifier = Modifier.height(8.dp))
        }
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

// Bulk-action bar for multi-select mode: count + Select all + Delete + Done.
@Composable
private fun BulkBar(
    count: Int,
    onSelectAll: () -> Unit,
    onDelete: () -> Unit,
    onDone: () -> Unit
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
        shape = RoundedCornerShape(topStart = 14.dp, topEnd = 14.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = "$count selected",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                modifier = Modifier.weight(1f)
            )
            TextButton(onClick = onSelectAll) {
                Text("Select all", maxLines = 1)
            }
            TextButton(
                onClick = onDelete,
                enabled = count > 0,
                colors = ButtonDefaults.textButtonColors(
                    contentColor = MaterialTheme.colorScheme.error
                )
            ) {
                Text("Delete", maxLines = 1)
            }
            TextButton(onClick = onDone) {
                Text("Done", maxLines = 1)
            }
        }
    }
}

// First free "<base> <n>" name (OwlProxy 1, OwlProxy 2, ...).
private fun freshProfileName(pm: ProfileManager, base: String): String {
    var n = 1
    while (pm.getProfile("$base $n") != null) n++
    return "$base $n"
}

// Duplicate registers the chosen name then clones every stored field via
// Profile.copyTo (internal to this module, so no engine change was needed).
private fun duplicateProfileAs(pm: ProfileManager, srcName: String, newName: String): Boolean {
    if (newName.isBlank() || pm.getProfile(newName) != null) return false
    val src = pm.getProfile(srcName) ?: return false
    if (pm.addProfile(newName) == null) return false
    src.copyTo(newName)
    return true
}

private fun firstFreeCopyName(pm: ProfileManager, srcName: String): String {
    var newName = "$srcName (copy)"
    var n = 2
    while (pm.getProfile(newName) != null) {
        newName = "$srcName (copy $n)"
        n++
    }
    return newName
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
