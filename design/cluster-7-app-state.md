# Cluster 7 — Radical Design: one deep `AppState` module owning the entire observable app model

**Scope:** `ui/viewmodel/VpnViewModel.kt`, `ui/navigation/AppNavigation.kt`, all 7 screens, all 7 components, plus `ui/theme/Theme.kt` (theme state lives there too).
**Design rule honored:** engine files (`SocksVpnService.kt`, `IVpnService.aidl`, `Utility.kt`, `ProfileManager.kt`) are **never modified** by this redesign. All changes live in the app process.

---

## 0. Status quo (what Cluster 7 actually is today)

Truth flows: `SocksVpnService` (:vpn process) → 15-method AIDL `IVpnService` → `VpnViewModel` polls **every 200 ms**, ~15 binder calls per tick (≈75 IPC calls/sec) → 16 `MutableStateFlow`s + 1 derived → 10–16 `collectAsState()` per screen. Settings-flavored state (theme, NetShield, split tunneling, floating bubble) bypasses all of this: screens snapshot `SharedPreferences` into `remember { mutableStateOf(...) }`, not reactive, **duplicated per screen**. Profile data and usage persistence are read by screens **directly** from `ProfileManager`/`PreferenceManager` in the middle of composition.

Observable consequences (all real, all in the code):

| Fact | Places it is computed/held | Can disagree |
|---|---|---|
| "connected country" | 4: IP-geo in `ConnectionStatusCard` (from service), username-zone in `ProxyCard`, username-zone in `CountriesScreen` checkmark, IP-geo in `FloatingControlService` flag pill | Yes: geo country vs selected zone are *semantically* different and the app shows both without saying so |
| `isActuallyConnected` (`running && since>0 && verified`) | 3: `StatusScreen:84`, `FloatingControlService:1003`, and the mirrored `isConnecting` in `VpnViewModel:105` | Yes |
| theme mode | 2 independent holders: `SettingsScreen` local state + `Theme.kt` local state + its own prefs listener | Yes |
| NetShield on/off | 2 (`SettingsScreen` icon row, `NetShieldScreen` switch), both prefs snapshots | Yes |
| split-tunneling on/off | 2 (`SettingsScreen` row, `SplitTunnelingScreen` master switch) | Yes |
| usage fallback logic (live → retained → persisted prefs) | 3: `StatusScreen` `persistedUsage`, `ProxyCard` `displayUsed`/`refreshPersistedUsage`, `VpnViewModel.lastProfileName` semantics | Yes |
| recent countries (file read) | 3: `CountriesScreen`, `AddEditProxySheet`, `BubbleMenuOverlay` | Yes |
| zone-rewrite username logic | 2: `CountriesScreen.onCountryTap:124`, `FloatingControlService.onBubbleCountrySelected` | Yes (drift risk) |
| default-profile resolution | 3 screens | Yes |
| connect timeout (20 s) | `StatusScreen` `LaunchedEffect` — dies on navigation away, stranding "Connecting…" | Bug by construction |
| connect-side permission flow (POST_NOTIFICATIONS → VPN prepare) | 2: `StatusScreen`, `SettingsScreen` (floating) | — |

`VpnViewModel` itself is shallow: 1 adapter (bound AIDL), 24 state fields with mechanical copy logic, 4 mutating methods whose real behavior lives in `Utility.startVpn`, `VpnService.prepare`, and the engine. Screens carry engine-derived logic because the ViewModel interface (16 bare flows) is too shallow to absorb it.

---

## 1. Radical design: `AppState` — one module, 3 readers, 1 writer

A single Kotlin module (package `net.typeblog.socks.appstate`) owning **the entire observable app model**, hidden behind a 3-entry-point interface:

```kotlin
interface AppState {
    /** Reader 1 — whole model. One immutable value, emitted at most ~5 Hz. */
    val state: StateFlow<AppUiState>

    /** Reader 2 — sliced model. Cached, distinct-until-changed. This is what screens use. */
    fun <T> derived(selector: (AppUiState) -> T): StateFlow<T>

    /** Writer — THE ONLY writer path for anything observable. Fire-and-forget, never throws. */
    fun command(cmd: UiCommand)
}
```

Everything observable is *one value class*; every mutation is *one command*; every screen reads *a slice*, never the whole, never the service, never prefs, never `ProfileManager`. All engine-derived data currently held in `remember { mutableStateOf(...) }` across the 7 screens disappears; only pure ephemeral UI state (scroll position, dialog visibility, search text, sheet form fields, selected-profile dropdown) stays `remember`ed.

---

## 2. Complete interface spec

### 2.1 Value type — `AppUiState` (one immutable `data class`)

```kotlin
data class AppUiState(
    // ── connection ──
    val connection: ConnectionState,          // sealed, see below (replaces isRunning/isConnecting/isActuallyConnected)
    val error: UiError?,                      // null | snapshot message + generation; cleared by DISMISS_ERROR

    // ── network identity (engine truth, polled) ──
    val ipInfo: IpInfo?,                      // ip, countryCode, country, region, city, isp, org, asName, timezone (nullable fields)

    // ── profiles ──
    val profiles: List<ProfileSummary>,       // name, server, port, providerType, zoneCountryCode (from username),
    defaultProfileName: String?,              //   persistedUsage (rx/tx), isDefault — ALL precomputed behind the seam
    val activeProfile: String?,               // running tunnel's profile (null when disconnected)
    val lastProfile: String?,                 // profile whose session usage was most recent — survives disconnect

    // ── usage (resolved ONCE here) ──
    val usage: SessionUsage,                  // live rx/tx while connected; retained totals while disconnected;
                                              // persisted-prefs fallback inside — screens stop doing Triple maths

    // ── settings (prefs, reactive — no more snapshots) ──
    val themeMode: ThemeMode,                 // LIGHT / DARK / SYSTEM
    val floatingControl: Boolean,
    val netshield: NetshieldPrefs,            // enabled, blockAdult
    val splitTunneling: SplitTunnelingPrefs,  // enabled, bypass, appList: Set<String>

    // ── misc observable state ──
    val recentCountries: List<String>,        // single source, updated by any zone selection
    val pendingPermission: PendingPermission?,// (profileName, prepareIntent) — the ONLY async result of CONNECT
    val debugLog: DebugLog,                   // text + generation; refreshed on demand by REFRESH_LOGS
    val serviceAvailable: Boolean             // binder alive / re-binding — what screens replace `bound` with
)

sealed interface ConnectionState {
    data object Disconnected : ConnectionState
    data object Connecting : ConnectionState              // spanning permission dialog → tunnel up → verified
    data class Connected(val connectedSinceEpochMs: Long) : ConnectionState
}

sealed interface UiError { val message: String; val generation: Long }  // generation: stale Toast suppression
```

**Invariants of the state machine (must hold on every emission):**
1. `connection == Connected` ⟺ tunnel is up **and** proxy verified (`connectedSince > 0 && proxyVerified`). "Connecting" is the *whole* span — it subsumes today's `_connectRequested`, the 20 s timeout, and the running-but-unverified window. No consumer ever re-derives this.
2. `ipInfo != null` ⟹ `connection == Connected`. Geo data cannot outlive the tunnel.
3. `activeProfile != null` ⟹ `connection != Disconnected`. `lastProfile` may survive it (that is its job — usage display).
4. `usage` never shows a higher figure after a *new* connect begins: CONNECT resets session totals (today's `startVpn:298`, done once, not per screen).
5. `pendingPermission != null` ⟹ `connection == Connecting` and it names the profile that CONNECT was issued for. Cleared by `GRANT_PERMISSION_RESULT` — never self-clears (timeout on *this* is the user's job via the system dialog).
6. `debugLog` is a snapshot, not a stream: `generation` increments on every `REFRESH_LOGS`; readers that only care about change subscribe on generation.
7. `error` is the *last* failure: CONNECT/DISCONNECT/ZONE_SWITCH failures set it; a successful connect clears it; `DISMISS_ERROR` clears it. Never two competing error sources (ViewModels, screens, service) writing simultaneously — that race is deleted.

**Ordering constraints (how the module must serialize):**
- Commands are processed **sequentially** (single `Channel<UiCommand>` / actor). Ordering of user intent is preserved: `CONNECT(A)` then `CONNECT(B)` yields one tunnel for B, never two.
- `CONNECT(p)` while `Connecting`/`Connected` is a **no-op** unless the target differs and… (see behavior table). `DISCONNECT` while `Disconnected` is a no-op. Idempotence is the module's job, not the caller's.
- A zone switch while connected = internal sequence `stop → rewrite default profile username → recent-countries update → CONNECT`, **atomic from the caller's view** (today the screen does `stop, delay(500), connect` at `CountriesScreen:162` — racing garbage).
- Settings mutations while the tunnel is up that need a restart (split-tunneling list, bypass, per-app) are **debounced inside** the module (500 ms, restart-job cancel semantics from `SplitTunnelingScreen:86`) — a queued `RESTART` command is synthesized; the screen never schedules.
- `GRANT_PERMISSION_RESULT(true)` resumes the previously pending CONNECT; `(false)` cancels it (`pendingPermission = null`, `connection -> Disconnected`, `cancelConnect` semantics).

**Error modes (everything the caller must know):**
- `command(cmd)` **never throws** and never suspends. Failures land in `state.error` (e.g. "Profile not found", "Starting VPN failed: …", timeout message). Callers observe, they do not handle.
- Binder death / process kill: `serviceAvailable=false` immediately, automatic re-bind inside (`scheduleRebind` moves in), `state` continues emitting `Disconnected`-ish slices without throwing `DeadObjectException` at callers — today every screen's `collectAsState` can see stale `bound=false` treated as disconnected, forever.
- `VpnService.prepare()` is the one system call that *must* return an `Intent` to an Activity. It surfaces as `pendingPermission` in state (reader-visible), never as a throw.

**Configuration dependencies (what the module requires at construction):**
Context (for `bindService`, prefs, files), a `CoroutineScope` (owned/provided by the caller — see lifecycle below), a `Clock` (`elapsedRealtime`), a `Delay`/timeout policy (20 s connect window, 500 ms restart debounce), and the four internal ports from §5. No other ambient state.

**Performance contract (what callers rely on):**
- `state` emits **at most once per poll tick** (≈5 Hz), not per binder call. Today one tick fires 15 successive flow updates; recomposition storms are structural.
- `derived(selector)` is **cached and distinct-until-changed**: identical selector results do not re-emit; screens subscribe once (hoisted in the screen body) and recompose only on slice change.
- Entire-state collection is allowed but discouraged; slices are the norm. Cost of one tick: one `AppUiState` alloc + N selector evaluations — trivial vs. 75 IPC calls/sec today.
- Polling continues while the app process lives (it must: the tunnel runs in another process). A config flag can pause it when `activityLevel == STOPPED` if battery matters; not part of v1.

### 2.2 Writer — `UiCommand` (complete set)

```kotlin
sealed interface UiCommand {
    data class CONNECT(val profileName: String) : UiCommand           // prepare/perm + start + optimistic state + timeout
    data object DISCONNECT : UiCommand
    data object RESTART : UiCommand                                    // internal + exposed; only meaningful while Connected
    data class GRANT_PERMISSION_RESULT(val granted: Boolean) : UiCommand
    data object DISMISS_ERROR : UiCommand
    data class SET_THEME(val mode: ThemeMode) : UiCommand
    data class SET_FLOATING_CONTROL(val enabled: Boolean) : UiCommand  // also starts/stops FloatingControlService
    data class SET_NETSHIELD(val enabled: Boolean) : UiCommand
    data class SET_BLOCK_ADULT(val enabled: Boolean) : UiCommand
    data class SET_SPLIT_ENABLED(val enabled: Boolean) : UiCommand     // + debounced RESTART when Connected
    data class SET_SPLIT_BYPASS(val bypass: Boolean) : UiCommand       // + debounced RESTART when Connected
    data class TOGGLE_APP(val packageName: String, val enabled: Boolean) : UiCommand // + debounced RESTART
    data class SELECT_ZONE(val countryCode: String) : UiCommand        // rewrite default profile username + recents + (re)connect
    data class SAVE_PROFILE(val profile: ProfileDraft, val renamedFrom: String?) : UiCommand // add or edit; rename-aware
    data class DELETE_PROFILE(val name: String) : UiCommand            // stops tunnel if it is the active profile
    data object REFRESH_PROFILES : UiCommand                           // after any out-of-band change
    data object REFRESH_LOGS : UiCommand                               // re-collect logcat tail → debugLog
}
```

**What a single command hides (leverage):**
- `CONNECT`: resolves profile → `VpnService.prepare` → pendingPermission or `Utility.startVpn` → optimistic `Connecting` → session-zeroed usage → 20 s timeout timer that only fires inside the module (fixes the navigate-away bug).
- `SELECT_ZONE`: the entire duplicated zone-rewrite (slug/rapid/clip/generic/owl variants, sticky suffix preservation) now lives once; adds the recent-country write; performs stop+restart if connected. Both `CountriesScreen` rows **and** the floating bubble's country menu become `SELECT_ZONE` callers.
- `SET_SPLIT_ENABLED/BYPASS/TOGGLE_APP`: prefs write + debounced `RESTART` if connected — screen-local `scheduleRestart` deleted.
- `SAVE_PROFILE/DELETE_PROFILE`: CRUD **plus** the active-profile rename remap (`VpnViewModel.updateActiveProfileName` dies) **plus** stopping the tunnel when the deleted profile is live.

---

## 3. How the module is shaped (internal structure)

```
appstate/
├── AppState.kt            // interface (above) — the ONLY thing callers import
├── AppStateStore.kt       // the deep implementation (single immutable value + actor)
├── AppUiState.kt          // value type, ConnectionState, UiError, ProfileSummary, PendingPermission…
├── UiCommand.kt           // writer vocabulary
├── gateway/
│   ├── VpnGateway.kt      // INTERNAL port: suspend snapshot of binder state + stop()  (§5)
│   ├── AidlVpnGateway.kt  // production adapter: binds IVpnService, poll loop, rebind
│   └── FakeVpnGateway.kt  // test adapter
├── profiles/
│   ├── ProfileStore.kt    // INTERNAL port: list/get/save/delete/rename/default (ProfileManager behind it)
│   └── SharedPrefsProfileStore.kt
├── prefs/
│   ├── AppPrefs.kt        // INTERNAL port: theme/netshield/split/floating/usage keys, change stream
│   └── AndroidAppPrefs.kt // SharedPreferences + listener→SharedFlow (2-constructor injection for tests)
├── lock/  …               // (internal seams introduced only if a second adapter is honestly justified)
```

The store is one class: an actor loop consuming `UiCommand`s, a `MutableStateFlow<AppUiState>` it owns, a poll loop pulling `VpnGateway.snapshot()` at 200 ms, and pure reducer/effect code. **~everything that reads a screen today moves into `AppStateStore` or its adapters.** No engine file changes.

**Lifecycle (the honest answer to "who scopes it"):** `AndroidViewModel` was the scope. AppState must stay alive across the Activity (the tunnel outlives it) but must die when the app process does. Recommendation: an `AppStateHolder` (thin `AndroidViewModel` is *not* needed) — create it in `SocksApplication` scope (`onCreate`) and expose via a `CompositionLocal`/`LocalAppState`; `onTerminate` is unreliable, so leak via process death (acceptable: binder is auto-unbound; poll loop dies with the process). Keep the *name* `VpnViewModel` only as a compatibility adapter during migration (§7 Phase 1), then delete it.

---

## 4. Seam placement, adapters

- **The external seam is `AppState`'s interface**, located in the **app process** at `appstate/`. Every caller (screens, `Theme.kt`, navigation callbacks) crosses this seam; `AppNavigation` wires it once.
- **Adapters at the external seam: exactly one** — the production store. A second adapter is not justified at *this* seam (there is only one app), so this seam is real but not port-shaped; it exists for depth, not substitution.
- **The AIDL binder is an INTERNAL seam** behind `AppState` — see §9 for the full honest treatment. Two adapters (`AidlVpnGateway`, `FakeVpnGateway`) honestly justify it as a real seam for tests.
- **`FloatingControlService` and the notification bubble become readers of the same seam** (they are in the app process and currently re-bind AIDL for themselves at `FloatingControlService:75`). This removes the second state machine in the app. Note: *not* in the cluster's file list — flag as required follow-up, since leaving the bubble on its own derivation keeps two "connected" computations alive.

---

## 5. Internal seams (private to the implementation, used by its own tests)

| Internal seam | Production adapter | Test adapter | Why two? |
|---|---|---|---|
| `VpnGateway` — `suspend snapshot(): Snapshot`, `stop()`, `available:Flow<Boolean>` | `AidlVpnGateway` (bind, poll, rebind, `DeadObjectException` handling) | `FakeVpnGateway` (mutable fields, controllable failure) | Production vs. test — **justified** (§9) |
| `ProfileStore` — CRUD + default + usage read | `SharedPrefsProfileStore` (wraps `ProfileManager`) | `InMemoryProfileStore` | ProfileManager is a global singleton requiring a Context; fake makes tests pure |
| `AppPrefs` — typed get/set + change stream | `AndroidAppPrefs` (SharedPreferences + listener) | `InMemoryAppPrefs` | Same |
| `Clock` | `SystemClock` | `FakeClock` | 20 s timeout, debounce, elapsed-since |

Testability rule from DEEPENING.md is observed: tests live **at the AppState interface** (send commands, assert on `state`/`derived`) and at the internal seams only where the seam itself is the subject. Old per-flow ViewModel tests are deleted, not ported — the interface replaces them (replace, don't layer).

---

## 6. Depth analysis

**Today, depth is inverted.** `VpnViewModel` = 17 flows + 4 methods, each flow a trivial field copy → a shallow module with a 20-method interface. The screens are shallow too: each re-implements connection semantics, usage fallback, or prefs plumbing inline; the *real* logic (usage precedence, zone rewriting, permission/timeout choreography) is scattered across callers, which is why inconsistencies exist.

**After:** the same body of behavior sits behind 3 entry points.

- **Leverage per unit of interface learned:** one `derived { it.connection }` replaces 4 collectAsState + a triple-conjunction + a mirrored formula in the bubble. One `SELECT_ZONE` replaces ~60 lines duplicated in two places. One `TOGGLE_APP` replaces prefs-writing + restart scheduling replicated per toggle.
- **Locality:** every bug about "why does the UI say Connected but the IP card is empty" now has exactly one file to inspect (`AppStateStore` + reducer). The usage-precedence triple (`StatusScreen`, `ProxyCard`, VM comment saga about cross-process prefs staleness) collapses into one resolver with one explanatory comment.
- **Perf leverage:** poll→single emission conversion happens once instead of 15×7 collector fan-outs.

**What stays deliberately shallow (and why that's fine):** components (`ProxyCard`, `ConnectionCard`, …) remain dumb presenters — their interface is exactly the slice they render; they are kept shallow on purpose, they are the *callers* buying leverage.

---

## 7. Deletion test

Delete `AppState`. What reappears?

1. On every one of the 7 screens: 10–16 `StateFlow` collectors, the `isActuallyConnected` conjunction (this time in 3 or more places, since the bubble survives), permission/timeout plumbing, prefs snapshots in `remember`, direct `ProfileManager` reads inside composition, and the usage Triple-tree fallback (StatusScreen + ProxyCard diverge almost immediately: they already do).
2. The zone-rewrite username logic: re-copied into `CountriesScreen` and the bubble (first divergence: quickprize zone regex already drifted once — comment at `CountriesScreen:133`).
3. The connect-timeout: reborn as a `LaunchedEffect` that dies on tab switch (the current bug returns).
4. The restart-on-split-change debounce: rewritten in `SplitTunnelingScreen`.
5. Theme state: re-split between `Theme.kt` and `SettingsScreen`, re-introducing the toggle-desync bug.

That complexity is real (≈1500 lines across callers today) and would reappear wholesale. AppState is earning its keep: it is a **deep module** by the deletion test, not a pass-through.

---

## 8. Cross-screen inconsistencies that collapse

1. **Connected-country (4 computations → 1 pair).** `AppUiState.connection` yields the *selected zone* `zoneCountry = profiles.active.zoneCountryCode`; `ipInfo.countryCode` is the *geo truth*. Cards render from `derived { it.profileZone }` (ProxyCard chip, CountriesScreen checkmark) and `derived { it.ipInfo }` (ConnectionStatusCard, bubble flag). The two meanings finally have names and one owner; the "chip says US, IP card says Germany" puzzle becomes a documented fact, not a drift.
2. **Connection state (3 conjunctions → sealed `ConnectionState`).**
3. **Theme (2 holders → 1 slice)** — SettingsScreen and Theme.kt both read `derived { it.themeMode }`; the prefs listener in `Theme.kt:67` is deleted.
4. **NetShield on/off (2 snapshots → 1 slice)** that also stays in sync with the engine's live `reconcileNetshield` prefs listener.
5. **Split tunneling on/off (2 snapshots → 1 slice).**
6. **Usage precedence (3 readers of "live→retained→persisted" → 1 resolver in the store).**
7. **Recent countries (3 file reads → 1 slice + subscriptions).**
8. **Default-profile resolution (3 screens → `profiles.defaultProfileName`).**
9. **Connect timeout (screen effect → module timer):** survives navigation; the "Connecting… forever after leaving Home" bug is structurally impossible.
10. **Notification-permission preflight** (StatusScreen + SettingsScreen): stays in screens (it is system-dialog ephemeral UI), but each now terminates in one shared command — the duplicated orchestration around it shrinks to one pattern.

---

## 9. The AIDL seam — honest treatment

**Question:** should the binder be an adapter *behind* AppState (this design), or should AppState *be* a live binder — the module living in the :vpn process, emitting `AppUiState` across IPC?

**Answer: the binder stays an internal adapter behind AppState — but with honest eyes on its cost.**

- **AppState-as-binder fails the depth test.** The interface would have to cross IPC: `AppUiState` becomes parcelables, `derived()` selectors become remote invocations, every emission is a full-object marshalling (the geo payload alone is 9 strings + counters, polled at 5 Hz — TransactionTooLarge is a live risk). The 3-entry-point interface would balloon into versioned parcelables, death/restart callbacks, and a manual flow registry. That is an interface as complex as the implementation — the definition of shallow. Depth exists *because* the module lives in-process.
- **AppState stays in the app process, exactly where the ViewModel already is.** The process boundary is real and must be explicit: `:vpn` owns tunnel+metering truth; the app process owns the *model*. What changes is that the boundary is now inside one module instead of inside every screen's collector.
- **What the binder costs, stated plainly:** poll-based pull is 15 AIDL calls per 200 ms (≈75/s) — this design does not make IPC free. Two honest evolution paths, both *invisible behind the interface* (internal seam flexibility, DEEPENING.md §1):
  1. *Batching:* add one additive AIDL method `getSnapshot()` returning a single `VpnSnapshot` parcel (engine file touched only additively, or — to honor "never modify engine" strictly — keep 15 calls; the callers no longer see them either way).
  2. *Push:* one-way binder callback (`onSnapshot(VpnSnapshot)`) registered on bind — AppState's poll loop becomes a receive loop. This is a gateway-internal change; `derived()` and `command()` callers never notice.
- **Mistakes the seam absorbs (and must keep absorbing):** `DeadObjectException`, process death mid-rebind (`scheduleRebind` moves in), `onCleared` unbind, and the cross-process prefs staleness keep (usage prefs written by `:vpn`, read by the UI process). **The module centralizes the workaround — the storage race itself is a pre-existing platform fact it cannot delete** (multi-process SharedPreferences). The doc's job is to name this so nobody expects AppState to fix IPC or prefs races.

---

## 10. Testability

- `AppStateStore` is a **plain Kotlin class**: injected `VpnGateway`, `ProfileStore`, `AppPrefs`, `Clock`, `CoroutineScope`. **No Activity, no Robolectric** for the store core (only the three Android adapters need a Context, and they are thin).
- Interface-level tests (the test surface, per SKILL.md):
  - `CONNECT` → fake gateway "running + verified" → `connection` transitions Disconnected→Connecting→Connected; assert `derived { it.connection }` emitted values in order, via `Turbine`.
  - `SELECT_ZONE` while connected → gateway received `stop` then a connect for the rewritten username; `recentCountries` updated.
  - 20 s timeout with `FakeClock +25 s` → `error` set *without any screen effect in existence*.
  - `TOGGLE_APP` while connected → no restart, then debounce fires exactly one `RESTART` (fake clock advance).
  - Binder death: `FakeVpnGateway.disconnect()` → `serviceAvailable=false`, reconnect → state re-syncs.
  - Usage resolver: live > retained > persisted precedence, per profile, incl. the reconnect-zeroing rule.
- Selectors are pure functions over the value type — unit-test `derived`'s caching/distinctness directly.
- Old ViewModel-flow tests: **deleted** (replace, don't layer).

---

## 11. Migration sketch (screen-by-screen; no engine edits)

**Phase 0 — snapshot & scaffolding.** Tag `pre-appstate` per repo convention. Add `appstate/` package, store, adapters, `LocalAppState` in `SocksApplication`/`MainActivity`. Add a *compat* `VpnViewModel` facade exposing the old 17 flows implemented as `appState.derived { … }` slices — nothing else changes, app ships, old behavior bit-identical.

**Phase 1 — migrate screens one at a time (each a self-contained commit):**

1. **StatusScreen:** replace 16 `collectAsState` with 5–6 `derived` slices (`connection`, `ipInfo`, `profiles`, `usage`, `error`). Delete `persistedUsage`, `isActuallyConnected`, the `LaunchedEffect` timeouts, `serverName` remember (now `profiles.active.server:port`... precomputed in `ProfileSummary`). Keep: dropdown's `selectedProfile` (ephemeral), permission launchers → they now end in `command(CONNECT)` / `command(GRANT_PERMISSION_RESULT)`. Collect `pendingPermission` instead of handling return values.
2. **ProxiesScreen:** read `profiles` slice (cards get precomputed `ProfileSummary`, incl. zone chip + persisted usage — `ProxyCard` keeps its dumb presenter signature, inputs change from raw fields to the summary). `AddEditProxySheet` keeps 100% of its form state (pure ephemeral); Save → `command(SAVE_PROFILE)`, Delete → `command(DELETE_PROFILE)`; delete the direct `ProfileManager` touches and `reloadProfiles` calls.
3. **CountriesScreen:** `query` stays local; `connectedCountryCode` becomes a slice; `onCountryTap` → `command(SELECT_ZONE(code))`; `recentCountries` from state; permission flow like #1.
4. **SettingsScreen:** themeMode/floatingControl/netshield/split flags all from slices; UpdateChecker + dialogs stay fully local (ephemeral); floating-bubble permission choreography stays (system dialogs) but the terminal action is `command(SET_FLOATING_CONTROL(true))`; theme row → `command(SET_THEME)`.
5. **SplitTunnelingScreen:** `installedApps` enumeration stays local (device data, not app model); `splitEnabled`/`bypassMode`/`toggleStates` come from `splitTunneling` slice; `scheduleRestart` deleted (module debounce). Writes → `SET_SPLIT_*`/`TOGGLE_APP`.
6. **NetShieldScreen:** both flags from slice; switches → `SET_NETSHIELD`/`SET_BLOCK_ADULT`. (Engine keeps its own live prefs listener — engine untouched.)
7. **DebugLogsScreen:** `logs` from `debugLog` slice; entry effect becomes `command(REFRESH_LOGS)` (+ optional 2 s auto-refresh loop *while visible* — visibility is the screen's local concern); Share/Copy stay.
8. **Theme.kt:** themeMode from slice; delete local state + prefs listener.
9. **AppNavigation:** pass `appState` (not viewModel); screens' signatures change to `(appState, …)`.

**Phase 2 — delete.** Remove compat facade, `VpnViewModel`'s 17 flows + actor logic, old flow tests, and every `remember { mutableStateOf(prefs… ) }` snapshot now covered. `grep -r "ProfileManager.getInstance" ui/` should return… the sheet's test-touch only. Then the bubble migration (separate ticket, out of cluster scope): `FloatingControlService` re-binds through the gateway and reads slices.

Ordering rationale: slices-first keeps every commit behavior-preserving; the risky logic moves (usage resolver, timeout, zone switch) land early so they soak in the nightly builds before the UI churn.

---

## 12. Honest drawbacks

1. **One value class, many slices.** Every 200 ms tick rebuilds `AppUiState`; sloppy `derived` consumers (or a screen collecting the whole state) lose the recomposition benefits. Mitigation is contractual (caching + distinctness) and must be enforced in review — a new discipline the codebase lacks today.
2. **Single-writer purity costs a hop for trivial writes.** `SET_THEME` is a 10-line command around a prefs write that today is inline. Indirection for uniformity is a real, admitted tax; it pays off only because 80% of writes are *not* trivial.
3. **Lifecycle re-homing.** `AndroidViewModel` scoping dies; AppState is application-scoped by process lifetime. If the app later needs a killable scope (or ViewModel-cached restarts), the store must grow a re-arm path — not designed in v1.
4. **Cross-process prefs staleness is inherited, not fixed.** Usage prefs written by `:vpn` remain technically stale-read in the app process; AppState routes around it (retained session totals) the way the ViewModel did, just centrally. If `getSnapshot()`-style batching is ever adopted, the engine-file "never modify" rule needs an explicit exception decision.
5. **Engine-coupled behavior migrates behind a seam the engine cannot see.** `SELECT_ZONE` rewrites the default profile *in the app process* while the tunnel in `:vpn` still runs the old zone; the stop-restart sequence papers over the race. This race *exists today*; AppState makes it deterministic (single sequence) but does not eliminate the two-process fact.
6. **Migration risk is behavioral.** The usage resolver and timeout move with subtle semantics (see the `_lastProfileName` comment saga). Phase 1's bit-identical compat facade is the guard, but it lengthens the migration and tempts "we'll clean up later" rot in the compat layer.
7. **Bubble/notification out of scope.** Until `FloatingControlService` migrates (phase 3), the app genuinely runs two state derivations; the design's headline win (one connection truth) is only fully true after that ticket. Bluntly stated so nobody misreads the deployment.
8. **Poll cost unchanged in v1.** 75 IPC calls/s stays until a batching/push gateway lands; the *visibility* of that cost moves inside the module, which is the actual win (it becomes an internal, fixable seam rather than a tax on 20 call sites).

---

## 13. Summary

Deepen Cluster 7 into one module: `AppState` (3 readers, 1 writer) owning connection, geo info, profiles, usage, theme, NetShield, split tunneling, recent countries, pending permissions, and the debug log tail — one immutable `AppUiState`, commands as the only writer, slices for readers, binder kept as an honest internal adapter seam behind it (app-process module, :vpn boundary explicit), pure-Kotlin store tested at its own interface with fake gateway/profile/prefs/clock adapters, migrated screen-by-screen behind a bit-identical compat facade, with the usage resolver and connect-timeout as the highest-risk moves and the floating bubble as the explicitly staged final reader.