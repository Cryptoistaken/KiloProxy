# KiloProxy — Agent Rules

## Build (mandatory)
- Use ONLY the GitHub builder (`.github/workflows/build.yml`). Never build locally on this machine.
- After pushing code to `master`, check the workflow run status ONCE every 30 seconds until it finishes.
- On failure: read the failing step, fix the code, commit, and push again.
- On success: proceed with download/install per below.

## Download & Install
- Always download and install the **`app-arm64-v8a-release.apk`** from the `app-release` artifact of the successful run.
- Fresh-download to a clean directory before installing (stale APKs caused version/signature mismatch before).
- Since the persistent release keystore (GitHub secrets `RELEASE_KEYSTORE_*`) was introduced, every build is signed with the SAME key and `versionCode` increases monotonically (CI `GITHUB_RUN_NUMBER` + 100). Updates are install-overs and PRESERVE all app data — never uninstall just to update.

### Install flow
1. Check if ADB device `localhost:5557` is alive (`adb devices` → shows `device`).
2. If alive:
   - Install over the old app WITHOUT uninstalling, so profiles/usage data are preserved:
     `adb -s localhost:5557 install -r <apk>`
   - Only uninstall first if a signature mismatch or downgrade is reported:
     - `INSTALL_FAILED_UPDATE_INCOMPATIBLE` / `INSTALL_FAILED_SIGNATURE` → signature differs (one-time migration from pre-keystore builds, or a different ABI build).
     - `INSTALL_FAILED_VERSION_DOWNGRADE` → installed versionCode is higher (e.g. a different ABI artifact); uninstall, or pull the matching ABI.
   - If not installed, install directly.
   - Verify with `adb -s localhost:5557 shell dumpsys package com.kiloproxy.app`.
3. If NOT alive: download the APK anyway, then STOP and wait for the user. Do NOT start any emulator/AVD on your own.
   - The user may skip the install, or start the emulator and tell you to install.
   - When the user later says to install after starting the device: install with `adb -s localhost:5557 install -r <apk>`; only uninstall first on signature/downgrade errors.

## Device notes
- App package: `com.kiloproxy.app`. Device ABI: supports `arm64-v8a`.
- Cross-ABI versionCode mismatch causes `INSTALL_FAILED_VERSION_DOWNGRADE` — install the ABI that matches the device; only uninstall before switching ABIs.
- Signature mismatch → the installed app was signed with an older key (pre-keystore ephemeral CI key, or a different ABI build); uninstall once, then all future updates install over cleanly.

## State Snapshot & Restore

Before making any major changes (UI redesign, architecture changes, etc.),
always snapshot the current working state so you can restore it later.

### Creating a snapshot
```bash
# Tag the current commit with a descriptive name
git tag -a pre-ui-redesign -m "Working state before UI redesign"

# Push the tag to remote
git push origin pre-ui-redesign
```

### Listing available snapshots
```bash
# List all tags
git tag -l

# List tags with their commit dates
git tag -l --sort=-creatordate
```

### Restoring a snapshot
```bash
# Option 1: Reset hard to a tagged state (DESTRUCTIVE — discards all changes)
git checkout pre-ui-redesign
git checkout -b restore-from-pre-ui-redesign
# Now you're on a new branch at the old state

# Option 2: Create a branch from a tag (SAFE — preserves current work)
git checkout -b ui-redesign-attempt-1 pre-ui-redesign
# You now have a branch with the old state

# Option 3: Cherry-pick specific commits from a snapshot
git log pre-ui-redesign..HEAD --oneline  # see what changed since snapshot
git revert <commit-hash>                  # undo a specific commit
```

### Tag naming convention
- `pre-<feature-name>` — before starting a feature (e.g. `pre-ui-redesign`)
- `stable-<date>` — known working release (e.g. `stable-2026-08-07`)
- `post-<feature-name>` — after completing a feature (e.g. `post-ui-redesign`)

### Existing snapshots
| Tag | Commit | Date | Description |
|---|---|---|---|
| `pre-ui-redesign` | `397d4b0` | 2026-08-07 | Engine intact, CI passing, floating bubble fixed. Use this to restore before any UI redesign work. |
| `pre-proton-settings` | (pre-proton-settings commit) | 2026-08-12 | Working state before ProtonVPN-style settings redesign (UI only). |
| `pre-netshield` | (pushed) | 2026-08-12 | Before NetShield Phase 1 (pdnsd exclude-list DNS blocking). |

> **One-time (do before the notification/dot pass):** tag the current commit as `pre-notif-and-dot-fixes` before this UI pass starts — `git tag -a pre-notif-and-dot-fixes -m "Before notification/dot fixes"` then `git push origin pre-notif-and-dot-fixes`. Add it to the table above once created.

### Quick restore (pre-ui-redesign)
```bash
# Safe restore — creates a new branch from the snapshot
git checkout -b ui-redesign pre-ui-redesign

# If you need to go back to THIS commit directly (destructive)
git reset --hard 397d4b0
```

### Important notes
- Tags are lightweight and don't affect branch history.
- Always push tags to remote (`git push origin <tag>`) so they survive local disasters.
- The `DesignPlan.md` file in the repo root describes the UI redesign plan.
- Engine code (`SocksVpnService.kt`, `IVpnService.aidl`, `Utility.kt`, `ProfileManager.kt`) must never be modified by UI changes.

## Filesystem Map & References (KEEP UPDATED)

> **Rule:** Whenever the repo structure changes (files/dirs added, moved, renamed, or deleted), update this map in the same commit. Read this section first for fast orientation instead of re-scanning the tree.

### Root
| Path | Purpose |
|---|---|
| `AGENTS.md` | This file — agent rules, build/install flow, snapshots, filesystem map |
| `DesignPlan.md` | UI redesign plan (referenced by UI work) |
| `build.gradle` | Root Gradle build (plugins: android.application, Kotlin compose) |
| `settings.gradle` / `gradle.properties` / `gradle/wrapper/gradle-wrapper.properties` | Gradle config (Gradle 9.4.1, AGP 9.2.1, Kotlin 2.2.10, Java 17) |
| `.github/workflows/build.yml` | **ONLY** build entry point (CI GitHub Actions; never build locally) |
| `.keystore-backup/` | Local keystore backup — signing handled via GitHub secrets in CI |
| `.gitignore` | Ignorable paths |

### `app/build.gradle` (app module)
- compileSdk 34, minSdk 21, **targetSdk 34** (Play deadline 2026-08-31 → 36)
- Monotonic `versionCode`: CI `GITHUB_RUN_NUMBER + 100`, local `git commit count + 100`
- Per-ABI versionCode override: `abi_rank * 67 + base` (arm7=1, arm64=2, x86=3, x86_64=4)
- ABIs: `armeabi-v7a`, `arm64-v8a`, `x86`, `x86_64` (+ universal) via `-Pabi=` split
- Native: ndkBuild via `src/main/jni/Android.mk`, NDK 27.0.12077973, `useLegacyPackaging = true`
- Signing: persistent release key from CI env `KILO_KEYSTORE_*`, else debug
- R8 minify+shrink on release; Java 17; Compose BOM `2024.10.01`, material3, navigation-compose 2.8.3, lifecycle 2.8.6, activity 1.9.x, appcompat 1.6.1, material 1.11.0, security-crypto 1.1.0-alpha06
- `tasks.configureEach` copies pdnsd/tun2socks `.so` from `build/intermediates/cxx` → `src/main/jniLibs`

### Kotlin source — `app/src/main/java/net/typeblog/socks/`
| File | Responsibility |
|---|---|
| `MainActivity.kt` | Compose host activity, entry point, launcher |
| `ProfileFragment.kt` | Legacy fragment UI (older pre-Compose screen) |
| `SocksApplication.kt` | Application class (init, context wiring) |
| `SocksVpnService.kt` | **Engine** — VpnService + tun2socks/pdnsd spawn, tunnelling, notifications, stats, IP check. NEVER modify for UI. |
| `FloatingControlService.kt` | Floating bubble (60dp) + flag pill overlays, long-press popup; WindowManager, SYSTEM_ALERT_WINDOW |
| `BubbleMenuOverlay.kt` | Popup overlay shown near bubble: country list, search, positioning; window params/IME handling |
| `BootReceiver.kt` | BOOT_COMPLETED auto-start receiver |
| `AppSelector.kt` | Per-app selection list adapter |
| `System.kt` | JNI bridge (sendfd) |

Notes on the merged notification/dot pass:
- `SocksVpnService.kt` — reuses the shared "floating control" notification (id 2, channel `floating_control`) instead of a separate VPN notification; user sees only ONE notification. `stopMe` uses DETACH (not REMOVE) so the shared FGS notification is not torn down.
- `FloatingControlService.kt` — notification uses custom RemoteViews: always-visible centered pill with Connect/Disconnect; connected bubble color is now `#DC2626` (light-theme `LightError`) instead of `DarkError #EF4444`.
- `BubbleMenuOverlay.kt` / `bubble_country_row.xml` — connected-dot now positioned where the dial code was shown.

### `.../util/`
| File | Responsibility |
|---|---|
| `Constants.kt` | Intent extras, preference keys, actions |
| `Countries.kt` | Country list for bubble menu |
| `LogCollector.kt` | In-app log capture (Debug Logs screen) |
| `Profile.kt` / `ProfileFactory.kt` | Profile data class + factory (pre-defined server profiles) |
| `ProfileManager.kt` | **ENGINE** — profile CRUD, prefs. NEVER modify for UI |
| `ProxyProviders.kt` | Proxy provider catalog (proxy list presets) |
| `Routes.kt` | VpnService route selection (route config) |
| `SocksTester.kt` | SOCKS5 liveness/health probe |
| `Utility.kt` | **ENGINE** — pdnsd conf, ip lookups, misc helpers. NEVER modify for UI |

### `.../ui/`
- `components/` — Compose components: AppToggleItem, ConnectionCard, ConnectionStatusCard, DataUsageCard, ProxyCard, SettingsItem, ThemePickerDialog
  - `ConnectionCard.kt` — Connect/Disconnect button is now text-only (icon removed); spinner shown while connecting.
- `navigation/AppNavigation.kt` — NavHost destinations
- `screens/` — DebugLogsScreen, ProxiesScreen, SettingsScreen, SplitTunnelingScreen, StatusScreen
- `theme/` — Color, Fonts, Theme, Type (Compose theming, Geist fonts)
- `viewmodel/VpnViewModel.kt` — Vpn state, AIDL binding

### Native C — `app/src/main/jni/`
| Area | Purpose |
|---|---|
| `Android.mk`, `Application.mk` | ndkBuild top-level build files |
| `badvpn/` | tun2socks engine (full badvpn fork: tun2socks/, lwip/ stack, client/, system/, etc.) |
| `pdnsd/` | pdnsd DNS proxy source |
| `libancillary/` | ancillary fd passing (sendfd recvfd) |
| `system.cpp` | JNI — `sendfd()` used by VPN tunnel setup |

### AIDL
- `app/src/main/aidl/net/typeblog/socks/IVpnService.aidl` — **ENGINE** binder interface between activity/UI and SocksVpnService (DO NOT touch for UI)

### Manifest — `app/src/main/AndroidManifest.xml`
- Permissions: INTERNET, RECEIVE_BOOT_COMPLETED, FOREGROUND_SERVICE, FOREGROUND_SERVICE_SPECIAL_USE, POST_NOTIFICATIONS, QUERY_ALL_PACKAGES (split-tunnel), SYSTEM_ALERT_WINDOW, VIBRATE
- `SocksVpnService`: `process=":vpn"`, `exported=true`, BIND_VPN_SERVICE, fgType specialUse + subType property
- `FloatingControlService`: specialUse FGS
- `BootReceiver`: exported=false, BOOT_COMPLETED
- `FileProvider` authorities `${applicationId}.provider`, paths `@xml/file_paths`
- `networkSecurityConfig="@xml/network_security_config"`

### Resources — `app/src/main/res/`
- `assets/` — (empty; NetShield blocklists were removed — NetShield is now cloud-only)
- `layout/` — `main.xml`, `app_item.xml`, `bubble_menu.xml` (bubble popup panel), `bubble_country_row.xml`, `notification_action.xml` (RemoteViews layout for the notification Connect/Disconnect pill)
- `drawable/` — lucide_* icons, menu_panel_bg, search_input_bg, signal_dot, logo_*, launcher, notification_pill (pill button background)
- `font/` — Geist family TTFs (bold/medium/mono/pixel etc.)
- `mipmap-*/` — legacy + adaptive launcher icons
- `values/` — strings.xml (+`values-ru/strings.xml`), arrays.xml, styles.xml, pdnsd.xml, ruroute.xml, simpleroute.xml, ic_launcher_background
- `xml/` — network_security_config.xml (cleartext/trust config), file_paths.xml (FileProvider), settings.xml (preference screen XML)
