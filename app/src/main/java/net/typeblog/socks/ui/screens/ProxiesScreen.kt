package net.typeblog.socks.ui.screens

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
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
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.rememberSwipeToDismissBoxState
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
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
import kotlinx.coroutines.Job
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
    val vpnConnected by viewModel.isConnected.collectAsState()
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
    val snack = remember { SnackbarHostState() }
    // Single 5s Undo dismiss timer: a repeat delete cancels the previous
    // timer so it can never dismiss the new snackbar early.
    var undoDismissJob by remember { mutableStateOf<Job?>(null) }

    // Swipe-left delete: immediate, with 5s Undo. Same stop-VPN handling
    // as the confirm dialog when the active profile is removed.
    fun swipeDelete(name: String) {
        val pm = ProfileManager.getInstance(context)
        val p = pm.getProfile(name) ?: return
        val backup = DeletedProfile(name, p.getServer(), p.getPort(), p.getUsername(), p.getPassword())
        if (isRunning && name == activeProfileName) viewModel.stopVpn(context)
        pm.removeProfile(name)
        viewModel.reloadProfiles(context)
        scope.launch {
            undoDismissJob?.cancel()
            undoDismissJob = launch { delay(5000); snack.currentSnackbarData?.dismiss() }
            val r = snack.showSnackbar("Profile deleted", actionLabel = "Undo", duration = SnackbarDuration.Long)
            if (r == SnackbarResult.ActionPerformed) {
                pm.addProfile(backup.name)?.let {
                    it.setServer(backup.server)
                    it.setPort(backup.port)
                    it.setIsUserpw(true)
                    it.setUsername(backup.username)
                    it.setPassword(backup.password)
                }
                viewModel.reloadProfiles(context)
            }
        }
    }

    val filteredProfiles = remember(profiles, profileSearch) {
        val q = profileSearch.trim().lowercase()
        if (q.isEmpty()) profiles.toList()
        else profiles.filter { it.lowercase().contains(q) }
    }

    Scaffold(
        modifier = modifier,
        snackbarHost = { SnackbarHost(hostState = snack) },
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
                    allSelected = filteredProfiles.isNotEmpty() && selected.size == filteredProfiles.size,
                    onSelectAll = {
                        selected =
                            if (filteredProfiles.isNotEmpty() && selected.size == filteredProfiles.size) {
                                emptyList()
                            } else {
                                filteredProfiles
                            }
                    },
                    onDelete = { deleteTargets = selected.toList() },
                    onCancel = {
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
                // Small selected-count at the top of the list while
                // multi-selecting; the page header stays "Profiles".
                if (selecting && !pickMode) {
                    Text(
                        text = "${selected.size} selected",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 8.dp)
                    )
                }
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    items(filteredProfiles, key = { it }) { profileName ->
                        val pm = remember { ProfileManager.getInstance(context) }
                        val profile = remember(profileName, profileVersion) { pm.getProfile(profileName) }
                        val dismissState = rememberSwipeToDismissBoxState(
                            confirmValueChange = { v ->
                                when (v) {
                                    SwipeToDismissBoxValue.StartToEnd -> {
                                        editTargetProfile = profileName
                                        false
                                    }
                                    SwipeToDismissBoxValue.EndToStart -> {
                                        swipeDelete(profileName)
                                        true
                                    }
                                    SwipeToDismissBoxValue.Settled -> false
                                }
                            }
                        )
                        SwipeToDismissBox(
                            state = dismissState,
                            gesturesEnabled = !selecting && !pickMode,
                            backgroundContent = { SwipeActionBg(dismissState.dismissDirection) }
                        ) {
                            ProxyCard(
                                profileName = profileName,
                                server = profile?.getServer() ?: "",
                                username = profile?.getUsername() ?: "",
                                password = profile?.getPassword() ?: "",
                                isConnected = vpnConnected && activeProfileName == profileName,
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
                            onLongPress = if (pickMode) {
                                null
                            } else {
                                {
                                    selecting = true
                                    if (!selected.contains(profileName)) {
                                        selected = selected + profileName
                                    }
                                }
                            },
                            checked = selected.contains(profileName)
                            )
                        }
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
                isConnected = vpnConnected && activeProfileName == target,
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
                                context, "Profile duplicated", android.widget.Toast.LENGTH_SHORT
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

// Square full-bleed swipe hints behind proxy cards: right = Edit on
// surface, left = Delete on error red. Direct commit, no buttons.
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SwipeActionBg(direction: SwipeToDismissBoxValue) {
    if (direction == SwipeToDismissBoxValue.Settled) return
    val fromStart = direction == SwipeToDismissBoxValue.StartToEnd
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(if (fromStart) MaterialTheme.colorScheme.surfaceContainerLow else MaterialTheme.colorScheme.error)
            .padding(horizontal = 20.dp),
        contentAlignment = if (fromStart) Alignment.CenterStart else Alignment.CenterEnd
    ) {
        Icon(
            painter = painterResource(if (fromStart) R.drawable.ic_sheet_edit else R.drawable.ic_sheet_delete),
            contentDescription = null,
            tint = if (fromStart) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onError
        )
    }
}

// Field snapshot for swipe-delete Undo (restores server creds as-is).
private data class DeletedProfile(
    val name: String,
    val server: String,
    val port: Int,
    val username: String,
    val password: String
)

// Bulk-action bar for multi-select mode: Cancel + Select all/Unselect all +
// Delete. Three equal buttons; Cancel neutral outline, Select all solid
// primary (black/white per theme), Delete solid error red.
@Composable
private fun BulkBar(
    count: Int,
    allSelected: Boolean,
    onSelectAll: () -> Unit,
    onDelete: () -> Unit,
    onCancel: () -> Unit
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
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            OutlinedButton(
                onClick = onCancel,
                modifier = Modifier.weight(1f).height(44.dp),
                shape = RoundedCornerShape(8.dp),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                contentPadding = PaddingValues(horizontal = 4.dp, vertical = 0.dp)
            ) {
                Text("Cancel", maxLines = 1, fontSize = 13.sp)
            }
            Button(
                onClick = onSelectAll,
                modifier = Modifier.weight(1f).height(44.dp),
                shape = RoundedCornerShape(8.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary
                ),
                contentPadding = PaddingValues(horizontal = 4.dp, vertical = 0.dp)
            ) {
                Text(
                    if (allSelected) "Unselect all" else "Select all",
                    maxLines = 1,
                    fontSize = 13.sp
                )
            }
            Button(
                onClick = onDelete,
                enabled = count > 0,
                modifier = Modifier.weight(1f).height(44.dp),
                shape = RoundedCornerShape(8.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.error,
                    contentColor = MaterialTheme.colorScheme.onError
                ),
                contentPadding = PaddingValues(horizontal = 4.dp, vertical = 0.dp)
            ) {
                Text("Delete", maxLines = 1, fontSize = 13.sp)
            }
        }
    }
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

