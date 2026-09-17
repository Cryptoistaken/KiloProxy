package net.typeblog.socks.ui.screens

import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.preference.PreferenceManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.typeblog.socks.R
import net.typeblog.socks.ui.components.SearchInput
import net.typeblog.socks.ui.components.rememberPref
import net.typeblog.socks.ui.components.ProtonSwitch
import net.typeblog.socks.ui.components.SettingsItem
import net.typeblog.socks.ui.viewmodel.VpnViewModel
import net.typeblog.socks.util.Constants.PREF_ADV_APP_LIST
import net.typeblog.socks.util.Constants.PREF_ADV_PER_APP
import net.typeblog.socks.util.SplitTunnel
import java.util.concurrent.ConcurrentHashMap

private data class InstalledApp(
    val name: String,
    val packageName: String,
    val icon: android.graphics.drawable.Drawable?
)

/**
 * Split tunneling, ProtonVPN mock design: feature header + master toggle card,
 * then Mode and Apps rows (main page) and a searchable two-section apps page
 * (selected apps with remove, all other apps with add).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SplitTunnelingScreen(
    onNavigateBack: () -> Unit,
    viewModel: VpnViewModel,
    modifier: Modifier = Modifier,
    startOnApps: Boolean = false
) {
    val context = LocalContext.current
    val prefs = PreferenceManager.getDefaultSharedPreferences(context)
    val packageManager = context.packageManager
    val isRunning by viewModel.isRunning.collectAsState()

    val scope = rememberCoroutineScope()
    var restartJob by remember { mutableStateOf<Job?>(null) }
    DisposableEffect(Unit) { onDispose { restartJob?.cancel() } }

    fun scheduleRestart() {
        if (!isRunning) return
        restartJob?.cancel()
        restartJob = scope.launch {
            delay(500)
            if (isRunning) {
                android.widget.Toast.makeText(
                    context,
                    "Restarting VPN",
                    android.widget.Toast.LENGTH_SHORT
                ).show()
                viewModel.restartVpn(context)
            }
        }
    }

    var splitEnabled by rememberPref(prefs, PREF_ADV_PER_APP) {
        it.getBoolean(PREF_ADV_PER_APP, false)
    }
    var persistedList by rememberPref(prefs, PREF_ADV_APP_LIST) {
        SplitTunnel.parseAppList(it.getString(PREF_ADV_APP_LIST, ""))
    }

    // Single Include-only mode: only selected apps connect through the VPN.
    // Leaving with zero effective apps auto-turns split off, so an empty
    // allow-list can never reach the engine.
    fun effectiveApps(): List<String> =
        SplitTunnel.parseAppList(prefs.getString(PREF_ADV_APP_LIST, ""))
            .filter { it != context.packageName }

    fun autoOffIfEmpty() {
        if (prefs.getBoolean(PREF_ADV_PER_APP, false) && effectiveApps().isEmpty()) {
            prefs.edit().putBoolean(PREF_ADV_PER_APP, false).apply()
        }
    }
    DisposableEffect(Unit) { onDispose { autoOffIfEmpty() } }

    var page by rememberSaveable { mutableStateOf(if (startOnApps) 1 else 0) } // 0 = main, 1 = included, 2 = add apps

    var installedApps by remember { mutableStateOf<List<InstalledApp>>(emptyList()) }
    // Screen-scoped decoded-icon cache: page switches recompose rows from
    // scratch, so without this every navigation would reconvert drawables
    // (flashing the letter fallback first). Keyed by package; a reload hands
    // AppIcon new drawable instances, which reconvert and overwrite.
    val iconCache = remember { ConcurrentHashMap<String, android.graphics.Bitmap>() }

    var cleanedStale by remember { mutableStateOf(false) }

    suspend fun loadApps() {
        val selfPkg = context.packageName
        // PackageManager queries + label/icon loads are binder calls: keep
        // them off the main thread so entry never stutters.
        val apps = withContext(Dispatchers.IO) {
            packageManager.getInstalledApplications(PackageManager.GET_META_DATA)
                .filter { it.flags and ApplicationInfo.FLAG_INSTALLED != 0 }
                .filter { it.packageName != selfPkg }
                .filter { packageManager.getLaunchIntentForPackage(it.packageName) != null }
                .map {
                    InstalledApp(
                        name = it.loadLabel(packageManager).toString(),
                        packageName = it.packageName,
                        icon = try {
                            it.loadIcon(packageManager)
                        } catch (_: Exception) {
                            null
                        }
                    )
                }
                .sortedBy { it.name.lowercase() }
        }
        installedApps = apps
        // Drop stale (uninstalled) and self entries from the persisted list
        // once per screen open; the engine skips them anyway.
        if (!cleanedStale) {
            cleanedStale = true
            val valid = apps.map { it.packageName }.toSet()
            val pruned = persistedList.filter { valid.contains(it) }.toSet()
            if (pruned != persistedList) {
                prefs.edit().putString(PREF_ADV_APP_LIST, pruned.joinToString("\n")).apply()
            }
        }
    }

    // Load once per screen entry: page switches reuse the same list and
    // drawable instances, so icons never reload from the source.
    LaunchedEffect(Unit) { loadApps() }

    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, page) {
        val obs = androidx.lifecycle.LifecycleEventObserver { _, e ->
            if (e == androidx.lifecycle.Lifecycle.Event.ON_RESUME && (page == 1 || page == 2)) {
                scope.launch { loadApps() }
            }
        }
        lifecycleOwner.lifecycle.addObserver(obs)
        onDispose { lifecycleOwner.lifecycle.removeObserver(obs) }
    }

    val toggleStates = remember { mutableStateMapOf<String, Boolean>() }
    LaunchedEffect(installedApps) {
        if (installedApps.isNotEmpty()) {
            installedApps.forEach { app ->
                if (!toggleStates.containsKey(app.packageName)) {
                    toggleStates[app.packageName] = persistedList.contains(app.packageName)
                }
            }
        }
    }
    LaunchedEffect(persistedList) {
        if (installedApps.isNotEmpty()) {
            installedApps.forEach { app ->
                toggleStates[app.packageName] = persistedList.contains(app.packageName)
            }
        }
    }

    var query by rememberSaveable { mutableStateOf("") }
    BackHandler(enabled = page == 1 || page == 2) {
        if (page == 2) page = 1
        else {
            autoOffIfEmpty()
            page = 0
        }
    }

    val nameByPkg = remember(installedApps) {
        installedApps.associate { it.packageName to it.name }
    }
    // Subtitle reads the prefs-backed persisted list (not toggleStates,
    // which is only populated once the apps list loads), so it is correct
    // on first display without opening the apps page.
    val selectedPkgs = persistedList
    val appsSubtitle = when (selectedPkgs.size) {
        0 -> "None"
        1 -> nameByPkg[selectedPkgs.first()] ?: selectedPkgs.first()
        else -> "${selectedPkgs.size} apps"
    }

    Scaffold(
        modifier = modifier,
        contentWindowInsets = WindowInsets(0),
        floatingActionButton = {
            if (page == 1) {
                FloatingActionButton(onClick = { page = 2 }) {
                    Icon(
                        painter = painterResource(R.drawable.lucide_plus),
                        contentDescription = "Add apps"
                    )
                }
            }
        },
        topBar = {
            TopAppBar(
                title = {
                    when (page) {
                        1 -> Text("Included apps")
                        2 -> Text("Add apps")
                        else -> {}
                    }
                },
                windowInsets = WindowInsets(0),
                navigationIcon = {
                    IconButton(onClick = {
                        when (page) {
                            2 -> page = 1
                            1 -> {
                                autoOffIfEmpty()
                                page = 0
                            }
                            else -> {
                                autoOffIfEmpty()
                                onNavigateBack()
                            }
                        }
                    }) {
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
        if (page == 0) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues)
                    .verticalScroll(rememberScrollState())
            ) {
                // Feature header
                Column(modifier = Modifier.padding(horizontal = 20.dp, vertical = 16.dp)) {
                    Icon(
                        painter = painterResource(
                            if (splitEnabled) R.drawable.feature_splittunneling_on
                            else R.drawable.feature_splittunneling_off
                        ),
                        contentDescription = null,
                        modifier = Modifier.size(64.dp),
                        tint = Color.Unspecified
                    )
                    Text(
                        text = "Split tunneling",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.padding(top = 16.dp)
                    )
                    Text(
                        text = "Customize your connection by deciding which apps are protected by VPN.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 8.dp)
                    )
                }

                // Master toggle card
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    shape = RoundedCornerShape(16.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerLow
                ) {
                    val onToggle: (Boolean) -> Unit = { newValue ->
                        splitEnabled = newValue
                        prefs.edit().putBoolean(PREF_ADV_PER_APP, newValue).apply()
                        // Enabling always opens the apps page: there is nothing
                        // else to do here, and an empty exit auto-turns it off.
                        if (newValue) page = 1
                        scheduleRestart()
                    }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onToggle(!splitEnabled) }
                            .padding(horizontal = 20.dp, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Split tunneling",
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.weight(1f)
                        )
                        ProtonSwitch(
                            checked = splitEnabled,
                            onCheckedChange = onToggle
                        )
                    }
                }

                if (splitEnabled) {
                    SettingsItem(
                        icon = painterResource(R.drawable.ic_proton_apps),
                        label = "Included apps",
                        description = appsSubtitle,
                        showChevron = false,
                        onClick = { page = 1 }
                    )
                }
                Spacer(modifier = Modifier.height(24.dp))
            }
        } else if (page == 1) {
            IncludedPage(
                paddingValues = paddingValues,
                installedApps = installedApps,
                toggleStates = toggleStates,
                iconCache = iconCache,
                onSetApp = { pkg, on ->
                    toggleStates[pkg] = on
                    prefs.edit()
                        .putString(PREF_ADV_APP_LIST, SplitTunnel.joinAppList(toggleStates.filterValues { it }.keys))
                        .apply()
                    scheduleRestart()
                }
            )
        } else {
            AddAppsPage(
                paddingValues = paddingValues,
                installedApps = installedApps,
                toggleStates = toggleStates,
                iconCache = iconCache,
                query = query,
                onQueryChange = { query = it },
                onSetApp = { pkg, on ->
                    toggleStates[pkg] = on
                    prefs.edit()
                        .putString(PREF_ADV_APP_LIST, SplitTunnel.joinAppList(toggleStates.filterValues { it }.keys))
                        .apply()
                    scheduleRestart()
                }
            )
        }
    }
}

@Composable
private fun IncludedPage(
    paddingValues: androidx.compose.foundation.layout.PaddingValues,
    installedApps: List<InstalledApp>,
    toggleStates: Map<String, Boolean>,
    iconCache: ConcurrentHashMap<String, android.graphics.Bitmap>,
    onSetApp: (String, Boolean) -> Unit
) {
    // Read live (no remember): toggleStates mutates in place, so a cached
    // filter would go stale.
    val selectedApps = installedApps.filter { toggleStates[it.packageName] == true }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(paddingValues)
    ) {
        if (installedApps.isEmpty()) {
            Text(
                text = "Loading apps",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 36.dp, vertical = 16.dp)
            )
        } else if (selectedApps.isEmpty()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 36.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Text(
                    text = "No apps included yet",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = "Tap + to choose apps that connect through the VPN.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp)
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 88.dp)
            ) {
                item {
                    SectionHeader(
                        title = "Included apps (${selectedApps.size})",
                        description = "Only these apps connect through the VPN."
                    )
                }
                items(selectedApps, key = { it.packageName }) { app ->
                    AppRow(
                        app = app,
                        trailingIcon = R.drawable.ic_proton_minus_circle_filled,
                        modifier = Modifier.animateItem(),
                        onAction = { onSetApp(app.packageName, false) },
                        iconCache = iconCache
                    )
                }
            }
        }
    }
}

@Composable
private fun AddAppsPage(
    paddingValues: androidx.compose.foundation.layout.PaddingValues,
    installedApps: List<InstalledApp>,
    toggleStates: Map<String, Boolean>,
    iconCache: ConcurrentHashMap<String, android.graphics.Bitmap>,
    query: String,
    onQueryChange: (String) -> Unit,
    onSetApp: (String, Boolean) -> Unit
) {
    val filtered = remember(installedApps, query) {
        val q = query.trim().lowercase()
        if (q.isEmpty()) installedApps
        else installedApps.filter { it.name.lowercase().contains(q) || it.packageName.lowercase().contains(q) }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(paddingValues)
            .imePadding()
    ) {
        // Search bar — shared SearchInput (single 56dp group + icon + clear)
        SearchInput(
            value = query,
            onValueChange = onQueryChange,
            placeholder = "Search apps",
            description = "Search apps",
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
        )

        LazyColumn(modifier = Modifier.fillMaxSize()) {
            if (installedApps.isEmpty()) {
                item {
                    Text(
                        text = "Loading apps",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 36.dp, vertical = 16.dp)
                    )
                }
            } else {
                items(filtered, key = { it.packageName }) { app ->
                    // Added rows flip to a check and stay put so the list
                    // never jumps under the finger while adding several apps.
                    val added = toggleStates[app.packageName] == true
                    AppRow(
                        app = app,
                        trailingIcon = if (added) R.drawable.lucide_check
                            else R.drawable.ic_proton_plus_circle,
                        modifier = Modifier.animateItem(),
                        onAction = { if (!added) onSetApp(app.packageName, true) },
                        iconCache = iconCache
                    )
                }
            }
        }
    }
}

@Composable
private fun SectionHeader(title: String, description: String?) {
    Column(modifier = Modifier.padding(horizontal = 36.dp, vertical = 8.dp)) {
        Text(
            text = title,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        if (description != null) {
            Text(
                text = description,
                fontSize = 13.sp,
                lineHeight = 18.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp)
            )
        }
    }
}

@Composable
private fun AppRow(
    app: InstalledApp,
    trailingIcon: Int,
    modifier: Modifier = Modifier,
    onAction: () -> Unit,
    iconCache: ConcurrentHashMap<String, android.graphics.Bitmap>
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .padding(horizontal = 20.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        AppIcon(icon = app.icon, appName = app.name, cacheKey = app.packageName, iconCache = iconCache)
        Spacer(modifier = Modifier.width(14.dp))
        Text(
            text = app.name,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f)
        )
        IconButton(
            onClick = onAction,
            modifier = Modifier.size(28.dp)
        ) {
            Icon(
                painter = painterResource(trailingIcon),
                contentDescription = null,
                modifier = Modifier.size(24.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun AppIcon(
    icon: android.graphics.drawable.Drawable?,
    appName: String,
    cacheKey: String,
    iconCache: ConcurrentHashMap<String, android.graphics.Bitmap>
) {
    Box(
        modifier = Modifier
            .size(36.dp)
            .clip(RoundedCornerShape(8.dp)),
        contentAlignment = Alignment.Center
    ) {
        if (icon != null) {
            // Cached bitmap first: recompositions from page switches reuse
            // it with no flash. Keyed on the drawable instance too, so a
            // reload (new instances) reconverts and refreshes the cache.
            val bitmap by produceState<android.graphics.Bitmap?>(
                initialValue = iconCache[cacheKey], icon, cacheKey
            ) {
                val fresh = iconCache[cacheKey]
                value = if (fresh != null) {
                    fresh
                } else {
                    withContext(Dispatchers.IO) {
                        try {
                            val bmp = android.graphics.Bitmap.createBitmap(
                                icon.intrinsicWidth.coerceAtLeast(1),
                                icon.intrinsicHeight.coerceAtLeast(1),
                                android.graphics.Bitmap.Config.ARGB_8888
                            )
                            val canvas = android.graphics.Canvas(bmp)
                            icon.setBounds(0, 0, canvas.width, canvas.height)
                            icon.draw(canvas)
                            bmp
                        } catch (_: Exception) {
                            null
                        }
                    }?.also { iconCache[cacheKey] = it }
                }
            }
            if (bitmap != null) {
                Icon(
                    painter = BitmapPainter(bitmap!!.asImageBitmap()),
                    contentDescription = appName,
                    modifier = Modifier.size(36.dp),
                    tint = Color.Unspecified
                )
            }
        } else {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = appName.firstOrNull()?.uppercase() ?: "?",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
        }
    }
}
