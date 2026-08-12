# Cluster 3 — Profile & Persistence: Radical Redesign

**Scope read (fully):** `util/Profile.kt` (186 ln), `util/ProfileFactory.kt` (32 ln), `util/ProfileManager.kt` (138 ln), `util/Constants.kt`, `SocksVpnService.kt` (usage persistence `usage_rx_`/`usage_tx_`, `USAGE_PERSIST_TICKS`), and every consumer: `Utility.startVpn`, `UI/ProxyCard.kt`, `UI/StatusScreen.kt`, `UI/ProxiesScreen.kt`, `UI/SplitTunnelingScreen.kt`, `UI/NetShieldScreen.kt`, `UI/SettingsScreen.kt`, `UI/theme/Theme.kt`, `VpnViewModel.kt`, `FloatingControlService.kt`, `CountriesScreen.kt`, `BootReceiver.kt`, `MainActivity.kt`, `SocksApplication.kt`.

Vocabulary used: module, interface, adapter, seam, depth, leverage, locality, deletion test, one-vs-two-adapters.

---

## 0. Current state (as audited)

Three shallow modules + raw prefs everywhere. `Profile` is a 15-field getter/setter façade over **per-field** `SharedPreferences` operations (15 getters, 15 setters, plus `delete()`/`copyTo()` that hard-code every key in their own arrays — three separate arrays of key names that must stay in sync by hand: getter bodies, `delete()`, `copyTo()`).

Two *separate* persistence worlds:

| World | Store | Contents | Writers / readers |
|---|---|---|---|
| Secure | `EncryptedSharedPreferences("profile")` | per-profile keys (`{prefix}server`, `{prefix}port`, … 15 keys × name-prefix), `profile` (newline list), `last_profile` | `Profile`, `ProfileFactory`, `ProfileManager` only |
| Open | default `SharedPreferences` | `usage_rx_<raw>_<sanitized>`, `usage_tx_…` | `SocksVpnService` (:vpn process) writes; ProxyCard, StatusScreen read |
| Open | default `SharedPreferences` | `adv_per_app`, `adv_app_bypass`, `adv_app_list` (global split-tunnel), `netshield_*`, `theme_mode`, `floating_control`, `auto_stop` | 11 files (see audit §6) |

### External prefs accessors audit (every file outside the cluster that touches prefs/Profiles)

1. `SocksVpnService` — `PREF_AUTO_STOP` read (:940), `PREF_NETSHIELD_*` listener (:324–332, :451), usage `getLong`/`putLong` with hand-rolled key format (:553–577), `usageKeySuffix()` sanitization `Regex("[^A-Za-z0-9]")→_`.
2. `Utility` — netshield prefs (:213–215); *the merge* `perApp = profile.isPerApp() || PREF_ADV_PER_APP` etc. (:236–247); the 14-extras `INTENT_*` builder (:249–275) reading 10 different Profile getters.
3. `ProxyCard` — reads usage keys directly (:74–76), duplicates the suffix regex (:71).
4. `StatusScreen` — reads usage keys directly (:172–174), duplicates suffix regex for the *display* profile.
5. `Theme` — `PREF_THEME_MODE` read + change listener (:61–67).
6. `SettingsScreen` — `PREF_THEME_MODE`, `PREF_FLOATING_CONTROL`, `PREF_NETSHIELD_ENABLED`, `PREF_ADV_PER_APP` read/write.
7. `NetShieldScreen` — `PREF_NETSHIELD_ENABLED`/`_BLOCK_ADULT` read/write.
8. `SplitTunnelingScreen` — `PREF_ADV_PER_APP`/`_APP_BYPASS`/`_APP_LIST` read/write (the *only* writer of the global tunnel keys).
9. `MainActivity` — `PREF_FLOATING_CONTROL`.
10. `BootReceiver` — `PREF_FLOATING_CONTROL` + `Profile.autoConnect()`.
11. `SocksApplication` — `PreferenceManager.setDefaultValues(R.xml.settings)` seeds the *legacy* schema.
12. `FloatingControlService` — ProfileManager get/set/switch (no direct prefs).
13. `CountriesScreen` — `getUsername/setUsername`, recent-countries **file** (not prefs).
14. `VpnViewModel` — ProfileManager for list/reload; hand-rolled `_profileVersion` counter as cache token (:181).

### Dead weight found (deletion candidates)

- 14 `PREF_*` constants referenced **only** in `Constants.kt` + legacy `res/xml/settings.xml`: `PREF_SERVER_IP/PORT`, `PREF_IPV6_PROXY`, `PREF_UDP_PROXY/GW`, `PREF_AUTH_USERPW/USERNAME/PASSWORD`, `PREF_ADV_ROUTE`, `PREF_ADV_DNS[_PORT]`, `PREF_ADV_AUTO_CONNECT`, `PREF_DYNAMIC_COLORS`, `PREF_CONNECTIVITY_CHECK`, `PREF_RECENT_COUNTRIES` (the real recent-countries store is a file).
- `settings.xml` + `SocksApplication.setDefaultValues` — dead schema seeding.
- Per-profile setters never called anywhere: `setRoute`, `setDns`, `setDnsPort`, `setIsPerApp`, `setIsBypassApp`, `setAppList`, `setHasIPv6`, `setHasUDP`, `setUDPGW`, `setAutoConnect`. Only `setServer/Port/Username/Password/IsUserpw` are UI-live (ProxiesScreen.saveProfile) plus `setUsername` (CountriesScreen/bubble). The per-profile "advanced" fields are only ever default-valued today; the *live* split-tunnel config is the global `adv_*` trio.
- The per-app merge (`profile.isPerApp() || adv_per_app`) is duplicated *semantically* in `Utility.startVpn` and *visually* in two screens — one truth, three places.

### Latent bugs found (materials for tests)

- **Key-prefix collision:** `prefPrefix` = `replace("_","__").replace(" ","_")` is **not injective**: name `"a_b"` → `"a__b"` and name `"a  b"` (two spaces) → `"a__b"` — two profiles silently share one config.
- **Usage key double-naming:** key = `usage_rx_<rawName>_<sanitized(rawName)>` embeds the raw name *and* a regex-sanitized copy; two different sanitizers exist (prefix vs usage) — a schema, not a design.
- **Rename leaves ledger orphaned; delete leaves ledger keys behind** — no cleanup anywhere (ProfileManager.rename/remove touch only the encrypted store).
- **Non-atomic multi-apply transactions:** `renameProfile` = 3+ separate `apply()`s; `saveProfile` = rename + 4 setters (5 applies). Crash between → torn state. `addProfile` even calls `reload()` mid-sequence.
- `loadProfileBytes(profileName)` ignores its argument for the suffix and uses `mProfileName` instead — only correct because they're equal at the call site; the parameter is a lie.

---

## 1. The radical design

**One deep module `net.typeblog.socks.store`** replaces `Profile` + `ProfileFactory` + `ProfileManager` and *absorbs* every prefs touch in the audit. The profile becomes an **immutable value**; persistence becomes a **small transactional store**; the engine gets **one projection**; usage stats become a **second projection** in the same module.

```
profile          = immutable value (data class, 15 fields, defaults)
ProfileStore     = the whole external seam (interface below)
ProfileCatalogue = collection view, same module, same transaction
UsageLedger      = usage projection, same module, same transaction
KeySchema        = internal seam: name↔keys, sanitization, both backends
KeyValueBackend  = internal seam: two adapters (SharedPrefs / InMemory)
TunnelConfig     = Parcelable projection: the ONLY thing the engine consumes
```

**Seam placement:** the external seam is `ProfileStore`'s interface plus the two *sibling views* (`profiles`, `usage`) that ride the same transaction. **Internal seams:** `KeyValueBackend` (production `SharedPrefsBackend` wrapping the two prefs files — encrypted for profile config, open for usage; test `InMemoryBackend`) and `KeySchema` (key derivation, sanitization, both-data-world knowledge). These are private to the implementation and used by the module's own tests — never exposed through the interface (DEEPENING.md "internal seams vs external seams").

### 1.1 Value types

```kotlin
data class Profile(
    val name: String,                       // display name = identity (schema-compatible)
    val server: String = "127.0.0.1",
    val port: Int = 1080,
    val username: String = "",
    val password: String = "",
    val authEnabled: Boolean = true,        // legacy "userpw": always true via UI; kept
    val route: String = ROUTE_ALL,
    val dns: String = "8.8.8.8",
    val dnsPort: Int = 53,
    val perApp: Boolean = false,            // legacy per-profile fields KEPT for schema compat;
    val appBypass: Boolean = false,         //   merged with the global adv_* trio at projection time
    val appList: Set<String> = emptySet(),  //   (was newline-delimited String — now typed)
    val ipv6: Boolean = false,
    val udpEnabled: Boolean = false,
    val udpGateway: String = "127.0.0.1:7300",
    val autoConnect: Boolean = false,
) {
    /** Engine projection — the ONLY read path the engine has. Pure function. */
    fun config(globals: TunnelGlobals): TunnelConfig
}

data class TunnelGlobals(                  // the global adv_* split-tunnel trio, same module
    val perApp: Boolean,
    val appBypass: Boolean,
    val appList: Set<String>,
)

data class TunnelConfig(                    // Parcelable
    val profileName: String,
    val server: String, val port: Int,
    val username: String, val password: String,
    val route: String, val dns: String, val dnsPort: Int,
    val perApp: Boolean, val appBypass: Boolean, val appList: List<String>,
    val ipv6: Boolean, val udpGateway: String?,   // null ⇒ UDP off
) { /* writeToParcel / fromParcel; toIntent() adds ONE extra under INTENT_TUNNEL_CONFIG */ }

data class UsageTotals(val rx: Long, val tx: Long)
```

**Invariants:** `Profile` is immutable; every mutation is a `copy()` inside `update`. `list-config merge semantics` live *inside* `config()` (pure, unit-testable): `perApp = profile.perApp || globals.perApp`, `bypass/appList = if (profile.perApp) profile.* else globals.*` — byte-identical to today's `Utility.startVpn` (:236–247), pinned by a compatibility test.

### 1.2 The interface (COMPLETE spec)

```kotlin
interface ProfileStore {
    /** Active (default) profile. Never null — "Default" placeholder guaranteed. */
    fun get(): Profile

    /**
     * Atomic read-modify-write. transform receives the current Profile; its result
     * is validate()d and, on success, persisted in ONE backend transaction and
     * snapshotToken() is bumped. On failure nothing is written and the error is
     * returned as-is (Result.failure). Invariant: update NEVER throws.
     * Ordering: validate BEFORE write; token bump AFTER write.
     * Errors: Result.failure(ValidationException(field, reason)) | StoreException.
     */
    fun update(transform: (Profile) -> Result<Profile>): Result<Profile>

    /** Monotonic, per-process version. Bumped on every successful update/add/remove/
     *  rename/setDefault. Consumers use it to invalidate caches (remember(token)),
     *  replacing VpnViewModel._profileVersion (VpnViewModel.kt:181). Zero is never
     *  returned. Not persisted; cross-process callers must not rely on it. */
    fun snapshotToken(): Long

    /** Pure validity check, no I/O. name non-blank & != reserved "Default",
     *  1 ≤ port ≤ 65535, 1 ≤ dnsPort ≤ 65535, server non-blank,
     *  dns non-blank, "host:port" for udpGateway when udpEnabled,
     *  appList non-empty when perApp&&!bypass… (full table in impl). */
    fun validate(profile: Profile): Result<Unit>
}

interface ProfileCatalogue {                // same module, same transaction
    fun names(): List<String>               // [Default, …user profiles…] — placeholder semantics preserved
    fun add(name: String): Result<Unit>     // validate + names() set membership; becomes last, switchDefault(to it)
    fun remove(name: String): Result<Unit>  // reserved "Default" rejected; purges config + usage keys
    fun rename(old: String, new: String): Result<Unit>  // atomic: config keys + usage keys + list + last_profile
    fun setDefault(name: String): Result<Unit>
}

interface UsageLedger {                     // second projection — SAME module (justification §3)
    fun totalsFor(name: String): UsageTotals
    fun addBytes(name: String, rx: Long, tx: Long)      // :vpn process, every USAGE_PERSIST_TICKS ticks
    fun reset(name: String)
}
```

`ProfileStore` exposes: `val profiles: ProfileCatalogue` and `val usage: UsageLedger`. **That is the entire external surface: 7 entry points total** — down from 30 Profile getters/setters + 6 ProfileManager CRUD methods + 2 duplicate usage readers + `_profileVersion`, all scattered across 11 files.

**What callers do now (usage examples):**

```kotlin
// ProxiesScreen.saveProfile → ONE call, ONE transaction:
store.update { p -> p.copy(server = host, port = port, username = username,
                           password = password, authEnabled = true).ok() }   // Result-typed
// (rename stays a catalogue op: store.profiles.rename(old, new))

// CountriesScreen / bubble country switch:
store.update { p -> p.copy(username = newUsername).ok() }
Utility.addRecentCountry(context, code)                       // file store — unrelated, untouched

// Engine start (Utility.startVpn replacement):
val tc = store.get().config(store.tunnelGlobals())            // or store.configSnapshot()
tc.toIntent(context)                                          // ONE extra, Parcelable
// SocksVpnService.onStartCommand:
val tc = TunnelConfig.fromIntent(intent) ?: return 0          // 14 reads → 1

// SplitTunnelingScreen saves:
store.setTunnelGlobals(TunnelGlobals(...))                    // third tiny view; or store.update on globals

// SocksVpnService usage persist:
store.usage.addBytes(profileName, totalRx, totalTx)           // :vpn process, identical key format

// UI invalidation:
val token = store.snapshotToken()
val profile = remember(token) { store.get() }
```

```kotlin
// ProfileField is deliberately NOT exposed. Screens bind Compose state,
// commit exactly once via update. No per-field API exists to misuse.
```

### 1.3 What the implementation hides behind the seam

- Both prefs files, the encrypted-vs-open split, and `MasterKey` construction (the only remaining `EncryptedSharedPreferences` import in the app).
- `KeySchema`: prefix escaping, usage key format `usage_<raw>_<sanitized>` (kept verbatim for on-disk compatibility), the catalogue list + `last_profile` keys, `TunnelGlobals` keys.
- Multi-key transactions: profile (+, in `rename`) usage migration + list + default — previously 3 separate `apply()`s in `ProfileManager.renameProfile`.
- The merge rules for per-app split tunneling (dead per-profile fields vs live globals).
- The `"Default"` placeholder bookkeeping (never persisted, never removable, always first) — today spread over `ProfileManager.add/remove/rename/reload`.
- `delete()`/`copyTo()` key-array drift (three hand-maintained key lists in `Profile.kt`) — replaced by one `KeySchema` table.
- Usage cleanup: `remove()` purges ledger keys; `rename()` migrates them. (Today: orphans forever.)
- `snapshotToken` monotonic counter and `_profileVersion`'s job.

### 1.4 Two-adapters test

**Is SharedPreferences an adapter?** Not today — it's *the implementation* leaked everywhere (11 files). In this design, yes: it sits behind `KeyValueBackend` and there are **two adapters**: `SharedPrefsBackend` (production; two file-backed instances — a secure one for profile config, an open one for usage/globals) and `InMemoryBackend` (tests; backs the whole JVM test suite, including cross-"process-restart" simulations by constructing a fresh store over the same map). Two adapters ⇒ a real seam, not indirection.

**Does anything else read/write the keys?** After migration, the store module is the *only* owner of: all `{prefix}*` profile keys, `profile`, `last_profile`, `usage_rx_/usage_tx_*`, `adv_per_app/bypass/list`. The non-tunnel chrome keys (`theme_mode`, `floating_control`, `netshield_*`, `auto_stop`) move to a sibling `AppSettings` module (typed getters + change observer, same `KeyValueBackend` pattern, ~60 lines) so that **no file outside a store module reads or writes any `SharedPreferences`** — the audit's 14 external accessors all route through a store. `SocksApplication`'s `setDefaultValues(R.xml.settings)` and `settings.xml` are deleted (they seed dead keys). The engine's netshield listener becomes `AppSettings.observeNetshield { … }`.

### 1.5 What stays in Constants.kt (justification)

The *raison d'être* of `PREF_*` constants was cross-file spelling agreement; the store makes that internal. What stays:

| Constant | Why it survives |
|---|---|
| `INTENT_TUNNEL_CONFIG` (new, replaces INTENT_NAME…INTENT_UDP_GW) | The one wire key crossing the process boundary (:vpn) as an opaque extra — wire format must be referenced by both processes and never guessed |
| `ACTION_START_VPN`, `ACTION_STOP_VPN` | Broadcast wire constants (notification pill, receiver) |
| `ROUTE_ALL/CHN/RU/RU_CHN` | Domain values, not keys — used by `TunnelConfig`/Routes and the value types |
| `MASTER_KEY_ALIAS` | Actually moves *into* the store (use site only); listed here to be explicit |
| `ABI_DEFAULT` | Engine constant, unrelated |

**Deleted from Constants:** all 23 `PREF_*` constants (14 already dead; the 9 live ones become `KeySchema`/`AppSettings` internal strings) and all `INTENT_*` profile extras (14) apart from the single `INTENT_TUNNEL_CONFIG`. Constants.kt shrinks ~56 → ~15 lines.

---

## 2. Depth & leverage analysis

- **Per entry point:** `update` alone replaces 30 getters/setters across 7 UI call sites with a validated atomic write. `rename` (catalogue) performs today's 4-file operation (Profile.copyTo + Profile.delete + list rewrite + last_profile fix + *new* usage migration) with zero torn states. `get` + `snapshotToken` replace three mechanisms: per-field reads, `_profileVersion`, and the remember(profileName, profileVersion) recomposition keys in `ProxiesScreen`.
- **Deletion test:** delete this module and the app must re-implement: key format + escaping (with its collision), encryption, catalogue bookkeeping, the merge, the INTENT protocol (14 extras), usage key format and cleanup, and validation, in **14 call sites**. Today the reverse holds: delete `ProfileManager` and callers keep working piecemeal via the other shallow modules — the load lives in the callers, i.e. the cluster is already "deleted" in substance and the app just carries a pile of entangled accessors. The new module is where the load *is*.
- **Locality:** the one schema table serializes every current and future key concern (a new profile field = one row in KeySchema + one column in Profile + one test; today = getter+setter+delete array+copyTo array, and 5 hand-places for usage).
- **Leverage ceiling:** `TunnelConfig` Parcelable kills the 14-key INTENT protocol *and* the silent drift risk when a field is added to Profile but forgotten in `Utility.startVpn` (today `setDns` etc. can be written but never forwarded; with one projection this class of bug is unrepresentable). `config()` being pure means the engine's *entire* input contract is a single tested function.

---

## 3. UsageLedger: same module vs sibling (decision)

**Decision: same module, second projection.** Reasons:
1. **Schema coupling:** usage keys are *derived from profile identity* (`<raw>_<sanitized>`); `rename`/`remove` must migrate/purge them atomically with the config keys. A sibling module would have to re-derive the same key format (duplication) or import the store's private schema (leak).
2. **Process reality:** the ledger is written by `:vpn` and read by the UI process; both already compile the same module — no new shared surface needed.
3. **One transaction boundary:** `rename(old,new)` must touch config + usage + list in one catalogue op; that requires a single owning module.

It is still a *separate interface* (`store.usage`) because write-frequency (5s ticks in `:vpn`) and lifetime (survives profile edits) differ from config — separation of interface, unity of ownership. **Honest drawback:** cross-process staleness remains — the UI process may hold a stale prefs cache after `:vpn` writes (the exact problem ProxyCard.kt:83–90 documents). This is an Android platform limitation (per-process `SharedPreferences` cache); the design keeps the existing mitigation (VpnViewModel retained live totals win; persisted is fallback) and does **not** pretend to fix it. In-process read-after-write is guaranteed by the store; `snapshotToken` is explicitly per-process.

---

## 4. Testability

First JVM-testable unit in the whole cluster (today's trio is `android.content`-bound; zero tests exist).

- **Backend seam:** `InMemoryBackend` implements the same `KeyValueBackend` contract — full store tests run on the JVM, no Robolectric.
- **Interface-level tests** (survive internal refactors, per DEEPENING.md):
  - `validate` table: port/DNS-port bounds, blank server, udpgw format, reserved "Default" name.
  - Merge semantics: `config(globals)` pins the exact `Utility.startVpn` :236–247 behavior (compatibility invariant).
  - `update` atomicity: failing transform → state unchanged, token unchanged, error surfaced; success → token bumped exactly once.
  - Catalogue invariants: "Default" never removable/renameable; `remove` purges config **and** usage keys; `rename` migrates config, usage, list, last_profile.
  - **Regression: prefix-injection failure** — `"a_b"` and `"a  b"` must not share config (catches the latent collision of §0).
  - Process restart: fresh store over the same `InMemoryBackend` map → identical `get()`.
- **No new adapter-layer tests:** the `SharedPrefsBackend` is a thin mechanical mapping covered by the two-interface impls; deep logic all sits behind the seam.

---

## 5. Migration sketch (rough budget)

| Step | File | Change | Δ lines |
|---|---|---|---|
| 1 | `store/Profile.kt` | value types + `config()` + validate rules | +120 |
| 2 | `store/KeyValueBackend.kt` | interface + `SharedPrefsBackend` + `InMemoryBackend` | +90 |
| 3 | `store/KeySchema.kt` | key table, escaping, both worlds | +60 |
| 4 | `store/ProfileStore.kt` | store impl + catalogue + ledger (one file family) | +220 |
| 5 | delete | `Profile.kt`, `ProfileFactory.kt`, `ProfileManager.kt` | −356 |
| 6 | `Utility.startVpn` | `store`-based; INTENT builder → `TunnelConfig.toIntent` | −35 |
| 7 | `SocksVpnService` | usage → `store.usage`; netshield/AUTO_STOP → AppSettings; `onStartCommand` 14 reads → 1 | −35 |
| 8 | `ProxyCard`, `StatusScreen` | usage reads → `store.usage.totalsFor` | −20 |
| 9 | `ProxiesScreen` | `saveProfile` → one `update`; token-based remember | −30 |
| 10 | `SplitTunnelingScreen`, `NetShieldScreen`, `SettingsScreen`, `Theme`, `MainActivity`, `BootReceiver` | → AppSettings typed accessors | −25 |
| 11 | `VpnViewModel`, `FloatingControlService`, `CountriesScreen` | store/token swap | −15 |
| 12 | `SocksApplication`, `Constants.kt`, `settings.xml` | delete legacy seeding + dead constants | −70 |
| 13 | `store/ProfileStoreTest.kt` (new) | interface-level suite, InMemory | +150 |

**Net: ≈ −350 to −400 production lines, +150 test lines.** No on-disk format change (keys stay byte-identical, encrypted file untouched, usage format kept), so existing installs upgrade transparently; rename/delete *improve* old data by starting to clean orphans.

Ordering: (1–4) build store alongside old code → (6–11) switch callers one file at a time, each a compilable commit → (5, 12) delete dead code last.

---

## 6. Honest drawbacks

1. **Cross-process usage staleness is not cured** (§3) — a platform cache limitation; mitigated the same way as today, not better.
2. **Interface is 7 entry points, not 4.** The catalogue view is unavoidable (multi-profile is a hard caller requirement: ProxiesScreen list, bubble switch, boot auto-connect); glossing it into `update` would *reduce* depth. Cost: 3 more methods to learn.
3. **Name remains identity.** Schema-compatible, but rename is still a key-migration dance (now safe and tested) and usage history is still name-bound. A UUID id would be cleaner and is the natural *next* deepening — deliberately out of scope because it rewrites every on-disk key at once.
4. **Encrypted prefs write cost is unchanged per operation** (full-rewrite per file), though the *count* of apply()s per logical action drops (e.g. rename: 3→1), so it's a strict improvement, not a win.
5. **Two-process singletons**: the store must be constructed in both processes; `MasterKey` build cost is paid per process (as today).
6. **AppSettings is required glue** (netShield/theme/floating/auto_stop) — without it, "no prefs access outside a store" is unachievable and the netshield live-reconcile listener stays hand-rolled. It is small (~60 ln) but is technically cluster-adjacent work.
7. **Behavior-preservation risk during cutover** is the usual one: merge/defaults must stay byte-identical; the compatibility test (§4) is the guard.