# Cluster 8 — Country Switching & Bubble Menu: Design

Design doc only — no repo changes. Vocabulary: Module, Interface, Adapter, Seam, Depth,
Leverage, Locality, deletion test, one-vs-two adapters (SKILL.md / DEEPENING.md).

---

## 0. State of the cluster (what we're deepening)

| File | Role | Country-switch content |
|---|---|---|
| `FloatingControlService.kt` (1258 ln) | Bubble + glue | `handleTap()` double-tap (300 ms window, `previousCountryCode` field, inline `listOf("DE","DZ","FR","CI").random()` fallback, ln 801-822); `onBubbleCountrySelected()` full per-provider rebuild + sticky-suffix regex + restart-orchestration (ln 855-904); `supportsCountrySwitch()` (ln 846) |
| `BubbleMenuOverlay.kt` (600 ln) | Menu view | Grouping/pinning/dedup (`shown` set built **3×**: ln 100-133, 136-149, 152-193), digit search, window params, IME, 4-side placement, animations, "not available" snackbar |
| `CountriesScreen.kt` (354 ln) | Compose screen | `onCountryTap()` (ln 121-169) — **verbatim duplicate** of `FloatingControlService.onBubbleCountrySelected` incl. the OWL sticky-suffix regex (proven drift hazard: two copies, one fix site remembers the other doesn't) |
| `ProxyProviders.kt` (119 ln) | Pure token formatter | `detectType`, `parseCountry`, `extractBase`, `buildUsername` (already pure JVM) |
| `Countries.kt` (303 ln) | Catalog | `Country(code,name,phone)` + flag via `Utility.countryCodeToFlag` (Android file, but flag math is pure) |
| `Utility.kt` ln 477-498 | History storage | `recent_countries.txt` raw file, read on every menu open (main-thread file I/O), cap 10 |

**Rules currently spread across the cluster:** double-tap→previous, previous-tracking only
in memory (lost when `:vpn` or UI process dies), random fallback list, per-provider username
rebuild (OWL sticky suffix preservation, RAPID/CLIP base rebuild, GENERIC separator/case
preservation), "previous is only recorded when it parses and differs from the target",
"no-op on malformed username", `TYPE_CUSTOM → unsupported`, no revert on failed switch
(a failed restart leaves the username mutated on disk — there is **no undo today**).

---

## 1. Design at a glance

Two modules replace the scattered rules + one view module gets a real interface:

1. **`CountrySwitcher`** — ONE pure module (zero Android imports, plain JVM). Owns every
   decision rule: double-tap→previous resolution, random fallback (injected RNG), per-provider
   username rebuild (moved verbatim from the two duplicated sites), revert construction,
   and the menu grouping/search rules (deepened sibling content merged in — see §7 why).
2. **`CountryMenuHost`** — the Android view module. Interface
   `show(anchor, model, config) / hide() / isShowing()`. Receives a **pre-sorted MenuModel**
   value; owns only window params, search wiring, IME handling, geometry, animations.
3. **`SwitchHistoryStore`** — a caller-facing persistence port with two adapters
   (SharedPreferences + in-memory). The pure module never sees it; callers load
   `SwitchState` from it and apply `HistoryRecord` ops returned *inside the plan*.

The service glue (double-tap, menu-open, menu-pick, restart, revert-on-failure) becomes
~10 real lines; `BubbleMenuOverlay` drops its data-prep third (~90 lines of dedup/group/
search that today cannot be unit-tested).

---

## 2. Module shape

New pure package `app/src/main/java/net/typeblog/socks/util/country/` (sibling of the
already-pure `util/ProxyProviders.kt`). No file in this package may import `android.*` —
enforced by convention, verified by the JVM test task compiling it without an Android
runtime.

```
util/country/
  CountrySwitcher.kt        interface + ALL value types (SwitchOutcome, SwitchPlan,
                            SwitchState, CountryChoice, MenuModel, HistoryRecord) — ~110 ln
  CountrySwitcherImpl.kt    rule engine + menu group/search + RNG seam — ~180 ln
  CountryCatalog.kt         interface (Country, byCode, all)
  SwitchHistoryStore.kt     port + RecentList (pure cap-10 dedup logic) + InMemory impl
  PrefsSwitchHistoryStore.kt  production adapter (android imports OK in THIS file)
 
util/Countries.kt           refactored to `object Countries : CountryCatalog` (keeps ALL;
                            drops flag/flagOrNull — flag emoji becomes a view-side render call
                            via the existing Utility.countryCodeToFlag, already what rows do)
test/java/.../CountrySwitcherImplTest.kt    pure JVM unit tests (golden) 
Android side (existing files, edited):
  BubbleMenuOverlay.kt      implements CountryMenuHost; receives MenuModel; deletes data-prep
  FloatingControlService.kt replaces the two country blocks with switcher glue
  CountriesScreen.kt        replaces duplicated onCountryTap with the same glue
```

---

## 3. `CountrySwitcher` — complete interface spec

### 3.1 Types

```kotlin
interface CountryCatalog {
    /** Uppercase-with-Locale.ROOT lookup; O(1). Total. */
    fun byCode(code: String): Country?
    /** Name-sorted list as today (drives the ALL section order). Constant. */
    val all: List<Country>
}

data class Country(val code: String, val name: String, val phone: String)

data class SessionView(
    val host: String,        // profile server, e.g. gate.owlproxy.com
    val username: String,    // profile username — the only mutable switch input
)

data class SwitchState(
    val recents: List<String>,      // newest first, ≤ 10, uppercase, deduped
    val lastSwitchFrom: String?,    // 'previous country' — target of a Previous choice
)

sealed interface CountryChoice {
    data class Pick(val code: String) : CountryChoice   // menu row / explicit select
    data object Previous : CountryChoice                // double-tap
}

sealed interface SwitchOutcome {
    data class Planned(val plan: SwitchPlan) : SwitchOutcome
    data object NoOp      : SwitchOutcome               // nothing to do (see errors)
    data object Unsupported : SwitchOutcome             // TYPE_CUSTOM profile
}

sealed interface HistoryRecord {
    data class Recent(val code: String) : HistoryRecord              // prepend, dedup, cap 10
    data class LastSwitchFrom(val code: String) : HistoryRecord      // overwrite
}

data class SwitchPlan(
    val newUsername: String,            // full rebuilt username — apply via profile.setUsername
    val record: List<HistoryRecord>,    // persist ONLY after the switch is verified connected
    val revert: SwitchPlan?,            // exact inverse; see §3.4 invariants
)

// ---- menu grouping (deepened into the same module, see §7) ----

sealed interface MenuSection {
    data class Pinned(val row: CountryRow) : MenuSection              // connected, not in recents
    data class Recent(val rows: List<CountryRow>) : MenuSection
    data class All(val rows: List<CountryRow>) : MenuSection
}
data class CountryRow(val code: String, val name: String, val phone: String, val isConnected: Boolean)

data class MenuModel(val sections: List<MenuSection>)   // ≥ 1 section, ALL always present
```

### 3.2 Entry points

```kotlin
interface CountrySwitcher {
    /** detectType != TYPE_CUSTOM. Total; this replaces supportsCountrySwitch()'s try/catch. */
    fun supports(host: String, username: String): Boolean

    /** The one decision call. Pure function of its inputs. */
    fun next(current: SessionView, state: SwitchState, choice: CountryChoice): SwitchOutcome

    /** Grouped menu for the overlay: Pinned → Recent → ALL. */
    fun menu(recents: List<String>, connected: String?): MenuModel

    /** Keystroke filter, connected row first (today: connectedItems' then others'). */
    fun search(query: String, allRows: List<CountryRow>): List<CountryRow>
}
```

### 3.3 Decision rules inside `next` (moved verbatim from the two duplicate sites)

1. `type = ProxyProviders.detectType(host, username)`. `TYPE_CUSTOM → Unsupported`.
2. Resolve target:
   - `Pick(code)` → catalog-validated `code.uppercase(ROOT)`. Unknown code → `NoOp`.
   - `Previous` → `state.lastSwitchFrom ?: randomFallback(random)` — random draws from the
     configurable pool (default `["DE","DZ","FR","CI"]` — the exact list in `handleTap()`
     today), **excluding the current country** (fixes today's self-switch: the old inline
     `.random()` could pick the country you're already on and restart in place).
3. `target == parseCountry(username, type)` (case-insensitive) → `NoOp` (no mutation, no
   restart; today's silent `?: return` paths become this or Unsupported explicitly).
4. Rebuild per provider — byte-identical to the two existing copies:
   - OWL: `^(.+?)_custom_zone_[a-zA-Z]{2}(_st__city_sid_\d+_time_\d+)?$` → base +
     `_custom_zone_` + lower(target) + captured suffix (SID/time **preserved**). No match → `NoOp`.
   - RAPID/CLIP: `extractBase` → `buildUsername(base, type, target)` (upper). No base → `NoOp`.
   - GENERIC: `genericParts` → `buildUsername(base, type, target, separator, upper)` →
     separator and casing preserved. No parts → `NoOp`.
5. Build the plan:
   - `newUsername = rebuilt`.
   - `record` = `[Recent(target)]` + `LastSwitchFrom(currentCountry)` **iff** currentCountry
     parses non-blank and differs from target (this is exactly the
     `!currentCountry.isNullOrBlank() && !code.equals(...)` guard that sets
     `previousCountryCode` today).
   - `revert = SwitchPlan(newUsername = current.username, record = emptyList(), revert = null)`.

### 3.4 Invariants (the contract callers rely on)

- **Ordering**: `next` is a pure function — no ordering constraints, safe from any thread
  (stateless; all inputs passed in). Callers may call `supports` / `menu` / `search` in any
  order. `menu` must be called before `search` (search takes rows derived from the menu's
  flattened sections).
- **Revert contract (explicit)**: `Planned` ⇒ `plan.revert != null`,
  `plan.revert!!.revert == null`, `plan.revert!!.record.isEmpty()`,
  and `plan.revert!!.newUsername` is the **literal byte-identical pre-switch username**
  (not a rebuild — sticky SIDs and suffix case survive any retreat). Applying the revert
  lands the profile on the exact pre-switch config. Chain terminates at depth 1.
- **Record contract**: `record` is non-empty iff the switch actually changes the country
  (`NoOp`/`Unsupported` carry no plan, so no record). Callers apply `record` **only after
  verified connect** (the tunnel's `isProxyVerified` gate); on failure they apply `revert`
  and drop the record. A failed switch must therefore leave: username = pre-switch,
  recents = pre-switch, lastSwitchFrom = pre-switch.
- **Case/locale**: all normalization is `Locale.ROOT` only — never the platform locale
  (Turkish-I hazard), matching today's `uppercase(Locale.ROOT)` usage in the overlay.
- **Errors**: no exceptions escape `next`/`supports`/`menu`/`search`. Three outcomes cover
  the space: `Unsupported` (custom profile — the overlay's snackbar / screen's toast),
  `NoOp` (malformed username, unknown code, pick-equals-current, previous-equals-current —
  caller does nothing), `Planned` (mutate + restart + hold revert).
- **Config**: constructor `CountrySwitcherImpl(catalog, randomPool = DEFAULT_RANDOM_POOL,
  random = Random.Default)`. `randomPool` and `random` are implementation configuration
  with sensible defaults — callers in production pass nothing.
- **Perf**: `next`/`supports` are O(1) with O(1) allocations. `menu` is O(n) over 195
  countries (one pass, three buckets — collapses the overlay's three-pass `shown` dance).
  `search` is O(n·|query|) worst case with the lowercase name precomputed once per row
  (implementation detail: `CountryRow` carries the lowercased name internally). Zero I/O —
  notably the overlay no longer reads files inside `show()`.
- **Menu invariants**: every country appears at most once; `isConnected` marks at most one
  row; `Pinned` appears iff connected ≠ null and ∉ recents; `Recent` is newest-first and
  deduped; `All` is the exact complement, catalog order; sections never empty (ALL always
  present); NO RESULTS is not a module concern — an empty search result list is that state,
  and the view renders the label (today's "NO RESULTS" TextView stays in the view).

### 3.5 Search semantics (verbatim from today, made total and deterministic)

`digits = query.filter { it.isDigit() }`; row matches iff
`lower(name).contains(lower(query))` ∨ `code.lower().contains(lower(query))` ∨
(`digits.isNotEmpty()` ∧ (`phone.startsWith(digits)` ∨ `digits.startsWith(phone)`)).
Empty query returns `allRows` unchanged. Connected row sorts first, remainder in catalog
order (mirrors the overlay's `connectedItems`/`otherItems` split).

---

## 4. `CountryMenuHost` — complete interface spec

```kotlin
interface CountryMenuHost {
    /** Opens the popup near the bubble. model arrives pre-sorted (pure). Idempotent while showing. */
    fun show(anchor: AnchorParams, model: MenuModel, config: MenuConfig)
    /** Dismisses; idempotent; fires config.onDismiss once per show. */
    fun hide()
    fun isShowing(): Boolean
}

data class AnchorParams(val centerX: Int, val centerY: Int, val bubbleSizePx: Int)

data class MenuConfig(
    val supportsCountrySwitch: Boolean,
    val onPick: (String) -> Unit,     // country code, uppercased
    val onDismiss: () -> Unit,
)
```

Implementation = today's `BubbleMenuOverlay` minus data-prep: window params and
`TYPE_APPLICATION_OVERLAY` selection, MATCH_PARENT scrim + alpha-in, focusable window +
`SOFT_INPUT_ADJUST_RESIZE`, search `EditText` focus/IME listeners, `globalLayoutListener`
reposition-above-IME, 4-side clamp geometry (`contentBounds`, `dp`, pivot/scale grow-in),
connected-dot pulse animator, snackbar "Country switching is not available for this
profile" (when `!config.supportsCountrySwitch`), scrim click-to-close, `cleanup()`.

**Invariants**: `show` while showing = no-op (today's `if (isShowing()) return`). `onPick`
fires at most once per show. No callback fires after `hide()`. `BadTokenException` and
window-removal races are swallowed and logged — the host never throws to callers. All
inflation/geometry work is posted/measured, never blocking. **The host no longer touches
`Context` storage** — recents arrive as part of `MenuModel`; this kills the main-thread
file read in `show()` today.

**Two adapters**: production `BubbleMenuOverlay` + a recording fake in the glue test
(`PlaybackMenuHost` used by the FCS-glue tests) — the seam is real (§6).

---

## 5. `SwitchHistoryStore` — the storage port (and the two verdicts asked for)

```kotlin
interface SwitchHistoryStore {
    fun recent(): List<String>              // newest first
    fun lastSwitchFrom(): String?
    fun apply(record: HistoryRecord)        // Recent = prepend+dedup+cap10; LastSwitchFrom = overwrite
}
```

Implementations: `PrefsSwitchHistoryStore` (SharedPreferences — `Constants.PREF_RECENT_COUNTRIES`
already exists and is **unused**; add `pref_last_switch_from`; one-time migration reads the
legacy `recent_countries.txt` once and then deletes the file) and `InMemorySwitchHistoryStore`
(tests JVM, and lets Compose screens/previews hold state in `remember`).

**Where 'previous country' + RECENT live — decision.** Both live in the shared store, keyed
as above. They enter the pure module only as the `SwitchState` value; the module returns
`HistoryRecord`s, which callers persist. Persisting `lastSwitchFrom` (vs today's in-memory
field in the service) makes the double-tap toggle work **across surfaces**: switch in
CountriesScreen, then double-tap the bubble, and it toggles back — today that returns the
stale in-memory value (or random after a process kill). This is the one intentional
behavior change; see drawbacks §10.3.

**Two-adapters test (asked: "is the FCS pill a second consumer of history today?")** — No.
The pill (`updateFlagPill`, ln 441-471) consumes **live VPN state** (`vpnService.countryCode`
+ `currentIp`), not history; it is not a history client and must stay that way (it's a
per-200ms poll read of the binder, not storage). The real history consumers are three:
`BubbleMenuOverlay` (read), `CountriesScreen` (read/write), `ProxiesScreen` (read/write).
So the storage seam has two production adapters plus the in-memory test/in-memory-Compose
ones — the one-vs-two rule is comfortably passed, and the port is justified.

**Internal seam**: the cap-10/dedup/move-to-front logic lives in a pure value type
`RecentList` inside `SwitchHistoryStore.kt`, consumed by both adapters so the rule is
tested once in JVM and the adapters stay dumb (DEEPENING: replace, don't layer — old
`Utility.getRecentCountries/addRecentCountry` are **deleted**, not wrapped).

---

## 6. Seams and glue

```
  FloatingControlService / CountriesScreen          (callers)
        │                  │
        │ SwitchState-in, SwitchPlan/Outcome-out    ← SEAM A (pure module interface)
        ▼
  CountrySwitcherImpl  ──▶  ProxyProviders (pure, reused as-is)
        │
        ▼
  SwitchPlan.record ──▶  SwitchHistoryStore  ● PrefsSwitchHistoryStore (FCS, screens)
        ──▶ recent/lastSwitchFrom            ● InMemorySwitchHistoryStore (tests, previews)
                                            ← SEAM B (caller-facing port, 2+ adapters)

  FloatingControlService ──▶ CountryMenuHost  ● BubbleMenuOverlay (production)
                                            ● PlaybackMenuHost  (glue tests)
                                            ← SEAM C (view seam, 2 adapters)

  MenuModel ── pure menu()/search() inside SEAM A (same module — one delete-test locus)
```

**Seam A** is where the depth pays: the same `next()` call is exercised by the service
(double-tap + menu pick) and the Compose screen (tap), and every rule-golden test crosses
the same seam (DEEPENING: interface = test surface).

**Glue (FloatingControlService, replacing ln 801-822 + 846-904 — the "~10 lines"):**

```kotlin
private fun countryChoice(choice: CountryChoice) {
    when (val o = switcher.next(session(), SwitchState(store.recent(), store.lastSwitchFrom()), choice)) {
        is Planned -> applySwitch(o.plan)
        is NoOp -> Log.d(TAG, "Country switch: no-op")
        is Unsupported -> {}                    // menu already hides rows; screen toasts
    }
}
private fun applySwitch(p: SwitchPlan) {                 // glue, not rules
    pendingPlan = p                                      // holds p.revert
    profile.setUsername(p.newUsername)
    when (state) { CONNECTED -> { stopVpn(); pollHandler.postDelayed({ startVpn() }, 500) }
                  DISCONNECTED -> startVpn(); CONNECTING -> {} }
}
private fun onVerifiedConnected() { pendingPlan?.let { p -> p.record.forEach(store::apply); pendingPlan = null } }
private fun onFailed()          { pendingPlan?.let { p -> p.revert?.let { profile.setUsername(it.newUsername) }; pendingPlan = null } }
```

`pollState`'s existing `isProxyVerified` gate fires `onVerifiedConnected()`; the existing
connect timeout fires `onFailed()`. `CountriesScreen.onCountryTap` becomes the same three
calls modulo its `viewModel` restart path. Double-tap timing (300 ms) stays a gesture
constant in the service — it is input choreography, not a country rule.

---

## 7. Why one module, not a sibling sorter (and the honest weak point)

The prompt permits `CountryMenuSorter` as a sibling. **Adopted: merged into CountrySwitcher**
for one reason: the sorter's deletion test is weak — the only consumer of `menu()`/`search()`
is the overlay, so a standalone sorter is a single-consumer pure module (hypothetical-seam
territory). Merged, its rules share the delete-test locus of the cluster, live next to the
catalog they both need, and land in one golden-test file. Internal source organization keeps
the two concerns apart (private `MenuRules` section in the impl).

Honest counterpoint recorded: if the menu UX starts evolving separately from switch rules
(ordering a/b-tests, recents-pinning preferences), the merged module churns its golden
tests for reasons orthogonal to switching. Re-splitting at that point is a mechanical
extract — the value types (`MenuModel`) are already isolated for it.

---

## 8. Depth analysis

**Interface size**: 4 entry points; a caller learns ~8 small data types; the two hot
calls (`next`, `menu`) are single-parameter aggregations. **Implementation**: ~300 lines of
rules — 4 provider rebuild branches, sticky-suffix regex, double-tap resolution, random
fallback with exclusion, revert construction, three-bucket grouping, dual-direction digit
search — *plus* the consolidation of today's two verbatim-duplicated ~35-line blocks
(FCS ln 855-904 ≈ CountriesScreen ln 121-169) and the overlay's triple `shown`-set build.

**Leverage**: every caller (service ×3 sites, screen, overlay, N tests) gets the full rule
set for one short call. **Locality**: the class of bug where FCS's sticky regex gets fixed
and CountriesScreen's copy doesn't becomes impossible; the "random fallback doesn't exclude
the current country" bug and the "no revert on failed switch" gap get fixed at the one
place where they live.

**Seam placement**: rules pure (JVM-testable without Robolectric), storage behind a
2-adapter port at the caller side, view behind a 2-adapter seam (production overlay +
playback fake for glue tests). No seam was invented for a single adapter.

**Deletion test** — delete `CountrySwitcher`:
- ~60 lines of provider-rebuild + sticky regex + random fallback + revert construction
  reappear **at two production call sites** (FCS and CountriesScreen — they already
  disagree on double-tap-random: FCS has the inline fallback, CountriesScreen has none);
- `supportsCountrySwitch`'s try/catch discipline reappears at both callers;
- menu grouping (the 3× `shown` dance) reappears inside the overlay, untestable.
Complexity does not vanish — it fans out to 3 consumers. The module earns its keep.

---

## 9. Testability — the interface is the test surface

Plain JVM JUnit in `test/java/.../CountrySwitcherImplTest.kt`. Fixture does the
`ProxyProviders` catalog as-is (pure); `CountrySwitcherImpl(catalog, pool = listOf("DE","FR"),
random = Random(42))`.

**RNG seam**: `kotlin.random.Random` (pure, JVM) injected in the constructor
(`random = Random.Default` in production). Golden tests seed it (`Random(42)`) so the
fallback draw is deterministic; the pool is also injectable so tests don't depend on the
shipping `["DE","DZ","FR","CI"]`. The seam is **internal** (constructor, not part of the
interface contract callers reason about) — per DEEPENING, internal seams stay private to
the module.

| # | Golden test | Input → assertion |
|---|---|---|
| 1 | double-tap toggle sequence | `Pick(FR)` then `Previous` then `Previous`… → targets FR,DE,FR — the lastSwitchFrom chain evolves exactly like today's `previousCountryCode` dance, now persistent |
| 2 | Previous, no lastSwitchFrom, no recents | seeded RNG → code from pool; never equals current country |
| 3 | Previous with lastSwitchFrom | target = lastSwitchFrom, regardless of recents[0] |
| 4 | Previous == current | → `NoOp` (no restart) |
| 5 | Pick == current (case-insensitive, "de" vs "DE") | → `NoOp` |
| 6 | OWL sticky suffix preserved | `user_custom_zone_de_st__city_sid_12345678_time_5` → `user_custom_zone_fr_st__city_sid_12345678_time_5` (SID literal) |
| 7 | OWL plain | `user_custom_zone_de` → `user_custom_zone_fr` |
| 8 | OWL malformed (owlproxy.com host, no zone in username) | → `NoOp` |
| 9 | RAPID / CLIP | `x-residential-DE`→`x-residential-FR`; `y-region-UK`→`y-region-FR` |
| 10 | GENERIC separator/case | `acct_de`→`acct_fr` (lower kept), `user-US`→`user-FR` (upper kept) |
| 11 | GENERIC non-ISO token (`user-XX`) | → `Unsupported` (mirrors `genericParts` ISO gate) |
| 12 | TYPE_CUSTOM profile | `supports`=false; `next` → `Unsupported` |
| 13 | revert contract | for every `Planned`: revert.newUsername == session.username (exact string), revert.revert == null, revert.record empty |
| 14 | record contract | real switch → `[Recent(target), LastSwitchFrom(old)]`; NoOp/Unsupported → nothing |
| 15 | menu() grouping | connected not in recents → Pinned only; connected in recents → marked in Recent, no dupe; All = complement; no country twice, ever |
| 16 | search() digits | `"49"` matches DE (`startsWith` both ways); `"1"` matches US/CA (shared +1); `"united"` name match; `"zzz"` → empty |
| 17 | search() ordering | connected row first, rest catalog order; empty query returns allRows |

`RecentList` (cap 10, dedup, move-to-front) gets its own small JVM suite; the prefs
adapter stays a dumb delegate (no instrumented tests needed for it — the rules it applies
are tested above).

Delete-with-replace: there are no existing unit tests for this logic (it lived inside an
Activity-adjacent service), so nothing to retire; what gets **deleted in code** is the
duplicated blocks and `Utility.getRecentCountries/addRecentCountry`.

---

## 10. Migration sketch (per-commit, each lands buildable)

1. **Pur**: add `util/country/` pure types + impl + `Countries : CountryCatalog` refactor
   (flag/flagOrNull move to the Android side; verify nothing else imports them — likely
   `ProxyCard`'s flag rendering already goes through `Country.flag`; route through
   `Utility.countryCodeToFlag`). Add JVM tests, goldens 1-17. No wiring changes.
2. **Store**: `SwitchHistoryStore` + prefs adapter + one-time file→prefs migration +
   `RecentList`. Switch the 3 read/write sites (Overlay, CountriesScreen, ProxiesScreen)
   to the store in the same commit; delete `Utility.getRecentCountries/addRecentCountry`
   (constants file key now in use).
3. **Switcher wiring — FCS**: replace the double-tap block and `onBubbleCountrySelected`
   with the §6 glue; add `pendingPlan` revert orchestration in `pollState` + timeout;
   delete `previousCountryCode` field.
4. **Switcher wiring — CountriesScreen**: replace the duplicated `onCountryTap` (~35 ln)
   with the same glue (toast on `Unsupported` kept).
5. **Menu host**: `BubbleMenuOverlay` implements `CountryMenuHost`; `show` takes
   `(AnchorParams, MenuModel, MenuConfig)`; delete `Utility.getRecentCountries` read inside
   `show()`, the three `shown`-set builds, and the inline search list; delete the "NO
   RESULTS"/section TextView construction decisions that moved into pure (`NO RESULTS` label
   stays, but is rendered on `search() == empty`).
6. **Glue tests**: `PlaybackMenuHost` + seeded-RNG tests for FCS glue (double-tap path,
   revert-on-failure path via fake `isProxyVerified`).

Nothing here touches engine files (`SocksVpnService.kt`, `IVpnService.aidl`, `Utility.kt`
beyond the two deleted history functions, `ProfileManager.kt`); `bubble_country_row.xml`
and `bubble_menu.xml` unchanged.

---

## 11. Honest drawbacks

1. **Revert-on-failure changes today's UX.** Currently a switched-but-unverified country
   stays for the *next* manual connect (arguably intended: "keep my pick, fix the
   connection"). The new contract restores the pre-switch username on timeout, which is the
   prompt's requirement but a real behavior change; if a "stick" notion is wanted later it
   becomes a config flag on `next`, not a rewrite.
2. **Persistent lastSwitchFrom changes double-tap semantics across surfaces.** A switch made
   in CountriesScreen now steers the bubble's double-tap (previously FCS-local in-memory).
   Toggling across surfaces is arguably the point, but a user who switched in-app minutes
   ago gets a different double-tap target than they expect from the bubble's own history.
   Mitigation: recents + lastSwitchFrom are both cheap to surface in the pill/menu later.
3. **`BubbleMenuOverlay` stays ~350 lines of view code.** The host's depth is unchanged —
   window/IME/geometry choreography is inherently Android and untestable in JVM; only its
   *data third* became pure. The interface earns its keep via the playback adapter; if that
   feels forced in review, the interface may be dropped with the glue left untested — I
   recommend keeping it, the glue tests are the ones catching revert-orchestration bugs.
4. **Single-consumer search/menu rules** (§7): the sorter content would fail the
   one-vs-two test as a standalone module; merged, its tests are golden but only one
   caller benefits. Accepted; re-split is mechanical if a second menu consumer appears.
5. **Malformed-username `NoOp`s are silent** — same as today's `?: return`, but now
   explicit and tested; a permanent NoOp string ("a switch the engine will never make")
   is not surfaced to the user. Noting it so it's a conscious choice.
6. **One-time migration code** for `recent_countries.txt` → prefs lives in the adapter and
   must be deleted once shipped; and the prefs key (`PREF_RECENT_COUNTRIES`) was reserved
   months ago — fine now, but it underlines that storage has drifted before.
7. **400ms-bookkeeping**: `pendingPlan` is a new piece of service state; the revert hook
   fires from `pollState`'s verified gate, which today only classifies CONNECTED/CONNECTING/
   DISCONNECTED. Two small branches — but the service is already long, and the new state
   must be reset in `onDestroy`/`onServiceDisconnected` to avoid reverting a *later* manual
   connect.

---

## 12. One-line summary

Split the cluster into a pure `CountrySwitcher` rule engine (one `next()` call owns
double-tap target resolution, provider rebuilds, sticky-suffix preservation, seeded-random
fallback, revert plans, and menu grouping) behind which sit a 2-adapter
`SwitchHistoryStore` port and a `CountryMenuHost` view seam, leaving ~10 lines of glue in
the service and ~35 duplicated lines deleted from CountriesScreen.