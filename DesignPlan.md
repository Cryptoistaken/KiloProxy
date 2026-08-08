# KiloProxy UI Redesign Plan

> Redesign the Compose UI using ProtonVPN-inspired patterns while preserving
> the existing engine (SocksVpnService + AIDL). No engine changes required.

---

## 1. Current State Analysis

### What works
- Clean MVVM architecture: `VpnViewModel` → AIDL → `SocksVpnService`
- Material 3 foundation with custom Geist typography
- Monochrome color system (intentional, not a bug)
- 4 focused screens, 7 reusable components

### What needs improvement
| Issue | Impact |
|---|---|
| StatusScreen crams too much into one scrollable column | Feels cluttered, no visual hierarchy |
| ProxiesScreen at 1198 lines is a monolith | Hard to maintain, slow recomposition |
| No visual connection status indicator (just text) | Users can't glance-status the connection |
| SettingsScreen is flat list with no grouping | Hard to scan, no visual structure |
| No onboarding / first-use experience | Confusing for new users |
| No animations or transitions | Feels static and unpolished |
| Data usage card is pure numbers | Not immediately readable |
| Error states are plain text | Easy to miss, no recovery guidance |
| No empty states with illustrations | Feels incomplete when no profiles exist |

### What stays untouched (engine — NEVER modify)
| File | Purpose |
|---|---|
| `SocksVpnService.kt` | VPN tunnel (tun2socks, pdnsd, IP check) |
| `IVpnService.aidl` | Cross-process interface contract |
| `Utility.kt` | startVpn(), checkPublicIp(), formatBytes() |
| `ProfileManager.kt` | SharedPreferences profile storage |
| `VpnViewModel.kt` | Bridge (data only, no UI) |

---

## 2. Design Principles (ProtonVPN-Inspired)

### 2.1 Connection-first hierarchy
ProtonVPN's home screen leads with the connection state as a full-width
hero card. KiloProxy should do the same — the connect/disconnect action
is the primary interaction.

### 2.2 Progressive disclosure
Show only what matters at each level. ProtonVPN hides advanced protocol
settings behind a tap. KiloProxy should hide profile editing details
behind the card, not inline.

### 2.3 Status as color, not text
ProtonVPN uses green/red/orange overlays and animated status indicators.
KiloProxy should reserve color for state: green = connected, red = error,
orange = idle/connecting.

### 2.4 Card-based grouping
ProtonVPN groups related settings into elevated cards with headers.
KiloProxy's flat list should become grouped sections with clear headers.

### 2.5 Smooth transitions
ProtonVPN uses animated status changes, slide-in panels, and crossfade
between states. KiloProxy should add `AnimatedVisibility` and
`Crossfade` for state transitions.

---

## 3. Asset Inventory

### 3.1 Fonts (keep all — no changes needed)

| Font | Files | Usage |
|---|---|---|
| Geist Regular | `fonts/geist-regular.ttf` | Body text, labels |
| Geist Medium | `fonts/geist-medium.ttf` | Card titles, button text |
| Geist SemiBold | `fonts/geist-semibold.ttf` | Hero status text |
| Geist Bold | `fonts/geist-bold.ttf` | Screen headlines |
| Geist Mono Regular | `fonts/geist-mono-regular.ttf` | Server addresses, IPs |
| Geist Pixel Square | `fonts/geist-pixel.ttf` | Logo/brand accents only |

### 3.2 Icons — Standardize to Lucide Filled

| Current drawable | State | Action |
|---|---|---|
| `lucide_activity.xml` | Outline | Swap → `lucide_activity_filled` |
| `lucide_globe.xml` | Outline | Swap → `lucide_globe_filled` |
| `lucide_info.xml` | Outline | Swap → `lucide_info_filled` |
| `lucide_paintbrush_vertical.xml` | Outline | Keep (unique enough) |
| `lucide_panel_left.xml` | Outline | Swap → `lucide_panel_left_filled` |
| `lucide_settings.xml` | Outline | Swap → `lucide_settings_filled` |
| `lucide_shield.xml` | Outline | Swap → `lucide_shield_filled` |
| `lucide_square_pen.xml` | Outline | Keep |
| `lucide_triangle_alert.xml` | Outline | Swap → `lucide_triangle_alert_filled` |
| `lucide_x.xml` | Outline | Keep (close button, outline is correct) |
| `ic_bubble_play.xml` | Custom | Keep (bubble-specific) |
| `ic_bubble_stop.xml` | Custom | Keep (bubble-specific) |

**Standard:** All settings icons = 24dp, 2px stroke, Lucide Filled set.

### 3.3 Colors

**Keep (existing):**
| Token | Hex | Usage |
|---|---|---|
| `Idle` | `#FF6B00` | Brand orange, default state |
| `Connected` | `#22C55E` | Green, VPN active |
| `Error` | `#EF4444` | Red, connection failed |

**Add (new):**
| Token | Hex | Usage |
|---|---|---|
| `Connecting` | `#F59E0B` | Amber, pulsing ring during connect |
| `Disabled` | `#71717A` | Zinc-500, inactive/muted elements |
| `SurfaceElevatedLight` | `#F4F4F5` | Zinc-100, card backgrounds (light) |
| `SurfaceElevatedDark` | `#18181B` | Zinc-900, card backgrounds (dark) |

### 3.4 Components — Current inventory

**Keep as-is:**
| Component | File | Lines | Notes |
|---|---|---|---|
| `SettingsItem` | `SettingsItem.kt` | ~60 | Add `iconTint` slot |
| `AppToggleItem` | `AppToggleItem.kt` | ~80 | Add app icon loading (Coil) |
| `ThemePickerDialog` | `ThemePickerDialog.kt` | ~80 | No changes |

**Rewrite:**
| Current | New | Lines (est.) | Key change |
|---|---|---|---|
| `ConnectionCard` | `ConnectionHero` | ~200 | Full-width ring + inline status |
| `DataUsageCard` | `StatsRow` | ~80 | Horizontal compact row |
| `ProxyCard` | `ProfileListItem` | ~120 | Status dot + mono address |

**Build new:**
| Component | Purpose | Lines (est.) |
|---|---|---|
| `StatusRing` | Animated circular pulse (200dp) | ~150 |
| `EmptyState` | Illustration + CTA button | ~80 |
| `SectionHeader` | Uppercase label divider | ~30 |
| `SearchBar` | Search input with icon | ~60 |

### 3.5 Screens — Current inventory

| Screen | File | Lines | Action |
|---|---|---|---|
| `StatusScreen` | `StatusScreen.kt` | 327 | Rewrite → `HomeScreen.kt` |
| `ProxiesScreen` | `ProxiesScreen.kt` | 1198 | Rewrite → `ProfilesScreen.kt` |
| `SettingsScreen` | `SettingsScreen.kt` | 309 | Restructure (grouped sections) |
| `SplitTunnelingScreen` | `SplitTunnelingScreen.kt` | 383 | Polish (search + segmented mode) |

### 3.6 Dead code to delete

| File | Reason |
|---|---|
| `ProfileFragment.kt` (624 lines) | Legacy XML PreferenceFragment, not connected to Compose UI |
| `main.xml` | Legacy layout, unused by Compose path |
| `AppSelector.kt` | Replaced by `SplitTunnelingScreen.kt` |

---

## 4. Screen-by-Screen Redesign

### 4.1 Home Screen (formerly StatusScreen)

**Current:** Profile dropdown + connect button + data card + details table
**Proposed:** Full-height hero with connection state, minimal details below

```
┌─────────────────────────────┐
│  [Logo]              [⚙️]   │  ← minimal top bar
│                             │
│                             │
│    ┌───────────────────┐    │
│    │                   │    │
│    │   ● CONNECTED     │    │  ← animated state ring
│    │   192.168.1.1     │    │     (green/red/orange pulse)
│    │   Japan · Tokyo   │    │
│    │                   │    │
│    │   [  DISCONNECT  ]│    │  ← full-width pill button
│    └───────────────────┘    │
│                             │
│  ┌──────┐ ┌──────┐ ┌──────┐│
│  │ ↑ 2MB│ │ ↓ 5MB│ │ 0:42 ││  ← compact stats row
│  └──────┘ └──────┘ └──────┘│
│                             │
│  Recently connected         │  ← quick reconnect list
│  ├ Japan · Tokyo            │
│  ├ US · New York            │
│  └ Singapore                │
└─────────────────────────────┘
```

**Key changes:**
- Connection state is the visual anchor (large centered status)
- Animated ring/circle around status (like ProtonVPN's map dot)
- IP + location shown inline below status (not in a table)
- Stats become a compact horizontal row (not a full card)
- Recent profiles as a quick-access list below
- Profile selector moves to a bottom sheet or dropdown in the top bar
- Settings gear icon in top-right corner

**ProtonVPN reference:** `redesign/home_screen/ui/Home.kt`

### 4.2 Profiles Screen (formerly ProxiesScreen)

**Current:** 1198-line monolith with inline editing
**Proposed:** Clean list with FAB, editing in bottom sheet

```
┌─────────────────────────────┐
│  Profiles                   │
│                             │
│  ┌───────────────────────┐  │
│  │ 🟢 Japan · Tokyo      │  │  ← status dot + location
│   socks5://1.2.3.4:1080  │  │  ← server address (mono font)
│  │ OwlProxy    ▸ Edit    │  │  ← provider chip + action
│  └───────────────────────┘  │
│                             │
│  ┌───────────────────────┐  │
│  │ 🔴 US · New York      │  │
│   socks5://5.6.7.8:1080  │  │
│  │ Custom      ▸ Edit    │  │
│  └───────────────────────┘  │
│                             │
│  ┌───────────────────────┐  │
│  │ ⚪ Singapore          │  │
│   socks5://9.10.11.12:1080│ │
│  │ OwlProxy    ▸ Edit    │  │
│  └───────────────────────┘  │
│                             │
│                    [＋]     │  ← FAB
└─────────────────────────────┘
```

**Key changes:**
- Status dot (green/red/gray) replaces verbose status text
- Server address in monospace font, location in regular font
- Provider shown as a subtle chip, not a separate column
- Swipe-to-delete or long-press context menu
- "Test" action becomes a small icon button (not a row)
- Add/Edit moves to a dedicated bottom sheet (already exists, keep it)
- Empty state: illustration + "Add your first proxy" CTA

**ProtonVPN reference:** `redesign/countries/ui/` (server list cards)

### 4.3 Settings Screen

**Current:** Flat list of items
**Proposed:** Grouped sections with headers (already has `SectionTitle`, enhance it)

```
┌─────────────────────────────┐
│  Settings                   │
│                             │
│  APPEARANCE                 │
│  ┌───────────────────────┐  │
│  │ 🎨 Theme        Dark ▸│  │
│  └───────────────────────┘  │
│                             │
│  CONTROLS                   │
│  ┌───────────────────────┐  │
│  │ 🔵 Floating bubble  ● │  │  ← toggle switch
│  └───────────────────────┘  │
│  ┌───────────────────────┐  │
│  │ 📱 Split tunneling  ▸ │  │
│  └───────────────────────┘  │
│                             │
│  CONNECTION                 │
│  ┌───────────────────────┐  │
│  │ 🔒 IPv6 mode   Auto ▸│  │  ← new: IPv4/IPv6/Both
│  └───────────────────────┘  │
│  ┌───────────────────────┐  │
│  │ 🌐 DNS resolver  ▸   │  │  ← new: custom DNS
│  └───────────────────────┘  │
│                             │
│  SUPPORT                    │
│  ┌───────────────────────┐  │
│  │ 📋 Debug logs       ▸ │  │  ← new: view/share logs
│  └───────────────────────┘  │
│                             │
│  ABOUT                      │
│  ┌───────────────────────┐  │
│  │ 📋 Version    1.2.1  │  │
│  ├───────────────────────┤  │
│  │ ⭐ Rate this app     │  │  ← new: Play Store link
│  ├───────────────────────┤  │
│  │ 🐙 GitHub            │  │  ← new: repo link
│  └───────────────────────┘  │
└─────────────────────────────┘
```

**Key changes:**
- Settings grouped into named sections with consistent spacing
- Each section is a `SettingsGroup` card with rounded corners
- Toggle items show state inline (● for on, ○ for off)
- Navigation items show chevron (▸)
- New items: IPv6 mode, DNS resolver, Rate app, GitHub link
- About section at bottom with version + links

**ProtonVPN reference:** `redesign/settings/ui/Settings.kt`

### 4.4 Split Tunneling Screen

**Current:** Functional but plain
**Proposed:** Cleaner header + search + grouped app list

```
┌─────────────────────────────┐
│  ← Split Tunneling         │
│                             │
│  ┌───────────────────────┐  │
│  │ Mode: [Route ▸ Bypass]│  │  ← segmented button
│  └───────────────────────┘  │
│                             │
│  🔍 Search apps...         │  ← search bar
│                             │
│  A                         │  ← alphabetical section header
│  ┌───────────────────────┐  │
│  │ 📱 Chrome          ●  │  │
│  ├───────────────────────┤  │
│  │ 📱 Discord          ●  │  │
│  └───────────────────────┘  │
│                             │
│  D                         │
│  ┌───────────────────────┐  │
│  │ 📱 Drive            ○  │  │
│  └───────────────────────┘  │
│  ...                        │
└─────────────────────────────┘
```

**Key changes:**
- Segmented button for mode selection (Route / Bypass)
- Search bar at top (not inline in the list)
- Alphabetical section headers
- App icons loaded via Coil/Glide for better visuals
- Toggle switch per app (already exists, keep it)

**ProtonVPN reference:** `redesign/settings/ui/SplitTunneling.kt`

---

## 5. Design System Updates

### 5.1 Color tokens (keep monochrome, add semantic states)

```kotlin
// Existing — keep
val Connected = Color(0xFF22C55E)    // green-500
val Error = Color(0xFFEF4444)        // red-500
val Idle = Color(0xFFFF6B00)         // brand orange

// Add — status ring animation
val Connecting = Color(0xFFF59E0B)   // amber-500 (pulsing)
val Disabled = Color(0xFF71717A)     // zinc-500

// Add — surface variants
val SurfaceElevated = Color(0xFFF4F4F5) // zinc-100 (light)
val SurfaceElevated = Color(0xFF18181B) // zinc-900 (dark)
```

### 5.2 Component tokens

| Token | Value | Usage |
|---|---|---|
| `CardCornerRadius` | 16.dp | All cards |
| `CardPadding` | 16.dp | Inner card padding |
| `SectionSpacing` | 24.dp | Between sections |
| `ItemSpacing` | 12.dp | Between items in a section |
| `PillCornerRadius` | 100.dp | CTA buttons, chips |
| `StatusDotSize` | 12.dp | Profile status indicators |
| `IconSize` | 24.dp | Standard icon size |
| `HeroRingSize` | 200.dp | Connection state ring |

### 5.3 Typography (already good, just organize)

```
Hero status:    Geist SemiBold 24sp
Section header: Geist Medium 12sp (uppercase, letter-spaced)
Card title:     Geist Medium 16sp
Card subtitle:  Geist Regular 14sp
Body:           Geist Regular 14sp
Mono (server):  Geist Mono Regular 13sp
Caption:        Geist Regular 12sp
```

### 5.4 Animation specs

| Animation | Duration | Easing |
|---|---|---|
| Status color crossfade | 300ms | FastOutSlowIn |
| Connection ring pulse | 1500ms | Linear (infinite) |
| Card enter | 300ms | FastOutSlowIn + 0.05 delay |
| Bottom sheet | 350ms | FastOutSlowIn |
| Button press scale | 100ms | Linear |
| Status text change | 200ms | FastOutSlowIn |

---

## 6. New Components to Build

| Component | Description | Replaces |
|---|---|---|
| `ConnectionHero` | Full-width animated status card with ring, IP, location | `ConnectionCard` (enhanced) |
| `StatusRing` | Animated circular progress/pulse around status | New |
| `StatsRow` | Horizontal row of upload/download/time stats | `DataUsageCard` (compact) |
| `ProfileListItem` | Compact profile card with status dot, server, provider | `ProxyCard` (simplified) |
| `EmptyState` | Illustration + CTA for empty lists | New |
| `SectionHeader` | Uppercase section divider | `SectionTitle` (enhanced) |
| `SearchBar` | Search input with icon | New (for split tunneling) |
| `DebugLogsScreen` | Log viewer + share (Compose) | New (ProtonVPN-inspired) |

---

## 7. Debug Logs Feature (ProtonVPN-inspired)

### 7.1 What ProtonVPN does
- Custom `ProtonLogger` writes to rolling log files (Logback, 300KB max, 2 files)
- `LogActivity` shows logs in a RecyclerView (monospace, 9sp)
- Share button creates a combined log file and opens system share sheet
- Log format: `{timestamp} | {level} | {category}:{event} | {message}`
- Debug builds also write to Logcat; release builds only write to files

### 7.2 What we need (simpler)

**No custom logging framework needed.** We already have Logcat. Just add:

| Component | Purpose |
|---|---|
| `DebugLogsScreen` | Compose screen showing logcat output |
| `LogCollector` | Utility to capture logcat for our package |
| Settings entry | "Debug logs" in a new SUPPORT section |

### 7.3 Implementation

**LogCollector.kt** — captures logcat filtered to our package:
```kotlin
object LogCollector {
    fun collectLogs(context: Context): String {
        val process = Runtime.getRuntime().exec(
            arrayOf("logcat", "-d", "-t", "1000", "--pid=${android.os.Process.myPid()}")
        )
        return process.inputStream.bufferedReader().readText()
    }

    fun shareLogs(context: Context, logs: String) {
        val file = File(context.cacheDir, "kiloproxy_logs.txt")
        file.writeText(logs)
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.provider", file)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, "Share logs"))
    }
}
```

**DebugLogsScreen.kt** — Compose screen:
```kotlin
@Composable
fun DebugLogsScreen(onBack: () -> Unit) {
    var logs by remember { mutableStateOf("") }
    LaunchedEffect(Unit) { logs = LogCollector.collectLogs(context) }

    Scaffold(
        topBar = { TopAppBar(title = { Text("Debug Logs") }, onBack) }
    ) { padding ->
        Column(Modifier.padding(padding)) {
            // Share button
            Button(onClick = { LogCollector.shareLogs(context, logs) }) {
                Icon(Icons.Default.Share, "Share")
                Text("Share logs")
            }
            // Log viewer
            LazyColumn {
                item {
                    Text(logs, fontFamily = FontFamily.Monospace, fontSize = 10.sp)
                }
            }
        }
    }
}
```

### 7.4 Settings entry
Add to SettingsScreen in a new "SUPPORT" section:
```
SUPPORT
┌───────────────────────┐
│ 📋 Debug logs       ▸ │  ← opens DebugLogsScreen
└───────────────────────┘
```

**ProtonVPN reference:** `ui/drawer/LogActivity.kt`, `redesign/settings/ui/DebugTools.kt`

---

## 7. Implementation Phases

### Phase 1: Foundation (1-2 days)
- [ ] Update `Color.kt` with new semantic tokens
- [ ] Create `Theme.kt` elevation/surface variants
- [ ] Build `StatusRing` composable (animated)
- [ ] Build `StatsRow` composable
- [ ] Build `EmptyState` composable

### Phase 2: Home Screen (2-3 days)
- [ ] Redesign `StatusScreen` → `HomeScreen` with `ConnectionHero`
- [ ] Add connection state animations (ring pulse, color crossfade)
- [ ] Add recent profiles quick-access list
- [ ] Move profile selector to top bar / bottom sheet
- [ ] Add animated IP/location reveal on connect

### Phase 3: Profiles Screen (1-2 days)
- [ ] Redesign `ProxiesScreen` → `ProfilesScreen`
- [ ] Simplify `ProxyCard` → `ProfileListItem`
- [ ] Add empty state illustration
- [ ] Add swipe-to-delete or long-press menu
- [ ] Extract `AddEditProxySheet` into its own file (currently inline)

### Phase 4: Settings + Split Tunneling (1 day)
- [ ] Group settings into elevated cards with section headers
- [ ] Add new settings items (IPv6 mode, DNS, Rate, GitHub)
- [ ] Improve split tunneling with segmented mode selector
- [ ] Add search bar to split tunneling

### Phase 5: Polish (1-2 days)
- [ ] Add `AnimatedVisibility` transitions between screens
- [ ] Add `Crossfade` for connection state changes
- [ ] Add haptic feedback on connect/disconnect
- [ ] Test on multiple screen sizes (phone + tablet)
- [ ] Remove dead code (`ProfileFragment.kt`, `main.xml`, old `AppSelector.kt`)

---

## 8. Files to Modify

| File | Action |
|---|---|
| `ui/theme/Color.kt` | Add semantic color tokens |
| `ui/theme/Theme.kt` | Add surface variants |
| `ui/screens/StatusScreen.kt` | Rewrite → `HomeScreen.kt` |
| `ui/screens/ProxiesScreen.kt` | Rewrite → `ProfilesScreen.kt` |
| `ui/screens/SettingsScreen.kt` | Restructure with grouped sections |
| `ui/screens/SplitTunnelingScreen.kt` | Add search bar, segmented mode |
| `ui/components/ConnectionCard.kt` | Enhance → `ConnectionHero` |
| `ui/components/DataUsageCard.kt` | Replace with `StatsRow` |
| `ui/components/ProxyCard.kt` | Simplify → `ProfileListItem` |
| `ui/navigation/AppNavigation.kt` | Update routes if screen names change |
| `ProfileFragment.kt` | Delete (dead code, 624 lines) |
| `main.xml` | Delete (dead code) |
| `AppSelector.kt` | Delete (replaced by SplitTunnelingScreen) |

---

## 9. References

| Resource | Link |
|---|---|
| ProtonVPN Android | `github.com/ProtonVPN/android-app` |
| ProtonVPN Home screen | `redesign/home_screen/ui/Home.kt` |
| ProtonVPN Settings | `redesign/settings/ui/Settings.kt` |
| ProtonVPN Server list | `redesign/countries/ui/` |
| Material 3 Motion | `m3.material.io/styles/motion/overview` |
| Compose Animation | `developer.android.com/develop/ui/compose/animation` |
