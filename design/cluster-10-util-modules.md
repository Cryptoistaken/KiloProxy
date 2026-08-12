# Cluster 10 — The 'kitchen sink' util cluster → Three deep modules + a statute

**Design doc only — no repo modifications.** Vocabulary per `codebase-design`: Module, Interface (invariants/ordering/errors/config/perf), Adapter, Seam, Depth, Leverage, Locality, deletion test, one-vs-two adapters.

---

## 0. The cluster as it stands (audit)

The cluster is a single `Utility` object (510 lines, 16 public members, 7+ responsibilities), a flat `Constants` string catalog (56 lines, ~33 keys), a 39-line `Routes`, a 69-line `LogCollector`, a 16-line `System.kt` JNI bridge, a 173-line `AppSelector` PackageManager dialog, and a `DebugLogsScreen` consumer. Verified state by symbol (grep-audited, Aug 2026):

| Symbol | Owner today | Callers | Verdict |
|---|---|---|---|
| `Utility.formatBytes` | Utility | DataUsageCard ×3, ProxyCard ×1 | → **Metrics** |
| `Utility.countryCodeToFlag` | Utility | Countries ×3, FloatingControlService ×2, ProxyCard ×1 | → **Metrics** |
| `Utility.makePdnsdConf` | Utility | SocksVpnService ×2 (start, reconcileNetshield) | → **PlatformBridge** |
| `Utility.startVpn` | Utility | BootReceiver, FloatingControlService, VpnViewModel | splits → **VpnLauncher** + **PlatformBridge.extras** |
| `Utility.netshieldPolicy` + `NetshieldPolicy` | Utility | SocksVpnService ×2 | stays out (engine policy — see §7 statute note) |
| `Utility.checkPublicIp`/`fetchPublicIp`/`IpInfo` | Utility | SocksVpnService (with SOCKS proxy auth) | IP-race cluster (Cluster 11); `IpInfo` is its cross-process schema |
| `Utility.getRecentCountries`/`addRecentCountry` | Utility | BubbleMenuOverlay, CountriesScreen, ProxiesScreen, FloatingControlService (5 calls total) | keep as tiny own module (§7) |
| `Utility.join` | Utility | ProfileManager ×3 | → ProfileManager (its serialization grammar) |
| `Utility.exec`, `exec(Array)`, `extractFile`, `readTunBytes`, `killPidFile` | Utility | **no callers** (killPidFile's pid files are never written — pdnsd/tun2socks spawn via ProcessBuilder) | **DELETE** (P0) |
| `Routes.addRoutes` | Routes | SocksVpnService.configure | → **RouteCatalog** (with configure's raw per-app logic folded in) |
| `System.sendfd` | System.kt | SocksVpnService.start (poll loop +100 attempts) | → **PlatformBridge.transferFd** (retry moved in) |
| `System.getABI`, `System.jniclose` (+ C entries `system.cpp:39,76-80`, `SystemUtil`) | System.kt | **no callers** | **DELETE** (P0, incl. native side) |
| `Constants.INTENT_*` (14 keys) | Constants | Utility.startVpn (writer), SocksVpnService (readers) | → **PlatformBridge** (private) |
| `Constants.INTENT_IP_INFO`, `INTENT_CONNECTED_SINCE` | Constants | **no users** (connectedSince crosses via AIDL) | **DELETE** |
| `Constants.ROUTE_*` (4) | Constants | Routes | → **RouteCatalog** (private) |
| `Constants.ACTION_STOP/START_VPN` | Constants | FCS (sender), SocksVpnService (receiver) | remains public (§7) |
| `Constants.PREF_PROFILE, PREF_LAST_PROFILE, PREF_SERVER_*, PREF_IPV6/UDP_*, PREF_AUTH_*, PREF_ADV_*` | Constants | ProfileManager (+Utility.startVpn, SplitTunnelingScreen) | → **ProfileManager** internal schema |
| `Constants.PREF_FLOATING_CONTROL` | Constants | MainActivity, SettingsScreen, BootReceiver | → FCS-owned schema |
| `Constants.PREF_THEME_MODE` | Constants | Theme.kt, SettingsScreen | → `ui/theme` ThemePrefs |
| `Constants.PREF_NETSHIELD_*` | Constants | Utility.netshieldPolicy | follows netshieldPolicy |
| `Constants.MASTER_KEY_ALIAS, ABI_DEFAULT, PREF_RECENT_COUNTRIES, PREF_DYNAMIC_COLORS, PREF_CONNECTIVITY_CHECK` | Constants | **no users** (recents is file-backed; colors = dead theme pref) | **DELETE** (P0) |
| `LogCollector` | LogCollector | DebugLogsScreen only | **keep as-is**, one line extracted (§5) |
| `AppSelector.show` | AppSelector | SettingsScreen split-tunneling dialog | query → **PlatformBridge.appList**; dialog chrome stays |
| Inline `"%.1f MB".format(...)` | SettingsScreen:123 | — | duplicate of formatBytes → **Metrics** |
| `FloatingControlService.formatElapsed` (mm:ss/H:MM:SS) | FCS:1185-1195 | FCS timer | → **Metrics.Duration** |

**Formatting-leftover audit result:** three distinct dialects exist today for one concept — `Utility.formatBytes` (B/KB/MB/GB), SettingsScreen's always-MB `"%.1f MB"`, and FCS's private `String.format("%d:%02d:%02d")` duration. No shared rate or percent helper exists; the only `Locale.US` discipline is ad-hoc.

---

## 1. The radical shape

```
util/
  metrics/     Metrics (module M1)      — ALL human-readable number grammar
  bridge/      PlatformBridge (M2)      — ALL native/JNI/Intent-extras/app/file boundaries
  routes/      RouteCatalog (M3)        — ALL route + per-app tunnel policy as data tables
  Constants.kt — shrunk to the statute  — manifest-level contracts only
```

Three deep modules + a statute (Constants split). Plus three *documented small exceptions* that are honest rather than force-fit: `RecentCountries`, `VpnLauncher`, and `netshieldPolicy` (§7). Per design-it-twice discipline, the rejected alternatives are in §9.

---

## 2. Module 1 — `Metrics`

### Interface (complete)

```kotlin
package net.typeblog.socks.util.metrics

sealed class MetricValue {
    data class Bytes(val bytes: Long)
    data class Rate(val bytes: Long, val periodMs: Long = 1000L)
    data class Duration(val millis: Long)
    data class Fraction(val value: Double)        // 0.0..1.0
    data class CountryFlag(val code: String)
}

interface Metrics {
    /** Format any human-readable value. Never throws. */
    fun format(value: MetricValue): String
}

/** Singleton production impl. */
object DefaultMetrics : Metrics
```

The interface is **one entry point + one sealed type**. Callers pass typed values; pre-formatted strings die.

### Invariants (the grammar, all in one place)

| Value | Grammar | Exceptions/errors |
|---|---|---|
| `Bytes(n)` | ≥1 GiB → `"%.2f GB"`; ≥1 MiB → `"%.2f MB"`; ≥1 KiB → `"%.1f KB"`; else `"$n B"` (binary units, exact copy of today's `Utility.formatBytes`) | `n < 0` → `"0 B"` (today's behavior, kept) |
| `Rate(b, ms)` | same bytes grammar + `"/s"` suffix; normalized to 1 s | `periodMs <= 0` is a programming error → **clamped to 1 ms** (never throws; documented) |
| `Duration(ms)` | ≥1 h → `"H:MM:SS"` (hours unpadded); else `"M:SS"` (today's FCS dialect, lifted verbatim) | `ms <= 0` → `"0:00"` |
| `Fraction(x)` | `x <= 0` → `"0%"`; `x >= 1` → `"100%"`; `x < 0.1` → 1 decimal (`"4.2%"`); else integer | out-of-range clamped, never throws |
| `CountryFlag(code)` | two regional-indicator code points | `code.length != 2` → `"\uD83C\uDF10"` (globe, today's behavior) |

**Config:** none today — `Locale.US` hardcoded everywhere in the current code, and the module preserves it for determinism (this is the *required configuration* fact callers must know: output is locale-stable). Future unit-system or hide-zero options would be a `configured(unitSystem)` factory — an internal seam, not exposed now.
**Ordering:** none (pure function).
**Perf:** allocation-free hot path except the returned String; called ≤ a few times/sec from stats tickers (FCS 200 ms poll, engine 1 s stats). Stateless; safe from both `:vpn` and main process.

### Seam placement & adapters

Pure in-process computation (DEEPENING category 1) — **no seam, no adapter**. `DefaultMetrics` is an object; testability comes from the type.

### Depth / leverage / locality

- One learned surface (`format(MetricValue)` + 5 sealed variants) pays back across **10 call sites in 6 files** (DataUsageCard, ProxyCard, FloatingControlService ×3, SettingsScreen, Countries.kt ×3).
- Locality: today a unit-display change (e.g. "show decimal MB", "hide 0 B") must be made in **three places with three different grammars** (Utility, SettingsScreen inline, FCS private). After: one place.
- The sealed-type move buys compiler enforcement: `formatBytes` today accepts any `Long`, so "MB everywhere" drifted in SettingsScreen. `MetricValue` makes the *kind* of human value part of the call.

### Deletion test

Delete Metrics and the complexity reappears across ≥6 sites: DataUsageCard (3 stats), ProxyCard (bytes + flag), FCS (elapsed timer at 1 Hz), SettingsScreen (APK size), Countries (3 flags) — in **3 mutually inconsistent dialects** (binary units, always-MB, H:MM:SS). That's the strongest deletion-test pass in the cluster: the module is what *prevents the dialects from existing*.

### Testability

Pure JUnit — table-driven golden tests, no Android:

```kotlin
class MetricsGoldenTest {
    @Test fun bytes() = assertFormats(
        Bytes(0) to "0 B", Bytes(1023) to "1023 B", Bytes(1536) to "1.5 KB",
        Bytes(1048576) to "1.00 MB", Bytes(1073741824) to "1.00 GB",
        Bytes(-5) to "0 B" )
    @Test fun duration() = assertFormats(
        Duration(0) to "0:00", Duration(59_999) to "0:59",
        Duration(3_600_000) to "1:00:00", Duration(36_000) to "10:00" )
    @Test fun fractionAndFlag() = assertFormats(
        Fraction(0.0417) to "4.2%", Fraction(0.999) to "100%",
        Fraction(-1.0) to "0%", CountryFlag("US") to "\uD83C\uDDFA\uD83C\uDDF8",
        CountryFlag("X") to "\uD83C\uDF10" )
    @Test fun rate() = assertFormats(Rate(1536) to "1.5 KB/s",
        Rate(0, 0) to "0 B/s" /* clamped period */)
}
```

---

## 3. Module 2 — `PlatformBridge`

### Interface (complete)

```kotlin
package net.typeblog.socks.util.bridge

data class TunnelPayload(
    val name: String,
    val server: String,
    val port: Int = 1080,
    val username: String? = null,
    val password: String? = null,
    val route: String,                        // RouteCatalog key; bridge treats as opaque
    val dns: String = "8.8.8.8",
    val dnsPort: Int = 53,
    val perApp: Boolean = false,
    val appBypass: Boolean = false,
    val appList: List<String> = emptyList(),  // newline-joined storage is prefs-layer, not here
    val ipv6: Boolean = false,
    val udpGw: String? = null
)

sealed class FdTransferResult {
    object Success
    data class Failed(val attempts: Int)
}

data class AppEntry(val name: String, val packageName: String, val icon: Drawable?)

interface PlatformBridge {
    /** Build the service-start intent with every extra bound. */
    fun startIntent(payload: TunnelPayload): Intent

    /** Inverse of startIntent; absent/odd extras fall back to defaults. Never throws. */
    fun readPayload(intent: Intent): TunnelPayload

    /** Push fd into the native tun pipe; retries internally, blocks the caller thread. */
    fun transferFd(fd: Int): FdTransferResult

    /** All installed apps, label-loaded, name-sorted (ignoreCase), incl. system apps. */
    fun appList(): List<AppEntry>

    /** Render + write pdnsd.conf into filesDir and ensure pdnsd.cache exists. */
    fun writePdnsdConf(dns: String, port: Int, upstream: String?)

    /** FileProvider-backed share intent (authority string lives here only). */
    fun shareIntent(file: File, mime: String, subject: String): Intent
}
```

### Invariants, ordering, errors, config, perf

- **Key grammar** — the `"SOCKS"+…` extra-key table is *private* to the bridge. One writer (`startIntent`) and one reader (`readPayload`) means the schema can no longer drift apart (today the writer does `appList.split("\n").toTypedArray()` and the reader does `getStringArrayExtra`; a key-name typo between `Utility.kt` and `SocksVpnService.kt` would be a silent runtime miss).
- **round-trip identity**: `readPayload(startIntent(p)) == p` is a test-enforced invariant (defaults fill both directions; `udpGw` only materializes when non-null, mirroring today).
- **Ordering**: callers must call `startIntent` → then `startForegroundService` on API 26+ (the bridge does not start services; that is `VpnLauncher`'s job). `readPayload` must precede any `configure`/`transferFd` use. `transferFd` *blocks its caller* — the engine invokes it from its background startup thread (documented perf contract: ~50 ms sleeps, ≤100 attempts, ~5 s worst case — the exact retry policy that lives in `SocksVpnService.start()` today, moved in).
- **Errors**: `transferFd` → `Failed(attempts)`; the *policy* response (stop the VPN) stays in the engine — the bridge reports, the engine decides. `writePdnsdConf` swallows file-I/O exceptions (today's `catch {}` behavior) and must not be called concurrently from two threads (engine serializes: reconcile path is main-thread, start path is its own background thread — documented constraint, unchanged from today).
- **Config**: the FileProvider authority, `filesDir`, and pdnsd conf template (from `R.string.pdnsd_conf`) are captured by the production adapter's constructor. Locale/format: none — it is not formatting.
- **Perf**: `appList()` is a cold-path call (dialog open); label loading is N×`loadLabel` just as today.

### Seam placement & adapters — the honest one-vs-two audit

External seam = `PlatformBridge` interface. **Two adapters → real seam:**
1. **Production** `AndroidBridge(context)` — JNI `System.sendfd`, `PackageManager`, `FileProvider`, conf writer.
2. **Test** `FakeBridge` — pure JVM: scriptable fd channel, canned app list, temp-dir conf, no Android at all.

Individual sub-seams behind the interface, scored:

| Slot | Why it earns a seam | Adapters |
|---|---|---|
| `transferFd` | Retry/backoff policy is *logic* (worth testing: success-on-N, cap at N) | production (JNI) + fake fd server → **2** ✔ |
| `writePdnsdConf` | Template is a versioned contract with the native binary (changing pdnsd.conf format must not be an engine change) | Android files + fake temp-dir → **2** ✔ |
| `startIntent`/`readPayload` | Schema grammar, round-trip identity | Android Intent + fake (identity check is pure JVM — fake is trivial) → **2** ✔ |
| `appList` | Query + normalization (sort, label, trim) | real PM + fake PM → **2** ✔ |
| `shareIntent` | Thin (`getUriForFile` + `FLAG_GRANT_READ_URI_PERMISSION`); payoff is consolidation, not depth | 1 adapter today — **admitted**: see drawbacks §8 |

**Internal seams** (private to the bridge's implementation, not part of the interface, per DEEPENING): `FdChannel` (products: JNI call; tests: fake that accepts after N calls) and `PdnsdConfTemplate` (resource string + `String.format`, golden-tested).

### Depth / leverage / locality

- Delete-the-module test: the extras grammar reappears in **Utility.startVpn AND SocksVpnService.onStartCommand** (2 copies that can diverge); the fd retry policy reappears inside the **engine thread** (violating the "never touch engine" rule); the FileProvider authority reappears in **LogCollector and UpdateChecker**; the package query reappears in **AppSelector**. The modest number of sites per boundary (1–2) is exactly why the *aggregation* matters: the single fact "only PlatformBridge touches IN-/System-/PackageManager" is now grep-verifiable (`grep -r "getUriForFile\|sendfd\|getInstalledApplications\|getStringExtra" app/src/main/java | grep -v bridge/`).
- Locality win for the guard clause: AGENTS.md says the engine must never be changed for UI; today a UI request like "app list should exclude preinstalled apps" lands in the **AppSelector (UI) + engine-touching Utility** both. After: one file.
- Honest ceiling: this is the least-deep of the three modules per-unit-interface. Its depth quotient is carried by `transferFd`'s retry policy and the payload schema; the rest is consolidation and audit-locality, not leverage.

### Testability

- **Fake fd server** (`FakeBridge` + internal `FakeFdChannel`): a channel that records calls and accepts on attempt N — asserts `Success`, `Failed(100)` at the cap, and that sleeping is bounded (inject a clock).
- **Round-trip golden**: paste a real engine start command (the 13 extras today) and assert `readPayload(startIntent(p)).toString()` identity; plus a "hostile intent" case (new Intent with nothing) → defaults, no throw.
- **Conf golden**: golden-file compare of `writePdnsdConf` output against a saved `pdnsd.conf` — locks the native contract.
- Production `AndroidBridge` stays thin enough to be trusted by review + the existing on-device CI install-over smoke test.

---

## 4. Module 3 — `RouteCatalog`

### Interface (complete)

```kotlin
package net.typeblog.socks.util.routes

enum class RouteMode { ALL, CHN, RU, RU_CHN }     // unknown strings map to ALL (today's fallback)
enum class PerAppMode { OFF, BYPASS, ALLOW }      // derived from (perApp, appBypass) by the caller

data class Cidr(val address: String, val prefix: Int)

data class RoutePlan(
    val cidrs: List<Cidr>,                // ordered; applied in order
    val disallowedPackages: List<String>, // tunnel-bypass set (includes the engine's own UID rule)
    val allowedPackages: List<String>     // per-app ALLOW mode membership
)

interface RouteCatalog {
    fun select(mode: RouteMode, perApp: PerAppMode, apps: List<String>, ipv6: Boolean): RoutePlan
}
```

### Invariants — the route/per-app truth table (data, not code)

| mode | cidrs | disallowed | allowed |
|---|---|---|---|
| ALL | `0.0.0.0/0` | — | — |
| CHN | `R.array.simple_route` | — | — |
| RU | `R.array.ru_route` | — | — |
| RU_CHN | simple_route + ru_route | — | — |
| unknown → ALL | `0.0.0.0/0` | — | — |
| + ipv6=true | append `::/0` | — | — |
| perApp=OFF | — | `[selfUidPackage]` | `[]` |
| perApp=BYPASS | — | `[selfUidPackage] + apps` | `[]` |
| perApp=ALLOW | — | `[]` | `apps \ selfUidPackage` |

Plus row-level rules, folded in from `SocksVpnService.configure:621-684` and `Routes.addRoutes`:

- **127/8 exclusion**: any CIDR starting `127` is dropped (builder can't route loopback — today's comment, now a table rule).
- **Trim/drop**: `apps` entries are trimmed; empty/whitespace entries dropped.
- **self-uid rule**: the engine's own package is always excluded from the tunnel when tunneling-all or bypassing, and excluded from an allow list even if listed — with the comment that explains *why* (tun2socks/pdnsd need the real network to reach the SOCKS server; routing them into the unsurfaced tun deadlocks startup).
- **Errors**: invalid CIDRs are dropped per-entry, never thrown (today's `catch {}` around `addRoute`); `select` is total, always returns a `RoutePlan`, never null.
- **Config**: route arrays are injected as an internal `RouteSource` (production = `R.array.*`; tests = stub lists). The catalog owns the `R.array.simple_route`/`ru_route` knowledge — the single place where a "new route mode CHEAP" feature can be added (new mode = new table row + resource).
- **Ordering**: `select` must be called before `Builder.establish()`; the plan is applied in returned order (all-mode routes first, then `8.8.8.8/32` bootstrap, then per-app memberships — the order today's engine hard-codes, now documented in the data).
- **Perf**: cold path, called once per connect; no allocation concerns.

### Seam placement & adapters

The only unconditional routes (tunnel addresses, `8.8.8.8/32`, MTU, session) are *topology and stay in the engine*; everything *selectable* (route mode, per-app mode, ipv6 wide route) is catalog. The resource arrays create a natural **internal seam** (`RouteSource`) — production + test stubs, i.e. two adapters internally, but it stays private; the **external seam** is `select`.

### Depth / leverage / locality

- One call replaces **~55 lines of policy in the engine** (`configure`'s three per-app branches + `Routes.addRoutes` loop + skip rules) with one `applyPlan(builder, plan)` of ~15 lines. That's the leverage: the engine's NFZ (no-touch zone) shrinks by a whole concern.
- The combo space (4 modes × 3 per-app modes) becomes exhaustive table cells — a new combination can't silently fall through to "tunnel everything" (the current implicit fallback).
- **The UI door**: route and split-tunneling features are UI-selected; RouteCatalog is the only surface a UI change needs — routing policy changes no longer require touching `SocksVpnService.kt` at all.

### Deletion test

Delete RouteCatalog and the complexity lands **inside the engine**: the route resource table, the 127/8 rule, the self-UID rule, the three per-app branches, the bootstrap route — all return to `configure()` where AGENTS.md forbids UI-driven edits, and where today's drift already lives (the engine's inline per-app logic and `Routes.addRoutes` are two already-inconsistent copies of the same policy).

### Testability

Pure JVM, injected `RouteSource` stub:

```kotlin
class RouteCatalogGoldenTest {
    // 12 explicit cells: every mode × every perAppMode, plus edge rows
    @Test fun allModeOff() = assertPlan(select(ALL, OFF, [], ipv6 = false),
        cidrs = [0.0.0.0/0], disallowed = [self], allowed = [])
    @Test fun chnBypass() = assertPlan(select(CHN, BYPASS, ["a.b", " ", "c.d"], false),
        cidrs = stubChn, disallowed = [self, "a.b", "c.d"], allowed = [])   // " " dropped, self listed stays
    @Test fun allAllowExcludesSelf() = assertPlan(select(ALL, ALLOW, ["self","x"], false),
        allowed = ["x"])                                                    // self never allowed
    @Test fun unknownMapsToAllAndIpv6Appends() = ...
    @Test fun loopbackFiltered() = ...                                      // "127.0.0.0/8" never in cidrs
}
```

---

## 5. LogCollector — decision: **keep, with one line extracted**

Honest verdict: `LogCollector` is **not a kitchen-sink member for its main job**. `collectLogs(context): String` (multi-pid process discovery → per-pid logcat dump → header) is a single-responsibility, single-consumer module with a clean small interface; it is neither formatting, routing, nor an IN-/System-/PackageManager boundary — folding it into any of the three modules would dilute their identity for zero leverage. Its deletion test is weak (one consumer could inline logcat), but it's *already the deep-enough shape*; extracting it from Utility is the whole improvement.

The one genuine boundary in it — `shareLogs`' FileProvider + `ACTION_SEND` marshalling — **moves to `PlatformBridge.shareIntent`**, simultaneously deduplicating `UpdateChecker`'s copy (second FileProvider user, same authority string, same grant flag). LogCollector keeps: header composition, pid discovery, logcat invocation, and a thin `share` that delegates to the bridge. `DebugLogsScreen`'s duplicated device-info strings (Build fields re-rendered in its own card) are a UI-side copy of the header — noted, out of scope here (Cluster 9/UI), listed as an observation.

---

## 6. The statute — what happens to `Constants.kt`

**Deletion test on Constants today:** it's a pass-through for roughly **half its catalog** — 8 of ~33 keys have zero users (verified dead: `INTENT_IP_INFO`, `INTENT_CONNECTED_SINCE`, `PREF_RECENT_COUNTRIES`, `MASTER_KEY_ALIAS`, `ABI_DEFAULT`, `PREF_DYNAMIC_COLORS`, `PREF_CONNECTIVITY_CHECK`), and nearly every live key is a *bare string* whose only "behavior" is being re-typed at a call site — the schema lives implicitly in whoever concatenates/reads it. Delete Constants and the complexity does **not** vanish for the live keys (ProfileManager would still need its keys, the engine its extras) — which is the point: Constants currently *distributes* schema authority instead of owning it.

**The statute (split by role):**

| Role | Keys | Destination | Justification |
|---|---|---|---|
| **Manifest-level contracts** | `ACTION_START_VPN`, `ACTION_STOP_VPN` | stays public in a trimmed `Constants` | They are the *exported* broadcast contract between two components (FCS notification actions → engine receiver); like a manifest declaration, exactly one public home — this is the honest remainder |
| Intent-extra schema | `INTENT_*` (12 live) | `PlatformBridge` (private) | owned by the module that must keep writer/reader symmetric |
| Profile/serialization schema | `PREF_PROFILE`, `PREF_LAST_PROFILE`, `PREF_SERVER_*`, `PREF_IPV6_PROXY`, `PREF_UDP_*`, `PREF_AUTH_*`, `PREF_ADV_*` | `ProfileManager` (private) | ProfileManager already serializes with them; split-tunneling UI and launcher consume via ProfileManager APIs, never raw keys |
| Feature toggles | `PREF_FLOATING_CONTROL` (MainActivity/SettingsScreen/BootReceiver), `PREF_THEME_MODE` (Theme/SettingsScreen) | each feature's own schema (`FloatingControlService` companion, `ui/theme/ThemePrefs`) | per-owner keys, like the statute requires |
| Engine policy prefs | `PREF_NETSHIELD_*` | follows `netshieldPolicy` (see below) | not this cluster's concern |
| Route keys | `ROUTE_*` | `RouteCatalog` (private) | the route table's own vocabulary |
| **Dead** | the 8 above | deleted | grep-proof, zero migration risk |

**Where the remaining Utility remnants legally park** (the three modules plus statute don't claim them):
- `RecentCountries` — small own module (≈40 lines, `list`/`remember`, file-backed, 4 callers, 5 call sites). The deletion test passes (5 sites would each re-implement trim/dedupe/take-10), and it is not any of the three modules' business. Kept as a named exception, not hidden inside Metrics/PlatformBridge.
- `VpnLauncher` — the *assembly* half of `startVpn` (profile isPerApp ∥ prefs fallback → `TunnelPayload` construction → `startIntent` → `startForegroundService/startService` by API level) stays in the main process as a small module (3 callers: BootReceiver, FloatingControlService, VpnViewModel). `PlatformBridge` only marshals; the profile-domain decisions stay out of the bridge.
- `netshieldPolicy` + `NetshieldPolicy` + `IpInfo` + the IP-race machinery — **engine/network domain, not utility**: when the IP-lookup cluster (Cluster 11) lands they relocate with it; in the interim they are extracted from `Utility` into `util/Netshield.kt` unchanged, with `PREF_NETSHIELD_*` in the same file. Forced placement into Metrics/PlatformBridge/RouteCatalog would re-create the kitchen sink they're escaping.

`join` folds into ProfileManager (serialization grammar). `DebugLogsScreen`'s device-info card stays (UI).

---

## 7. Migration sketch (each phase compilable, shippable through the CI-only build per AGENTS.md)

**P0 — excision (smallest, urgent):** delete `exec`×2, `extractFile`, `readTunBytes`, `killPidFile`; delete `System.getABI`/`jniclose` **and** the native side (`system.cpp` `jniclose`/`getABI` registrations + SystemUtil impl); delete the 8 dead Constants. Ship. (Verification: `grep -rn "Utility.exec\|readTunBytes\|killPidFile\|getABI" app/src` → empty.)

**P1 — Metrics:** extract `util/metrics/` verbatim-grammar; migrate call sites (DataUsageCard ×3, ProxyCard bytes+flag, Countries ×3, FCS elapsed+flags, SettingsScreen inline `%.1f MB`); add golden tests; delete `Utility.formatBytes`/`countryCodeToFlag`.

**P2 — RouteCatalog:** create `util/routes/`; fold `Routes.addRoutes` + SocksVpnService.configure's per-app branches into the table; engine `configure()` becomes `applyPlan`; add the 12-cell golden tests + injected RouteSource.

**P3 — PlatformBridge:** create `util/bridge/`; move extras grammar (writer=Utility.startVpn's intent block, reader=SocksVpnService.onStartCommand) into `TunnelPayload` + round-trip tests; move fd retry loop into `transferFd` (+ fake fd server tests); move `writePdnsdConf` (+ template golden); move `appList` query from AppSelector (+ fake PM); cut LogCollector.share + UpdateChecker over to `shareIntent`; create `VpnLauncher` holding the assembly/launch half of `startVpn`; delete `System.kt`, `Routes.kt` walking skeletons.

**P4 — statute:** Constants → 2 action strings; ProfileManager absorbs `join` + its key schema (export narrow accessors); `RecentCountries` extraction; `netshieldPolicy` → `util/Netshield.kt`; theme/floating prefs relocate; SplitTunneling/MainActivity/SettingsScreen/BootReceiver re-keyed; delete `Constants.kt`'s remains except actions. Grep-audit gate: `getStringExtra|getInstalledApplications|getUriForFile|sendfd` may appear only under `bridge/` (engine's `Builder` uses are the RouteCatalog API, not raw boundaries).

### Audited offender list (every direct boundary touch, today)

| # | Offender (file:line) | Boundary touched | Fix phase |
|---|---|---|---|
| 1 | Utility.kt:249-268 `startVpn` | `Intent.putExtra` ×13 | P3 (bridge.startIntent) |
| 2 | SocksVpnService.kt:361-379 `onStartCommand` | `Intent.get*Extra` ×13 | P3 (bridge.readPayload) |
| 3 | SocksVpnService.kt:621-684 `configure` | VpnService.Builder disallowed/allowed policy | P2 (RouteCatalog) |
| 4 | SocksVpnService.kt:810 `System.sendfd` + poll loop | JNI | P3 (bridge.transferFd) |
| 5 | SocksVpnService.kt:489-490 `killPidFile` | process kill | P0 (dead) |
| 6 | SocksVpnService.kt:705-708, 884-890 `netshieldPolicy`/`makePdnsdConf` | native config | P3 (bridge.writePdnsdConf) + netshield stays |
| 7 | AppSelector.kt:32-50 | `getInstalledApplications`, `loadLabel`, `loadIcon` | P3 (bridge.appList) |
| 8 | LogCollector.kt:58-67 `shareLogs` | FileProvider + AIDL-free SEND | P3 (bridge.shareIntent) |
| 9 | UpdateChecker.kt:118 | FileProvider | P3 (bridge.shareIntent) |
| 10 | FloatingControlService.kt:721-726, 170-174 | ACTION_START/STOP_VPN intents | stays (manifest contract, statute) |
| 11 | FloatingControlService.kt:1185-1195 `formatElapsed` | private number grammar | P1 (Metrics) |
| 12 | SettingsScreen.kt:123 `"%.1f MB".format(...)` | inline format dialect | P1 (Metrics) |
| 13 | SplitTunnelingScreen.kt:52-54,104-112,220-413; SettingsScreen.kt:54-55,74,229-353; MainActivity.kt:45; BootReceiver.kt:30 | raw PREF_* keys | P4 (per-feature schemas) |
| 14 | Theme.kt:62-67; SettingsScreen.kt:56,71,109 | `PREF_THEME_MODE` | P4 (ui/theme) |
| 15 | Countries.kt:11,282,289; ProxyCard.kt:156,182; DataUsageCard.kt:62-72 | `Utility.formatBytes`/`countryCodeToFlag` | P1 (Metrics) |
| 16 | ProfileManager.kt:78,97,118 | `Utility.join` | P4 (absorb) |
| 17 | BubbleMenuOverlay.kt:103; CountriesScreen.kt:69,159-160; ProxiesScreen.kt:274,912-913,960-961; FCS:888 | recents file | P4 (RecentCountries) |
| 18 | system.cpp:39,76-80; SystemUtil | getABI/jniclose JNI | P0 (delete both sides) |

---

## 8. Testability summary

| Module | Surface | Technique | Android-free? |
|---|---|---|---|
| Metrics | `format(MetricValue)` | table golden tests, 5 dialects | yes (pure JVM) |
| RouteCatalog | `select(mode, perApp, apps, ipv6)` | 12-cell truth-table golden + edge rows; injected RouteSource | yes |
| PlatformBridge | 6-method interface | `FakeBridge`: fd channel accepting on attempt N; Intent round-trip identity; hostile-intent defaults; pdnsd.conf golden file | yes (FakeBridge is pure JVM) |
| LogCollector | `collectLogs` | unchanged; on-device smoke (CI install-over) | no (by nature) |

The old shape had **zero tests** (no test source set exists for these units today — from here, new tests arrive *with* each module and live in `app/src/test`). Per DEEPENING "replace, don't layer": no tests are being replaced; the modules arrive tested.

---

## 9. Alternative designs considered (design-it-twice) and why not

1. **Statute-only cleanup (keep Utility as a named-but-tamed object).** Zero refactor risk, but keeps the three dialect drift points and leaves the engine holding route policy — the two worst current costs — untouched. Rejected: the cluster's problems are drift + engine-boundedness, not just sprawl.
2. **One mega `PlatformSeam` module (fold Metrics+Routes+LogCollector+bridge into a single boundary facade).** Very high one-entry leverage, but the deletion test inverts: the module would own *unrelated* grammars (formatting is not a platform boundary), and its interface would grow to ~12 methods — the shallow-module trap at scale. Rejected: three modules have cleaner single-responsibility seams; the statute keeps the coordination.
3. **Fold LogCollector and recents into PlatformBridge.** Reduces count to 3, but drags process-instrumentation and file state behind a boundary facade that owns JNI/Intent — Mixing-in the kitchen sink again. Rejected; keep-as-is + named exceptions is the honest shape.

**Recommendation:** the three-module design as specified, with P0 deletion first — half the cluster's utility work is *removal*, and it de-risks every later phase.

---

## 10. Honest drawbacks

1. **PlatformBridge is the weakest module per unit of interface** — several slots (`shareIntent`, `appList`) each have one production adapter and one thin caller; without the two-adapter discipline on `transferFd`/`writePdnsdConf` the seam would be ceremony. The module's real payoff is the grep-verifiable boundary guarantee + payload-schema symmetry, not leverage. If the app never gains a second share path or second FileProvider user, those methods are justified by consolidation alone — a weaker claim.
2. **Metrics' sealed-type ceremony** for what is a ~25-line formatter is only worth it because three incompatible dialects exist *today*; if the team is comfortable locking one dialect by fiat, a single `format(...)` overload family would do. The sealed value still wins for future `Rate`/`Percent` (nothing renders those today).
3. **Process split is unchanged** — `PlatformBridge` is used from both the main and `:vpn` processes; no cross-process win or loss, but the "one place to change" claim is per-process-instance only.
4. **Engine churn risk in P2/P3:** folding `configure` into RouteCatalog and the fd poll into `transferFd` mutates the most guarded file in the repo; every behavioral regression lands there. Mitigation: phases are small and each is CI-verified via the install-over flow; RouteCatalog's 12-cell and transferFd's fake-server tests cover the moved logic *before* it's cut over.
5. **Import churn in P4** touches ~12 files for the key relocation, much of it for keys with three users (e.g. `PREF_FLOATING_CONTROL`); the honest sequence is P0's deletions first — the refactor's value is highest after the dead weight is gone.
6. **`shareIntent`'s one-adapter honesty** — folded into the bridge for audit-locality despite failing the two-adapter test; called out rather than hidden.

---

## 11. Bottom line

The cluster is not one problem: it's *drift* (three number dialects), *boundary sprawl* (five raw platform touches in engine-adjacent code), *policy-in-the-engine* (routing/per-app rules), *dead weight* (11 unused members across Utility/System/Constants), and *schema democracy* (Constants keys re-typed by every caller). Three deep modules + statute attack each: Metrics owns the grammar, PlatformBridge owns every boundary, RouteCatalog owns every selectable routing rule, the statute forces every key to live beside the code that owns its meaning. Half the work is deletion, which is the part that can't regress.