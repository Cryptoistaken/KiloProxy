package net.typeblog.socks.ui.screens

import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.preference.PreferenceManager
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import net.typeblog.socks.R
import net.typeblog.socks.ui.components.AppToggleItem
import net.typeblog.socks.ui.viewmodel.VpnViewModel
import net.typeblog.socks.util.Constants.PREF_ADV_APP_BYPASS
import net.typeblog.socks.util.Constants.PREF_ADV_APP_LIST
import net.typeblog.socks.util.Constants.PREF_ADV_PER_APP

/**
 * Represents an installed app with its display name and package name.
 */
private data class InstalledApp(
    val name: String,
    val packageName: String,
    val icon: android.graphics.drawable.Drawable?
)

/**
 * Split tunneling configuration screen.
 *
 * Shows a single searchable list of all installed apps with per-app toggle
 * rows. Toggle state is persisted to [PREF_ADV_APP_LIST] via SharedPreferences.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SplitTunnelingScreen(
    onNavigateBack: () -> Unit,
    viewModel: VpnViewModel,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val prefs = PreferenceManager.getDefaultSharedPreferences(context)
    val packageManager = context.packageManager
    val isRunning by viewModel.isRunning.collectAsState()

    val scope = rememberCoroutineScope()
    var restartJob by remember { mutableStateOf<Job?>(null) }

    fun scheduleRestart() {
        if (!isRunning) return
        restartJob?.cancel()
        restartJob = scope.launch {
            delay(500)
            if (isRunning) {
                android.widget.Toast.makeText(
                    context,
                    "Restarting VPN to apply changes",
                    android.widget.Toast.LENGTH_SHORT
                ).show()
                viewModel.restartVpn(context)
            }
        }
    }

    // Load persisted app list into a set
    val persistedList = remember {
        prefs.getString(PREF_ADV_APP_LIST, "")?.split("\n")
            ?.map { it.trim() }
            ?.filter { it.isNotEmpty() }
            ?.toSet() ?: emptySet()
    }

    // Master split-tunneling switch and allow/disallow mode
    var splitEnabled by remember { mutableStateOf(prefs.getBoolean(PREF_ADV_PER_APP, false)) }
    var bypassMode by remember { mutableStateOf(prefs.getBoolean(PREF_ADV_APP_BYPASS, false)) }
    var modeMenuExpanded by remember { mutableStateOf(false) }

    // Real installed launcher apps, loaded asynchronously
    var installedApps by remember { mutableStateOf<List<InstalledApp>>(emptyList()) }

    LaunchedEffect(Unit) {
        val apps = packageManager.getInstalledApplications(PackageManager.GET_META_DATA)
            .filter { it.flags and ApplicationInfo.FLAG_INSTALLED != 0 }
            .filter { packageManager.getLaunchIntentForPackage(it.packageName) != null }
            .map {
                InstalledApp(
                    name = it.loadLabel(packageManager).toString(),
                    packageName = it.packageName,
                    icon = it.loadIcon(packageManager)
                )
            }
            .sortedBy { it.name.lowercase() }
        installedApps = apps
    }

    // Toggle state for each app — true = included in split tunneling, false = not
    val toggleStates = remember { mutableStateMapOf<String, Boolean>() }

    // Sync persisted toggle states once real apps are loaded
    LaunchedEffect(installedApps) {
        if (installedApps.isNotEmpty()) {
            installedApps.forEach { app ->
                if (!toggleStates.containsKey(app.packageName)) {
                    toggleStates[app.packageName] = persistedList.contains(app.packageName)
                }
            }
        }
    }

    var query by remember { mutableStateOf("") }

    val filteredApps = remember(installedApps, query) {
        val q = query.trim().lowercase()
        if (q.isEmpty()) {
            installedApps
        } else {
            installedApps.filter { app ->
                app.name.lowercase().contains(q) || app.packageName.lowercase().contains(q)
            }
        }
    }

    val selectedCount = toggleStates.values.count { it }

    Scaffold(
        modifier = modifier,
        contentWindowInsets = WindowInsets(0),
        topBar = {
            TopAppBar(
                title = { Text("Split Tunneling") },
                windowInsets = WindowInsets(0),
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(
                            painter = painterResource(R.drawable.lucide_arrow_left),
                            contentDescription = "Back"
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    titleContentColor = MaterialTheme.colorScheme.onSurface
                )
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .imePadding()
        ) {
            // ── Master switch ──
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.surfaceContainerLow
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Enable split tunneling",
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "Turn on per-app proxy control",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = splitEnabled,
                        onCheckedChange = { newValue ->
                            splitEnabled = newValue
                            prefs.edit().putBoolean(PREF_ADV_PER_APP, newValue).apply()
                            scheduleRestart()
                        }
                    )
                }
            }

            if (splitEnabled) {
                // ── Allow vs disallow mode selector ──
                ExposedDropdownMenuBox(
                    expanded = modeMenuExpanded,
                    onExpandedChange = { modeMenuExpanded = !modeMenuExpanded }
                ) {
                    OutlinedTextField(
                        value = if (bypassMode) "Bypass selected apps" else "Route selected apps through VPN",
                        onValueChange = {},
                        readOnly = true,
                        label = { Text("Mode") },
                        trailingIcon = {
                            ExposedDropdownMenuDefaults.TrailingIcon(expanded = modeMenuExpanded)
                        },
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier
                            .menuAnchor(MenuAnchorType.PrimaryNotEditable, enabled = true)
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 4.dp)
                    )
                    ExposedDropdownMenu(
                        expanded = modeMenuExpanded,
                        onDismissRequest = { modeMenuExpanded = false }
                    ) {
                        DropdownMenuItem(
                            text = { Text("Route selected apps through VPN") },
                            onClick = {
                                bypassMode = false
                                modeMenuExpanded = false
                                prefs.edit().putBoolean(PREF_ADV_APP_BYPASS, false).apply()
                                scheduleRestart()
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("Bypass selected apps") },
                            onClick = {
                                bypassMode = true
                                modeMenuExpanded = false
                                prefs.edit().putBoolean(PREF_ADV_APP_BYPASS, true).apply()
                                scheduleRestart()
                            }
                        )
                    }
                }
            }

            // ── Mode banner — adapts to splitEnabled so this message and the
            // "enable split tunneling" copy never say the same thing twice ──
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.surfaceContainerLow
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = if (splitEnabled) {
                            "Choose which apps are handled by the proxy."
                        } else {
                            "Split tunneling is off"
                        },
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = if (splitEnabled) {
                            "Toggle an app ON to include it in the split-tunneling list. Apps not listed are not affected."
                        } else {
                            "Turn on the switch above to route or bypass individual apps."
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            if (splitEnabled) {
                // ── Search field ──
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    label = { Text("Search apps") },
                    singleLine = true,
                    leadingIcon = {
                        Icon(painter = painterResource(R.drawable.lucide_search), contentDescription = null)
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp)
                )

                // ── Status line ──
                Text(
                    text = if (selectedCount == 1) "1 app selected" else "$selectedCount apps selected",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 18.dp, vertical = 4.dp)
                )

                // ── App list ──
                if (installedApps.isEmpty()) {
                    Spacer(modifier = Modifier.height(40.dp))
                    Text(
                        text = "Loading apps…",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 18.dp)
                    )
                } else if (filteredApps.isEmpty()) {
                    Spacer(modifier = Modifier.height(40.dp))
                    Text(
                        text = "No apps match your search",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 18.dp)
                    )
                } else {
                    LazyColumn(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                    ) {
                        items(filteredApps, key = { it.packageName }) { app ->
                            val isOn = toggleStates[app.packageName] == true
                            AppToggleItem(
                                appName = app.name,
                                packageName = app.packageName,
                                isAllowed = isOn,
                                onToggle = { newValue ->
                                    toggleStates[app.packageName] = newValue
                                    // Persist to SharedPreferences
                                    val updatedList = toggleStates
                                        .filterValues { it }
                                        .keys
                                        .joinToString("\n")
                                    prefs.edit()
                                        .putString(PREF_ADV_APP_LIST, updatedList)
                                        .apply()
                                    scheduleRestart()
                                },
                                icon = app.icon
                            )
                            HorizontalDivider(
                                thickness = 0.5.dp,
                                color = MaterialTheme.colorScheme.outlineVariant
                            )
                        }
                    }
                }
            }
        }
    }
}
