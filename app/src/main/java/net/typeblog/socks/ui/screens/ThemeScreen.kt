package net.typeblog.socks.ui.screens

import android.content.SharedPreferences
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.preference.PreferenceManager
import net.typeblog.socks.R
import net.typeblog.socks.ui.components.ProtonRadio
import net.typeblog.socks.util.Constants.PREF_THEME_MODE

/**
 * Theme picker page, ProtonVPN mock style: three side-by-side cards
 * (Light / Dark / Device theme), each a mini phone preview + label + radio.
 * Selection persists to [PREF_THEME_MODE] and applies live via KiloProxyTheme.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ThemeScreen(
    onNavigateBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val prefs = PreferenceManager.getDefaultSharedPreferences(context)
    var themeMode by remember {
        mutableStateOf(prefs.getString(PREF_THEME_MODE, "light") ?: "light")
    }
    DisposableEffect(context) {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == PREF_THEME_MODE) {
                themeMode = prefs.getString(PREF_THEME_MODE, "light") ?: "light"
            }
        }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        onDispose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }

    fun select(value: String) {
        themeMode = value
        prefs.edit().putString(PREF_THEME_MODE, value).apply()
    }

    Scaffold(
        modifier = modifier,
        contentWindowInsets = WindowInsets(0),
        topBar = {
            TopAppBar(
                title = { Text("Theme") },
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
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(horizontal = 16.dp, vertical = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterHorizontally)
        ) {
            ThemeCard(
                label = "Light",
                selected = themeMode == "light",
                onClick = { select("light") }
            ) { MiniPhone(LightPalette) }
            ThemeCard(
                label = "Dark",
                selected = themeMode == "dark",
                onClick = { select("dark") }
            ) { MiniPhone(DarkPalette) }
            ThemeCard(
                label = "Device theme",
                selected = themeMode == "system",
                onClick = { select("system") }
            ) { AutoMiniPhone() }
        }
    }
}

@Composable
private fun ThemeCard(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    preview: @Composable () -> Unit
) {
    Column(
        modifier = Modifier
            .clip(RoundedCornerShape(12.dp))
            .background(
                if (selected) MaterialTheme.colorScheme.onSurface.copy(alpha = 0.05f)
                else Color.Transparent
            )
            .clickable(onClick = onClick)
            .width(92.dp)
            .padding(horizontal = 4.dp, vertical = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(modifier = Modifier.shadow(elevation = 4.dp, shape = RoundedCornerShape(16.dp))) {
            preview()
        }
        Spacer(modifier = Modifier.height(10.dp))
        Text(
            text = label,
            fontSize = 14.sp,
            lineHeight = 18.sp,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center
        )
        Spacer(modifier = Modifier.height(10.dp))
        ProtonRadio(selected = selected)
    }
}

/** Fixed preview palette — previews look identical in both app themes, like the mock. */
private data class MiniPalette(
    val screen: Color,
    val logo: Color,
    val dropdown: Color,
    val dropdownBorder: Color,
    val card: Color,
    val cardBorder: Color,
    val onCard: Color,
    val chip: Color,
    val button: Color,
    val line: Color
)

private val LightPalette = MiniPalette(
    screen = Color(0xFFF5F5F5), logo = Color(0xFF000000),
    dropdown = Color(0xFFE0E0E0), dropdownBorder = Color(0xFFCCCCCC),
    card = Color(0xFFFFFFFF), cardBorder = Color(0xFFE0E0E0),
    onCard = Color(0xFF000000), chip = Color(0xFFE0E0E0),
    button = Color(0xFF000000), line = Color(0xFFD0D0D0)
)

private val DarkPalette = MiniPalette(
    screen = Color(0xFF0F0F0F), logo = Color(0xFFFFFFFF),
    dropdown = Color(0xFF1E1E1E), dropdownBorder = Color(0xFF333333),
    card = Color(0xFF1A1A1A), cardBorder = Color(0xFF2A2A2A),
    onCard = Color(0xFFFFFFFF), chip = Color(0xFF2A2A2A),
    button = Color(0xFFFFFFFF), line = Color(0xFF333333)
)

@Composable
private fun MiniPhone(p: MiniPalette) {
    Column(
        modifier = Modifier
            .size(width = 80.dp, height = 140.dp)
            .clip(RoundedCornerShape(16.dp))
            .padding(5.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .clip(RoundedCornerShape(12.dp))
                .background(p.screen)
                .padding(horizontal = 6.dp, vertical = 10.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(3.dp),
                modifier = Modifier.padding(start = 4.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(6.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(p.logo)
                )
                Box(
                    modifier = Modifier
                        .size(width = 28.dp, height = 4.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(p.logo)
                )
            }
            Spacer(modifier = Modifier.height(6.dp))
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(10.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(p.dropdown)
                    .border(1.dp, p.dropdownBorder, RoundedCornerShape(4.dp))
            )
            Spacer(modifier = Modifier.height(6.dp))
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(p.card)
                    .border(1.dp, p.cardBorder, RoundedCornerShape(10.dp))
                    .padding(horizontal = 6.dp, vertical = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Box(
                    modifier = Modifier
                        .size(width = 24.dp, height = 3.dp)
                        .clip(RoundedCornerShape(1.dp))
                        .background(p.onCard)
                )
                Spacer(modifier = Modifier.height(5.dp))
                Box(
                    modifier = Modifier
                        .size(width = 28.dp, height = 6.dp)
                        .clip(RoundedCornerShape(3.dp))
                        .background(p.chip)
                )
                Spacer(modifier = Modifier.height(5.dp))
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(12.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(p.button)
                )
            }
            Spacer(modifier = Modifier.weight(1f))
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .background(p.card)
                    .border(1.dp, p.cardBorder, RoundedCornerShape(8.dp))
                    .padding(6.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(width = 23.dp, height = 3.dp)
                        .clip(RoundedCornerShape(1.5.dp))
                        .background(p.line)
                )
                Spacer(modifier = Modifier.height(3.dp))
                Box(
                    modifier = Modifier
                        .size(width = 38.dp, height = 3.dp)
                        .clip(RoundedCornerShape(1.5.dp))
                        .background(p.line)
                )
            }
        }
    }
}

/** Device-theme preview: single phone split light/dark down the middle. */
@Composable
private fun AutoMiniPhone() {
    Column(
        modifier = Modifier
            .size(width = 80.dp, height = 140.dp)
            .clip(RoundedCornerShape(16.dp))
            .padding(5.dp)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .clip(RoundedCornerShape(12.dp))
        ) {
            Row(modifier = Modifier.matchParentSize()) {
                Box(modifier = Modifier.weight(1f).fillMaxHeight().background(Color(0xFFF5F5F5)))
                Box(modifier = Modifier.weight(1f).fillMaxHeight().background(Color(0xFF0F0F0F)))
            }
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 6.dp, vertical = 10.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(3.dp)
                ) {
                    SplitBar(modifier = Modifier.size(6.dp), light = Color(0xFF000000), dark = Color(0xFFFFFFFF), radius = 2.dp)
                    SplitBar(modifier = Modifier.size(width = 28.dp, height = 4.dp), light = Color(0xFF000000), dark = Color(0xFFFFFFFF), radius = 2.dp)
                }
                Spacer(modifier = Modifier.height(6.dp))
                SplitBar(
                    modifier = Modifier.fillMaxWidth().height(10.dp),
                    light = Color(0xFFE0E0E0), dark = Color(0xFF1E1E1E), radius = 4.dp
                )
                Spacer(modifier = Modifier.height(6.dp))
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                ) {
                    Row(modifier = Modifier.matchParentSize()) {
                        Box(modifier = Modifier.weight(1f).fillMaxHeight().background(Color(0xFFFFFFFF)))
                        Box(modifier = Modifier.weight(1f).fillMaxHeight().background(Color(0xFF1A1A1A)))
                    }
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 8.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        SplitBar(
                            modifier = Modifier.size(width = 24.dp, height = 3.dp),
                            light = Color(0xFF000000), dark = Color(0xFFFFFFFF), radius = 1.5.dp
                        )
                        Spacer(modifier = Modifier.height(5.dp))
                        SplitBar(
                            modifier = Modifier.size(width = 28.dp, height = 6.dp),
                            light = Color(0xFFE0E0E0), dark = Color(0xFF2A2A2A), radius = 3.dp
                        )
                        Spacer(modifier = Modifier.height(5.dp))
                        SplitBar(
                            modifier = Modifier.fillMaxWidth().height(12.dp),
                            light = Color(0xFF000000), dark = Color(0xFFFFFFFF), radius = 6.dp
                        )
                    }
                }
                Spacer(modifier = Modifier.weight(1f))
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                ) {
                    Row(modifier = Modifier.matchParentSize()) {
                        Box(modifier = Modifier.weight(1f).fillMaxHeight().background(Color(0xFFFFFFFF)))
                        Box(modifier = Modifier.weight(1f).fillMaxHeight().background(Color(0xFF1A1A1A)))
                    }
                    Column(modifier = Modifier.fillMaxWidth().padding(6.dp)) {
                        SplitBar(
                            modifier = Modifier.size(width = 23.dp, height = 3.dp),
                            light = Color(0xFFD0D0D0), dark = Color(0xFF333333), radius = 1.5.dp
                        )
                        Spacer(modifier = Modifier.height(3.dp))
                        SplitBar(
                            modifier = Modifier.size(width = 38.dp, height = 3.dp),
                            light = Color(0xFFD0D0D0), dark = Color(0xFF333333), radius = 1.5.dp
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SplitBar(
    modifier: Modifier,
    light: Color,
    dark: Color,
    radius: androidx.compose.ui.unit.Dp
) {
    Box(modifier = modifier.clip(RoundedCornerShape(radius))) {
        Row(modifier = Modifier.matchParentSize()) {
            Box(modifier = Modifier.weight(1f).fillMaxHeight().background(light))
            Box(modifier = Modifier.weight(1f).fillMaxHeight().background(dark))
        }
    }
}
