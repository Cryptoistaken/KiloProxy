# KiloProxy — Full Codebase Audit Report

**Date:** 2026-08-28
**Commit:** `HEAD` (master)
**Scope:** `app/src/main/java`, `app/src/main/res`, `app/build.gradle`, `.github/workflows/build.yml`, `AndroidManifest.xml`, native `jni/`
**Method:** 5 parallel sub-agents (prefs, engine, UI, build/security, threading) + manual cross-check. All findings verified against source at `file:line`.

> Original trigger: *split tunneling resets to Off after close/reopen* — confirmed as `SocksApplication.kt:11` `readAgain=true` anti-pattern. This report lists that bug plus 52 additional defects in the same style.

---

## Executive Summary

| Severity | Count | Ship-blocker? |
|----------|-------|---------------|
| **Critical** | 9 | Yes — data loss / crash / Play rejection |
| **High** | 22 | Yes — VPN drop, security, battery, hot-loop |
| **Medium** | 18 | Fix before next release |
| **Low** | 7 | Debt / a11y / hardening |
| **Total** | **56** | |

**Top 3 to fix today:**
1. `SocksApplication.kt:11` + `ProfileManager.kt:19` — prefs wipe + `EncryptedSharedPreferences` crash on boot (Critical).
2. `SocksVpnService.kt:348` `onStartCommand` returns `0` + `SocksVpnService.kt:621` whitelist branch missing `addDisallowedApplication(packageName)` — tunnel deadlocks, foreground service ANR (Critical).
3. `Utility.kt:400` global `Authenticator.setDefault` race + `Utility.kt:287` 4-thread `checkPublicIp` leak — wrong proxy creds, thread leak (Critical).

---

## 1. Critical — fix before release

### C1. Split-tunneling prefs reset (reported bug)
- **Files:** `SocksApplication.kt:11`, `res/xml/settings.xml:102-104`, `util/Constants.kt:38-40`, `ui/screens/SplitTunnelingScreen.kt:111,220`, `util/Utility.kt:237`
- **What:** `PreferenceManager.setDefaultValues(this, R.xml.settings, true)` with `readAgain=true` re-inflates `settings.xml` on **every** `Application.onCreate` (main + `:vpn` process). `CheckBoxPreference` default is `false` even without `android:defaultValue`. Each launch overwrites `adv_per_app` (and `adv_app_bypass`/`adv_app_list`) → UI shows Off, `Utility.startVpn` gets `perApp=false`.
- **Fix:** `true` → `false`. One-line diff. Ponytail ultra: deletion over addition.
```kotlin
- PreferenceManager.setDefaultValues(this, R.xml.settings, true)
+ PreferenceManager.setDefaultValues(this, R.xml.settings, false)
```

### C2. EncryptedSharedPreferences hard crash — no recovery
- **Files:** `util/ProfileManager.kt:19-31`, `util/ProfileFactory.kt:22-31`, `BootReceiver.kt:18`
- **What:** `catch (Exception) throw RuntimeException` in singleton `init`. Keystore corruption / lock-screen reset / restore on new device / Direct Boot before `isUserUnlocked` → every cold start + `:vpn` process crashes. `BootReceiver:18` triggers it pre-unlock.
- **Fix:** Catch, delete `MasterKey` + `EncryptedSharedPreferences` file, recreate; guard `BootReceiver` with `UserManager.isUserUnlocked`; use `context.applicationContext`, `synchronized`/`volatile` singleton.

### C3. VPN engine ANR — wrong `onStartCommand` return
- **Files:** `SocksVpnService.kt:348-359`
- **What:** `intent==null` (START_STICKY restart) and `mRunning==true` both `return 0` (`START_STICKY_COMPAT`). System kills notification+service; restart delivers null intent, early-return never re-creates foreground notification → `ForegroundServiceDidNotStartInTime` ANR + silent drop of profile-switch requests.
- **Fix:** `return START_STICKY` in both branches; handle null intent by restoring `mProfileName` from prefs or `stopSelf`.

### C4. Whitelist mode deadlocks — own UID routed into tun
- **Files:** `SocksVpnService.kt:621-684` (`configure:666-675`)
- **What:** `!perApp` and `bypass` branches call `addDisallowedApplication(packageName)` so `tun2socks`/`pdnsd` (same UID) bypass tun. Whitelist (`else`) branch only `addAllowedApplication(selected)` and skips own package → own UID stays inside tun with no DNS/proxy path → `getaddrinfo` black-hole at startup.
- **Fix:** Add `addDisallowedApplication(packageName)` in whitelist branch (same try/catch).

### C5. Cross-process `SharedPreferences` for usage + IPC — stale cache
- **Files:** `SocksVpnService.kt:553-577`, `SocksVpnService.kt:319-332`, `ui/components/ProxyCard.kt:71`, `ui/screens/StatusScreen.kt:189`
- **What:** `:vpn` writes `usage_rx_${name}_$suffix` every 5s via `apply()` (async); UI process reads same `DefaultSharedPreferences` file via per-process in-memory cache (no `MODE_MULTI_PROCESS`). Totals drop to 0 after disconnect; `usageKeySuffix` sanitizes `[^A-Za-z0-9]→_` → `"Test Profile"` and `"Test_Profile"` alias.
- **Fix:** Stop using `SharedPreferences` for IPC. Use `IVpnService.getReceivedBytes()` AIDL (already exists) or `ContentProvider`/`DataStore`. If kept, use distinct file + `commit()` + `contains()` guard.

### C6. Global `Authenticator.setDefault` race leaks creds
- **Files:** `util/Utility.kt:398-405`, `SocksVpnService.kt:211-298`, `util/SocksTester.kt:39`
- **What:** 4 parallel `checkPublicIp` threads each call `Authenticator.setDefault(object...{user/pass})` — JVM-global singleton, last writer wins, other probes use wrong creds; creds persist after VPN stop for `UpdateChecker`/`track` requests.
- **Fix:** Per-connection `Proxy-Authorization: Basic ...` header, never `setDefault`; reset to `null` in `finally`.

### C7. ProfileManager/ProfileFactory singleton data race
- **Files:** `util/ProfileManager.kt:128-137`, `util/ProfileFactory.kt:23-31`, `MainActivity.kt:122`
- **What:** `sInstance` without `volatile`/`synchronized` → two instances, half-constructed `mPref`/`mProfiles`. `mProfiles:ArrayList` mutated without lock → `ConcurrentModificationException` when `MainActivity:syncKiloProxyProxies` (IO) races UI thread. Leaks `Activity` context.
- **Fix:** `synchronized` getInstance + `volatile`, `context.applicationContext`, `CopyOnWriteArrayList` or lock.

### C8. `QUERY_ALL_PACKAGES` Play rejection
- **Files:** `AndroidManifest.xml:10`
- **What:** `QUERY_ALL_PACKAGES` with `tools:ignore` silences lint. Split-tunneling `getInstalledApplications` is not an accepted Play core-use; update will be rejected.
- **Fix:** Replace with `<queries><intent><action android:name="android.intent.action.MAIN"/></intent></queries>` + filter by launch intent.

### C9. Hardcoded URLs + insecure update path
- **Files:** `util/UpdateChecker.kt:28,108,144`, `util/Utility.kt:288,323`, `res/xml/network_security_config.xml:3`
- **What:** GitHub `browser_download_url` trusted without `https` check; http `ip-api.com`/`ipwho.is` allow MITM; `ipwho.is` not whitelisted → 2/4 IP providers always fail blocked by `NetworkSecurityPolicy`; FileProvider `cache-path path="/"` over-broad.
- **Fix:** Validate `apkUrl.startsWith("https://")`, use `HttpsURLConnection` + pin, narrow `file_paths.xml:3` to `path="update.apk"`, switch IP providers to `https`.

---

## 2. High — fix in same release

| # | File:Line | Title | Root cause |
|---|-----------|-------|------------|
| H1 | `SocksVpnService.kt:462-551` | `stopMe` no early-exit guard → double teardown race | `mRunning` checked late; every binder/notification/ip-check/tun2socks path re-enters → double `destroy()`, double `unregisterReceiver`, double `stopSelf`. Add `if(!mRunning && reason!="on_destroy") return` + `synchronized`. |
| H2 | `SocksVpnService.kt:124-169` | `mTunnelUp` not volatile + CONNECTED split-brain | `Boolean` read on bg thread vs main; `connectedSince` set at tunnel-up but bubble/VM require `connectedSince>0 && proxyVerified`. Use `volatile`/`AtomicBoolean`, single CONNECTED definition. |
| H3 | `SocksVpnService.kt:124` / `FloatingControlService.kt:802` | Shared `CHANNEL_ID="floating_control"` / `NOTIFICATION_ID=2` in both processes | Notifications per-package, second `notify()` overwrites first; `stopForeground(DETACH)` detaches other's notification. Single `NotificationHost` owner. |
| H4 | `SocksVpnService.kt:489` / `Utility.kt:132-166` | `killPidFile` blind `kill $pid` may kill recycled PID | Parses 512B file, `kill` without UID check / `-9`; `stopMe` does `destroy()` + `killPidFile` + NetShield reconcile re-launches. Validate PID namespace, use `Process.destroy()` only. |
| H5 | `res/xml/settings.xml` ↔ `Constants.kt` ↔ `Profile.kt:17` | Triple key-schema mismatch, dead pref seeding | `settings.xml` seeds `server_ip` etc. never read; `Profile` encrypts `{prefix}server`; split-tunnel has two sources of truth (`perapp` vs `adv_per_app`). Delete legacy `PreferenceScreen` keys or single owner store. |
| H6 | `ProfileManager.kt:71-84` `addProfile` / `93-99` `removeProfile` | Persisted profile list newline-serialization invariant broken | `removeAt(0)` vs `drop(1)` vs `split("\n")` with empty seed `[""]`; names with `\n` collapse. Use JSON array or `StringSet`. |
| H7 | `Profile.kt:182` `prefPrefix` collision | `replace("_","__").replace(" ","_")` not bijective (`a_b` vs `a__b`). Use URL-encode or single `KEYS` constant. |
| H8 | `SocksVpnService.kt:319-332,450` | `OnSharedPreferenceChangeListener` cannot cross processes | `:vpn` listens for UI `apply()` writes — stale cache, OEM non-propagation. Use explicit `Intent`/broadcast. |
| H9 | `ui/screens/SplitTunnelingScreen.kt:103,111,133` + `SettingsScreen.kt:70` | `remember { prefs.getBoolean }` stale snapshots | `persistedList`/`splitEnabled`/`bypassMode`/`netShieldEnabled` never observe external writes (`saveState=true` back-stack). Add `DisposableEffect(registerOnSharedPreferenceChangeListener)` (as `Theme.kt:64` does) or `AppSettings` flow. |
| H10 | `ui/screens/SplitTunnelingScreen.kt:117` | Installed apps loaded once (`LaunchedEffect(Unit)`) | New installs invisible; `QUERY_ALL_PACKAGES` grant never refreshes. Use `LifecycleEventObserver ON_RESUME` refresh. |
| H11 | `ui/screens/ProxiesScreen.kt:107` | Focus-poll hot-spins without delay (`continue` before `delay(3000)`) | `uid==null` / `!ok` / `arr==null` bypass delay → tight HTTP loop. Move `delay` before `continue`. |
| H12 | `ui/screens/StatusScreen.kt:97` | 20s `isConnecting` timeout tied to composition | `LaunchedEffect(isConnecting)` cancelled on tab switch → hang forever. Move to `VpnViewModel`. |
| H13 | `ui/screens/DebugLogsScreen.kt:80` | `Toast` from `Dispatchers.IO` crashes | Needs `withContext(Main)`. |
| H14 | `ui/screens/ProxiesScreen.kt:242` | Unscoped `CoroutineScope(IO).launch` leaks | Delete POST survives popBackStack. Use `rememberCoroutineScope()`. |
| H15 | `util/Utility.kt:285-377` | `checkPublicIp` 4 Threads + 10s `CountDownLatch` leak, ignore return | Non-daemon threads linger, late `compareAndSet` after timeout lost, `catch` swallowed. Use `Executor` + `withTimeout` + cancellation. |
| H16 | `util/Utility.kt:380-424` / `132-166` | `BufferedReader`/`FileInputStream` leaks | `close()` only on happy path; `errorStream` never drained. Use `use{}`. |
| H17 | `SocksVpnService.kt:211-296` | Unbounded `Thread{checkPublicIp; probeProxy}.start()` per poll | Every 0.5s-30s without limit, threads overlap. Use single `ExecutorService`. |
| H18 | `VpnViewModel.kt:164-171` / `FloatingControlService.kt:706` | `rebinding`/`rebindInFlight` strands rebind forever | Success keeps flag `true` until `onServiceConnected`; early `onServiceDisconnected` never retries; anonymous `postDelayed` runnable leaked after `onDestroy`. AtomicBoolean + track token to `removeCallbacks`. |
| H19 | `MainActivity.kt:73-88,122` | `ActivityLifecycleCallbacks` + `CoroutineScope(IO).launch` leak + Activity context on IO | New scope per resume, holds `Activity` past lifecycle. Use `lifecycleScope` + `applicationContext`. |
| H20 | `util/KiloProxyAuth.kt:5-47` / `MainActivity.kt:124` / `SocksVpnService.kt:946` | Auth tokens plaintext + `Random` device ID in URL query | `DefaultSharedPreferences` XML plaintext, `kotlin.random` predictable, `GET ?token=` leaks in logs/proxy. Use `EncryptedSharedPreferences` + `SecureRandom` + `Authorization` header POST. |
| H21 | `.github/workflows/build.yml:11,22` | Actions not SHA-pinned + `contents: write` overly broad | `checkout@v7` etc. mutable tags; PR gets `write`. Pin to SHA, scope to `build` job. |
| H22 | `app/build.gradle:78,93` | Release fallback silently to debug keystore | Empty `signingConfigs.release` when persistent key missing → next persistent build `INSTALL_FAILED_UPDATE_INCOMPATIBLE` forces uninstall. Warn on fallback. |

---

## 3. Medium — next sprint

| # | File:Line | Title |
|---|-----------|-------|
| M1 | `util/Utility.kt:236-247` | Merged tunnel logic `profile.isPerApp || prefs adv_per_app` → `getAppList`/`isBypassApp` read wrong source when global ON but profile `perapp=false`. Single typed `AppSettings` owner. |
| M2 | `util/Utility.kt:478` / `Constants.kt:56` | `PREF_RECENT_COUNTRIES` ghost key vs `filesDir/recent_countries.txt` CSV — no migration, update loses recents; `getOrCreateDeviceId` racy `apply()`. |
| M3 | `SocksVpnService.kt:539-549,965` | Receiver register/unregister asymmetry; `notification` reg in `onStartCommand`, `screenOff` cond, `screenOn` in `postStartOnMain`; `try/catch` swallowed on START_STICKY null. |
| M4 | `SocksVpnService.kt:579-591` | `showNotification` missing `setOngoing(true)` until `updateNotification` → swipe-away during startup; `STOP_FOREGROUND_DETACH` vs legacy `stopForeground(false)` orphan. |
| M5 | `SocksVpnService.kt:170-192,924` | `TrafficStats.getUidRx/Tx` double-count / `usageKeySuffix` duplicate key `"usage_rx_${name}_$suffix"`; `readTunBytes` unused. |
| M6 | `SocksVpnService.kt:808-826,972` | `sendfd` 100×50ms poll uncancelled; `stopMe` during poll still calls `postStartOnMain`; log says `5_attempts` for 100. |
| M7 | `SocksVpnService.kt:686-836,838` | `launchPdnsd` daemon `waitFor` never returns, thread leaked; `consumeProcessOutput` not closed. |
| M8 | `SocksVpnService.kt:227-296,154,971` | `Handler(mainLooper)` per-call allocation, `mStatsTick` unsynchronized, `removeCallbacks` race with `postDelayed` from bg thread. |
| M9 | `VpnViewModel.kt:199,356` | `delay(200)` poll 5×/s even backgrounded + 10 Binder calls per tick; `restartVpn` busy-polls stale `StateFlow`. Use callback/flow, backoff. |
| M10 | `ui/screens/StatusScreen.kt:82,172` | `selectedProfile` not `rememberSaveable` → rotation reset; `persistedUsage` `prefs.getLong` on main thread jank. |
| M11 | `ui/screens/SettingsScreen.kt:121` / `AuthScreen.kt:52` | `pendingFloatingStart`/`hasClicked` not `rememberSaveable` → permission flow lost on rotation. |
| M12 | `ui/screens/CountriesScreen.kt:73,84,162,302` | `defaultProfileName` stale after rename, `connectedCountryCode` not recomputed after username rewrite, `stop+delay(500)` race, missing `Role.Button` semantics. |
| M13 | `ui/screens/SplitTunnelingScreen.kt:88` | `restartJob` not cancelled on dispose → `restartVpn` fires after leave. Add `DisposableEffect(onDispose{cancel()})`. |
| M14 | `ui/components/ConnectionCard.kt:185` | `produceState` timer keeps ticking when disconnected (`now-0` = 43y). Guard `if(!isConnected) return@produceState`. |
| M15 | `ui/navigation/AppNavigation.kt:136` | `popUpTo(Countries){ inclusive=false }` after country tap → back returns to Countries loop. Use `inclusive=true`. |
| M16 | `util/Utility.kt:235-377,406` | `ip-api.com` http + mixed http/https providers winner-takes-first without quorum; Doze skip not inside `Utility`. Switch all to `https`, quorum. |
| M17 | `AndroidManifest.xml:17` / `app/build.gradle:33` | `allowBackup=false` without `dataExtractionRules` (API 31+); `usesCleartextTraffic` not explicit. |
| M18 | `app/build.gradle:149-167,26,173` | `tasks.configureEach` copies `.so` into `src/main/jniLibs` pollutes VCS; `versionCode` `67` offset tiny + `git rev-list --count` non-monotonic; `security-crypto:1.1.0-alpha06` → stable `1.1.0`. |

---

## 4. Low — debt / a11y / hardening

| # | File:Line | Note |
|---|-----------|------|
| L1 | `ui/screens/*:31,245,371`, `BubbleMenuOverlay.kt:60,279`, `FloatingControlService.kt:117,220` | Hardcoded strings (`"Restarting VPN…"`, `"No proxy…"`) not in `strings.xml`; `OnGlobalLayoutListener` + `messageHandler`/`ValueAnimator` leak if `hide()` not called / `INFINITE` animator holds `View`→`Context`. |
| L2 | `ui/components/AppToggleItem.kt:87` | `Bitmap.createBitmap` from `icon.intrinsicWidth` on main thread. Move to `produceState(IO)`. |
| L3 | `ui/components/SettingsItem.kt:45` / `NetShieldScreen.kt:53` | `clickable` without `Role.Button`/`Role.Switch`, missing `stateDescription` → TalkBack invisible. |
| L4 | `res/xml/file_paths.xml:3` | Over-broad `cache-path path="/"`. Narrow to `path="update.apk"`. |
| L5 | `app/build.gradle:173` / `gradle.properties:6` | Alpha crypto + `Xmx2048m` minimal for 4-ABI native build; `strictFullModeForKeepRules=false` weakens R8; `proguard-rules.pro:23` `-keep class net.typeblog.socks.**` disables R8. Narrow to `System` natives. |
| L6 | `app/src/main/jni/Android.mk:31,42` / `Application.mk:2` | NDK PIE/relro defaults implicit, `APP_PLATFORM android-21` stale; recommend `-Wl,-z,relro,-z,now`, bump to `23` for `arm64` BTI/PAC. |
| L7 | `.github/workflows/build.yml:50,67` | Secret via `echo "${{ secrets }}"` in `run:`; `ANDROID_NDK_HOME` uses workflow `env` context empty → `/ndk/27...`; NDK install uncached 700MB per cold runner. Use `env: B64: ...` + cache. |

---

## 5. Verification steps ( cheapest first )

1. **Prefs reset repro:** `adb shell run-as com.kiloproxy.app cat shared_prefs/com.kiloproxy.app_preferences.xml` → toggle split ON → kill app → reopen → file shows `adv_per_app` reverted if `SocksApplication:11` is `true`. Fix to `false` → survives.
2. **Engine ANR repro:** `adb shell am force-stop com.kiloproxy.app` while VPN connected → START_STICKY restart log shows `return 0` path, no notification within 10s → FGS ANR. After fix `START_STICKY`, notification reappears.
3. **Cross-process usage:** Connect, let `SocksVpnService:563` persist 30s, `adb shell dumpsys` → UI totals vs `usage_rx_*` file mismatch → after AIDL fix they match live.
4. **Build:** `./gradlew :app:assembleRelease -Pabi=arm64-v8a` + `apksigner verify --print-certs`; Play pre-launch report flags `QUERY_ALL_PACKAGES` gone after M1 fix.

---

## 6. Recommended minimal patch order (ponytail — smallest diffs first)

1. `SocksApplication.kt:11` `true`→`false` (1 char).
2. `SocksVpnService.kt:348` `return 0`→`return START_STICKY`; `SocksVpnService.kt:668` add `addDisallowedApplication(packageName)` in whitelist.
3. `Utility.kt:398` replace `Authenticator.setDefault` with per-request header.
4. `ProfileManager.kt:128` / `ProfileFactory.kt:23` add `synchronized` + `volatile` + `applicationContext`.
5. `SocksVpnService.kt:462` guard `stopMe` with `AtomicBoolean`/synchronized.
6. `AndroidManifest.xml:10` drop `QUERY_ALL_PACKAGES`, add `<queries>`.
7. The rest via iterative PRs per table.

---

*Generated by OpenCode audit (5 sub-agents) — see `design/cluster-*.md` for deep-module context. Run `gh workflow view build` after patch; APK artifact `app-arm64-v8a-release-*.apk` must install `install -r` over prior without data loss.*
