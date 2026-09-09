package net.typeblog.socks.ui.screens

import android.content.SharedPreferences
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
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
import androidx.compose.ui.window.Dialog
import androidx.preference.PreferenceManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.typeblog.socks.R
import net.typeblog.socks.ui.components.ProtonDialogRadioRow
import net.typeblog.socks.ui.components.SearchInput
import net.typeblog.socks.ui.components.ProtonSwitch
import net.typeblog.socks.ui.components.SettingsItem
import net.typeblog.socks.ui.viewmodel.VpnViewModel
import net.typeblog.socks.util.Constants.PREF_ADV_APP_BYPASS
import net.typeblog.socks.util.Constants.PREF_ADV_APP_LIST
import net.typeblog.socks.util.Constants.PREF_ADV_PER_APP

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
    modifier: Modifier = Modifier
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
                    "Restarting VPN to apply changes",
                    android.widget.Toast.LENGTH_SHORT
                ).show()
                viewModel.restartVpn(context)
            }
        }
    }

    var splitEnabled by remember { mutableStateOf(prefs.getBoolean(PREF_ADV_PER_APP, false)) }
    var bypassMode by remember { mutableStateOf(prefs.getBoolean(PREF_ADV_APP_BYPASS, false)) }
    var persistedList by remember {
        mutableStateOf(
            prefs.getString(PREF_ADV_APP_LIST, "")?.split("\n")
                ?.map { it.trim() }
                ?.filter { it.isNotEmpty() }
                ?.toSet() ?: emptySet()
        )
    }
    DisposableEffect(context) {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            when (key) {
                PREF_ADV_PER_APP -> splitEnabled = prefs.getBoolean(PREF_ADV_PER_APP, false)
                PREF_ADV_APP_BYPASS -> bypassMode = prefs.getBoolean(PREF_ADV_APP_BYPASS, false)
                PREF_ADV_APP_LIST -> persistedList = prefs.getString(PREF_ADV_APP_LIST, "")?.split("\n")
                    ?.map { it.trim() }?.filter { it.isNotEmpty() }?.toSet() ?: emptySet()
            }
        }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        onDispose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }

    var installedApps by remember { mutableStateOf<List<InstalledApp>>(emptyList()) }

    suspend fun loadApps() {
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

    LaunchedEffect(Unit) { loadApps() }

    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val obs = androidx.lifecycle.LifecycleEventObserver { _, e ->
            if (e == androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
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

    var showModeDialog by remember { mutableStateOf(false) }
    var page by rememberSaveable { mutableStateOf(0) } // 0 = main, 1 = apps
    var query by rememberSaveable { mutableStateOf("") }
    BackHandler(enabled = page == 1) { page = 0 }

    val nameByPkg = remember(installedApps) {
        installedApps.associate { it.packageName to it.name }
    }
    val selectedPkgs = toggleStates.filterValues { it }.keys
    val appsSubtitle = when (selectedPkgs.size) {
        0 -> "None"
        1 -> nameByPkg[selectedPkgs.first()] ?: selectedPkgs.first()
        else -> "${selectedPkgs.size} apps"
    }

    if (showModeDialog) {
        ModeDialog(
            bypassMode = bypassMode,
            onSelect = { exclude ->
                bypassMode = exclude
                prefs.edit().putBoolean(PREF_ADV_APP_BYPASS, exclude).apply()
                scheduleRestart()
                showModeDialog = false
            },
            onDismiss = { showModeDialog = false }
        )
    }

    Scaffold(
        modifier = modifier,
        contentWindowInsets = WindowInsets(0),
        topBar = {
            TopAppBar(
                title = { if (page == 1) Text(if (bypassMode) "Excluded apps" else "Included apps") },
                windowInsets = WindowInsets(0),
                navigationIcon = {
                    IconButton(onClick = { if (page == 1) page = 0 else onNavigateBack() }) {
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
                        icon = painterResource(R.drawable.ic_proton_filter),
                        label = "Mode",
                        description = if (bypassMode) "Exclude" else "Include",
                        showChevron = false,
                        onClick = { showModeDialog = true }
                    )
                    SettingsItem(
                        icon = painterResource(R.drawable.ic_proton_apps),
                        label = if (bypassMode) "Excluded apps" else "Included apps",
                        description = appsSubtitle,
                        showChevron = false,
                        onClick = { page = 1 }
                    )
                }
                Spacer(modifier = Modifier.height(24.dp))
            }
        } else {
            AppsPage(
                paddingValues = paddingValues,
                bypassMode = bypassMode,
                installedApps = installedApps,
                toggleStates = toggleStates,
                query = query,
                onQueryChange = { query = it },
                onSetApp = { pkg, on ->
                    toggleStates[pkg] = on
                    prefs.edit()
                        .putString(PREF_ADV_APP_LIST, toggleStates.filterValues { it }.keys.joinToString("\n"))
                        .apply()
                    scheduleRestart()
                }
            )
        }
    }
}

@Composable
private fun ModeDialog(
    bypassMode: Boolean,
    onSelect: (Boolean) -> Unit,
    onDismiss: () -> Unit
) {
    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(24.dp),
            color = MaterialTheme.colorScheme.surface
        ) {
            Column(modifier = Modifier.padding(24.dp)) {
                Text(
                    text = "Mode",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(modifier = Modifier.height(12.dp))
                ProtonDialogRadioRow(
                    title = "Include",
                    description = "Only selected apps connect through the VPN; all other traffic is unprotected.",
                    selected = !bypassMode,
                    onClick = { onSelect(false) }
                )
                HorizontalHairline()
                ProtonDialogRadioRow(
                    title = "Exclude",
                    description = "Selected apps are excluded from the VPN connection.",
                    selected = bypassMode,
                    onClick = { onSelect(true) }
                )
            }
        }
    }
}

@Composable
private fun HorizontalHairline() {
    Surface(
        modifier = Modifier.fillMaxWidth().height(1.dp),
        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)
    ) {}
}

@Composable
private fun AppsPage(
    paddingValues: androidx.compose.foundation.layout.PaddingValues,
    bypassMode: Boolean,
    installedApps: List<InstalledApp>,
    toggleStates: Map<String, Boolean>,
    query: String,
    onQueryChange: (String) -> Unit,
    onSetApp: (String, Boolean) -> Unit
) {
    val filtered = remember(installedApps, query) {
        val q = query.trim().lowercase()
        if (q.isEmpty()) installedApps
        else installedApps.filter { it.name.lowercase().contains(q) || it.packageName.lowercase().contains(q) }
    }
    val selectedApps = filtered.filter { toggleStates[it.packageName] == true }
    val otherApps = filtered.filter { toggleStates[it.packageName] != true }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(paddingValues)
            .imePadding()
    ) {
        // Search bar — shared SearchInput (shell + 40dp group + icon + clear)
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
                item {
                    SectionHeader(
                        title = if (bypassMode) "Excluded apps (${selectedApps.size})" else "Included apps (${selectedApps.size})",
                        description = if (bypassMode)
                            "These apps are excluded from your VPN connection."
                        else
                            "Only these apps connect through the VPN."
                    )
                }
                items(selectedApps, key = { it.packageName }) { app ->
                    AppRow(
                        app = app,
                        trailingIcon = R.drawable.ic_proton_minus_circle_filled,
                        modifier = Modifier.animateItem(),
                        onAction = { onSetApp(app.packageName, false) }
                    )
                }
                item {
                    SectionHeader(
                        title = "All other regular apps (${otherApps.size})",
                        description = null
                    )
                }
                items(otherApps, key = { it.packageName }) { app ->
                    AppRow(
                        app = app,
                        trailingIcon = R.drawable.ic_proton_plus_circle,
                        modifier = Modifier.animateItem(),
                        onAction = { onSetApp(app.packageName, true) }
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
    onAction: () -> Unit
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .padding(horizontal = 20.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        AppIcon(icon = app.icon, appName = app.name)
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
private fun AppIcon(icon: android.graphics.drawable.Drawable?, appName: String) {
    Box(
        modifier = Modifier
            .size(36.dp)
            .clip(RoundedCornerShape(8.dp)),
        contentAlignment = Alignment.Center
    ) {
        if (icon != null) {
            val bitmap by produceState<android.graphics.Bitmap?>(initialValue = null, icon) {
                value = withContext(Dispatchers.IO) {
                    val bmp = android.graphics.Bitmap.createBitmap(
                        icon.intrinsicWidth.coerceAtLeast(1),
                        icon.intrinsicHeight.coerceAtLeast(1),
                        android.graphics.Bitmap.Config.ARGB_8888
                    )
                    val canvas = android.graphics.Canvas(bmp)
                    icon.setBounds(0, 0, canvas.width, canvas.height)
                    icon.draw(canvas)
                    bmp
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
