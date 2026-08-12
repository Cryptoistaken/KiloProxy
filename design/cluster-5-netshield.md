# Cluster 5 — NetShield: Radical Design (Pure Policy + Effect-Only Reconfigurator)

> Design agent output. NO repo modifications were made. Vocabulary per `codebase-design`: Module, Interface (invariants/ordering/errors/config/perf), Adapter, Seam, Depth, Leverage, Locality, deletion test, one-vs-two adapters.

---

## 0. Status quo (as-built, verified by reading the code)

Four touch points own NetShield behavior today, tangled across two files, one resource, and one UI:

| Piece | Location | What it does |
|---|---|---|
| `Utility.netshieldPolicy(context, server, user, realCountry): NetshieldPolicy` | `app/src/main/java/net/typeblog/socks/util/Utility.kt:212` | Reads `SharedPreferences` itself (Context), derives country from username via `ProxyProviders.parseCountry` or takes `realCountry`, applies a hardcoded rule: `{CN,RU,IR}` → plain DNS; else AdGuard `94.140.14.14`, or CleanBrowsing Family `185.228.168.168` when "block adult" is on. Returns a data class holding only `upstream: String?`. Android-coupled (Context, prefs, `ProxyProviders`), untestable on JVM. |
| `Utility.makePdnsdConf(context, dns, port, upstream)` | `Utility.kt:181` | `String.format` against the Android string resource `R.string.pdnsd_conf` (in `app/src/main/res/values/pdnsd.xml`), deletes + rewrites `pdnsd.conf`, touches `pdnsd.cache`. All errors swallowed. Pass-through-ish: template formatting + a file write. |
| `SocksVpnService.reconcileNetshield()` | `SocksVpnService.kt:879` | Computes policy, **change-guard lives in service state** (`if (policy == mNetshieldPolicy) return`, line 885), writes conf, destroys `mPdnsdProcess`, relaunches via `launchPdnsd` (line 854). Triggered from the prefs listener (line 322–332: toggles while tunnel up) and from `applyIpInfo` (line 910: real exit country arrives). |
| `SocksVpnService.start()` bg thread | `SocksVpnService.kt:705–717` | *Duplicates* the same compute-conf-write-spawn sequence at connect time (with a username-guessed country, later corrected by reconcile). |
| `NetShieldScreen.kt` | `app/src/main/java/net/typeblog/socks/ui/screens/NetShieldScreen.kt` | Pure prefs write-only surface: NetShield toggle (`PREF_NETSHIELD_ENABLED`) + "Block adult content" (`PREF_NETSHIELD_BLOCK_ADULT`). **There is no per-country UI today** — the "per-country" dimension is entirely on the resolve side (the reconcile run at `applyIpInfo`). |

Structural smells:

1. **Policy logic and effects are fused** — `netshieldPolicy` reads prefs (state) *and* resolves (logic); `makePdnsdConf` writes files *and* is called from two duplicated sites.
2. **Change-guard is in the wrong place** — it lives as a `mNetshieldPolicy` field comparison in the *service*, comparing a policy *result*. Only the reconfigurator knows what's actually live on disk.
3. **No failure/rollback** — if reconcile's `launchPdnsd` returns false (line 900), the old pdnsd is already destroyed and the app silently runs DNS-less.
4. **Rule data is hardcoded** in the function body: `NETSHIELD_UNSUPPORTED_COUNTRIES` (Utility.kt:73) + two address constants (62–68). No seam, no independent replaceability.
5. **The conf template lives in `res/values/pdnsd.xml`** — only reachable through Android resources, so conf rendering is un-JVM-testable.

### Dependency classification (DEEPENING.md)

- Policy resolution: **in-process, pure** — deepenable freely; no adapter needed at the module's external seam beyond what tests require.
- Conf rendering: **in-process, pure** (string substitution) — but today *accidentally* coupled to Android via the resource. Fix by injecting the template string.
- Process lifecycle, file I/O: **local-substitutable** — fake `Process`/temp-dir stand-ins exist for tests.

---

## 1. The design

Model NetShield as two deep modules behind one interface each, joined by a 3-line wiring in the service:

- **(1) `NetshieldPolicy`** — a PURE module. One entry point:
  `resolve(exitCountry: String?, prefs: PolicyPrefs): UpstreamChoice`.
  Zero side effects, zero Android imports, zero prefs plumbing. All country rules are *data* (an injected rule table). Deterministic, total, idempotent.
- **(2) `DnsReconfigurator`** — the ONLY module allowed to touch pdnsd. One entry point +
  one shutdown: `reconfigure(config: DnsConfig): Result<Unit>`. Owns conf write (atomic), cache
  touch, process kill/start, **change-guard**, failure/rollback. Receives choices; never computes them.
  Two adapters (real pdnsd / test fake) → real seam. One internal seam (ProcessRunner) for its own tests.

Radical departures from status quo:

- The service stops *computing* and stops *touching pdnsd* — it only threads values through the two interfaces.
- `reconcileNetshield()` becomes: `reconfigurator.reconfigure(DnsConfig(policy.resolve(mCountryCode, prefs), mDns, mDnsPort))` — 1 line.
- The change-guard moves out of the service into the reconfigurator where it belongs.
- Failure handling and rollback become part of the interface contract, not a `Log.e`.
- Rule behavior becomes JVM-golden-testable; process behavior becomes fake-process-testable.

---

## 2. Module 1 — `NetshieldPolicy` (pure)

### 2.1 Interface (complete)

```kotlin
// package: net.typeblog.socks.nshield   — NO android.* imports anywhere.
// Compiles and runs on plain JVM.

interface NetshieldPolicy {
    fun resolve(exitCountry: String?, prefs: PolicyPrefs): UpstreamChoice
}
```

```kotlin
data class PolicyPrefs(
    val enabled: Boolean = false,
    val blockAdult: Boolean = false,
)

enum class DnsMode { PLAIN, ADBLOCK, ADULT_FILTER }

/** Null upstream == plain DNS through the user's own resolver. */
data class UpstreamChoice(
    val mode: DnsMode,
    val upstream: InetAddress?,   // java.net.InetAddress — JVM, not Android
)
```

Caller-side config of the module (constructor-injected, one concrete production adapter):

```kotlin
data class UpstreamDef(val label: String, val address: InetAddress)   // e.g. AdGuard / CleanBrowsing
data class RuleSet(
    val unsupportedCountries: Set<String>,  // geo carve-outs where cloud filtering is unavailable
    val adblockDefault: UpstreamDef,        // tier-1 (ads/malware/trackers)
    val adultFilter: UpstreamDef,           // tier-2 (adult blocking; historically CleanBrowsing)
)
```

Production adapter:

```kotlin
class RuleBasedNetshieldPolicy(private val rules: RuleSet) : NetshieldPolicy
```

### 2.2 Behavior spec (`resolve`)

In order, with short-circuit:

1. `!prefs.enabled` → `UpstreamChoice(PLAIN, null)` — disabled overrides everything.
2. `exitCountry` normalized: `trim().uppercase(Locale.ROOT)` (or `null`).
3. `normalized in rules.unsupportedCountries` → `UpstreamChoice(PLAIN, null)` — carve-out
   overrides tier. Note: **unknown/null country is treated as supported** (matches today:
   custom profiles without a zone get AdGuard).
4. `prefs.blockAdult` → `ADULT_FILTER` with `rules.adultFilter.address`, else `ADBLOCK` with
   `rules.adblockDefault.address`.

### 2.3 Interface facts a caller must know

- **Invariants**
  - Total: defined for *every* input, including `exitCountry = null`, garbage strings, lowercase, both prefs false. Never throws. (Only `RuleSet` construction can throw — that is configuration, validated at build time.)
  - Pure & deterministic: same inputs → same output, forever. No I/O, no clock, no randomness, no mutable state.
  - Idempotent: `resolve(c, p) == resolve(c, p)` trivially; callable at any frequency.
  - Thread-safe: stateless (the `RuleSet` is immutable after construction).
  - Normalization: country comparison is case-insensitive; output never depends on `Locale` except `Locale.ROOT` being explicit.
  - Enforced at construction (fail fast, in `RuleSet.init`): all codes in `unsupportedCountries` are 2-letter uppercase; `adblockDefault` and `adultFilter` addresses parse as literals (`InetAddress.getByName` on a dotted-quad never throws); the two addresses differ.
- **Ordering**: none — the module holds no state and no caller ordering is required. Call it whenever a country or prefs *may* have changed; cheap.
- **Errors**: none by contract. The only gates are construction-time `require` failures, which are programming/config errors and surface at startup, not mid-tunnel.
- **Config**: exactly one `RuleSet` at construction. Anything that varies per deployment (country carve-outs, provider addresses, tier mapping) goes in the table — never in the implementation.
- **Perf**: O(1) — set membership + two field reads + one allocation per call. Safe to call per packet, per tick, per reconcile.

### 2.4 Rule table as replaceable data — the two-adapters test

Do the policy dimensions vary independently today? **Yes, and history proves it.**

- The **availability dimension** (CN/RU/IR carve-out) changed for geopolitical/legal reasons (GFW/SAN, RKN collateral bans) — no change to providers.
- The **provider/tier dimension** changed for business reasons — AdGuard Family's public policy dropped adult blocking (2021), which is exactly why the adult tier is CleanBrowsing, not AdGuard Family (see the comment at Utility.kt:64–68). No change to the carve-outs.

Those two facts share one function body today; any change in either dimension is an edit in the middle of unrelated logic, retested by hand, untestable on JVM. In the redesign the two dimensions are two independently replaceable data objects (`unsupportedCountries` vs `adblckDefault`/`adultFilter`), each swappable without touching the other.

Seam honesty: the policy module has **one production adapter** (`RuleBasedNetshieldPolicy`). The second adapter is the JVM test suite, which must construct its own tables to assert behavior — the injected-table constructor *is* the seam's second adapter. Per the one-vs-two rule: the seam exists because tests cross it with different data than production. If a second *production* policy family ever appears (e.g. per-profile NetShield settings → `ProfileScopedPolicy` delegating with per-profile overrides), it plugs in behind the same interface with zero engine changes — the designed-for case, not an accident.

---

## 3. Module 2 — `DnsReconfigurator` (effect-only)

### 3.1 Interface (complete)

```kotlin
// package: net.typeblog.socks.nshield  — interface is Android-free; the prod adapter is not.

interface DnsReconfigurator {
    /** Reconcile live DNS config. No-op when nothing changed. Never throws. */
    fun reconfigure(config: DnsConfig): Result<Unit>

    /** Kill the running pdnsd (if any) and release resources. Idempotent. */
    fun shutdown()
}

data class DnsConfig(
    val choice: UpstreamChoice,   // receives choices; never computes them
    val baseDns: String,          // user's plain resolver ("8.8.8.8" default)
    val basePort: Int,
)
```

Sealed error type (tests + callers can branch):

```kotlin
sealed class ReconfigureError(val rolledBack: Boolean, cause: Throwable?) {
    class ConfWriteFailed(cause: Throwable? = null) : ReconfigureError(false, cause)
    class KillFailed(cause: Throwable? = null) : ReconfigureError(rolledBack = cause != null, cause)
    class ProcessStartFailed(cause: Throwable? = null) : ReconfigureError(rolledBack = false, cause)
}
```

Production adapter (constructor config):

```kotlin
class PdnsdProcessReconfigurator(
    private val dir: String,          // filesDir
    private val libDir: String,       // applicationInfo.nativeLibraryDir
    private val template: String,     // pdnsd.conf format string, INJECTED (see §3.4)
) : DnsReconfigurator                  // internally uses a ProcessRunner (internal seam, §6)
```

### 3.2 Behavior spec (`reconfigure`)

1. **Change-guard (invariant answer — this is where it lives):** if `config == lastAppliedConfig` → return `Ok` immediately, **zero side effects** (no write, no kill, no spawn). `DnsConfig` is a data class; equality covers choice upstream/mode *and* base DNS/port (guarding on what was actually applied, not on policy output — strictly safer than today's upstream-only comparison).
2. Render `pdnsd.conf` text from `template` + config: base DNS when `choice.upstream == null`, else upstream literal (via `hostAddress`).
3. **Atomic write:** write to a temp file, rename over `pdnsd.conf` (today's delete-then-write can leave a truncated conf on crash mid-write). Touch `pdnsd.cache` if absent.
4. Kill the current pdnsd process (pid-file fallback if the handle is stale — pdnsd daemonizes).
5. Spawn `libpdnsd.so -c <conf>` via the internal `ProcessRunner`.
6. `lastAppliedConfig = config` **only on full success**.
7. **Rollback:** if steps 4–5 fail after the conf was rewritten → restore the *previous* conf text (snapshot taken at step 3), restart the previous process, return `Err` with `rolledBack` set. The invariant: *after any return, exactly one pdnsd may be running (or none after shutdown), and the conf on disk matches the running process* — the current code violates this freely (service destroys pdnsd at line 892, then may fail to relaunch at 900 → live-but-broken state).

### 3.3 Interface facts a caller must know

- **Invariants**
  - Idempotent + convergent: N consecutive calls with the same config cost O(1) each after the first.
  - At most one pdnsd process running between calls; `shutdown()` leaves zero.
  - Never throws; every outcome is an explicit `Result` / `Unit`.
  - Success implies: conf on disk == config, daemon launched. Failure implies: either nothing changed (`ConfWriteFailed`) or previous-good state restored (`rolledBack=true`), or the process could not be restored (`rolledBack=false` — callers should treat `rollback-failed` as fatal-ish: consider tearing the tunnel).
- **Ordering**
  - Callers decide when policy *may* have changed and call `reconfigure`; the module decides whether anything actually needs to happen.
  - `shutdown()` must be called before filesDir teardown (stop path). Reconfigure-after-shutdown is legal and forces a fresh apply (`lastAppliedConfig` is cleared on shutdown).
  - Threading: callers may call from any thread — the module serializes internally (single lock). The service calls `start()` from a background thread and reconcile from the main thread; the serialize-inside contract removes today's benign race where a prefs toggle can interleave a boot-time config write.
- **Errors**: see sealed type above. The module logs once per failure internally (locality) and reports structured outcome upward.
- **Config**: dir/libDir/template injected at construction; the module reads no prefs, no country, no policy — *choices come in, effects go out*. The template is injected so the impl and its tests share the code path but not the Android resource (see below).
- **Perf**: steady-state (country unchanged, prefs unchanged) is a single equality check. Reconcile storms are impossible by construction. Reactivation cost = one ~1KB file write + one process spawn.

### 3.4 Where does the template come from?

The template must NOT be read from Android resources inside the module. Keep `R.string.pdnsd_conf` in `res/values/pdnsd.xml` (no file moves), and have the *wiring site* (the service, once) pass `getString(R.string.pdnsd_conf)` into the constructor. Config is injected at the seam; the module stays JVM-testable with its own template. (Optional later: move the template to a `raw/` asset or Kotlin constant; not required for this design.)

---

## 4. Seam placement

```
┌─────────────────────────────────────────────────────────────────┐
│ SocksVpnService  (wiring only — no policy logic, no pdnsd ops)  │
│                                                                 │
│   start()          bg thread:  resolve(guess, prefs) → reconfigure
│   applyIpInfo      main:       reconcileNetshield() = 1 line    │
│   prefs listener   main:       → reconcileNetshield()           │
│   stopMe()         →           reconfigurator.shutdown()        │
└───────────────┬───────────────────────────────┬─────────────────┘
                │                               │
        ┌───────▼────────┐             ┌────────▼──────────────────┐
        │ NetshieldPolicy │  seam      │      DnsReconfigurator    │
        │  (pure, 1 meth) │  →         │  (effects, 2 methods)     │
        └───────┬────────┘             └────────┬──────────────────┘
                │                               │ internal seam (own tests)
        ┌───────▼────────┐             ┌────────▼──────────────────┐
        │ RuleSet data   │             │ ProcessRunner (real/fake) │
        │ (injected)     │             └───────────────────────────┘
        └────────────────┘
```

**Seam 1 (policy)** sits *between* the service and the rule data — at the `resolve` call. Production adapter: `RuleBasedNetshieldPolicy`. Test adapters: JVM constructions with arbitrary `RuleSet`s (§2.4). Because the module is pure, the seam is where logic is *exercised*, not where a dependency is faked — this is the "interface is the test surface" case in its purest form.

**Seam 2 (reconfigurator)** sits *between* the service and pdnsd — at the `reconfigure` call. Production adapter: `PdnsdProcessReconfigurator` (writes files, spawns the real binary). Test adapter: a fake that records `(config, callCount)` and injects failures — behavior varies across it, so by the two-adapters rule this is a **real** seam.

**Internal seam (ProcessRunner)** is private to the reconfigurator's implementation: real adapter spawns/kills Processes, fake adapter replays scripted outcomes. Used only by the module's own tests (explicitly allowed — internal seams must not leak into the interface).

---

## 5. Wiring — what the service becomes

```kotlin
// start()  — background thread, initial bring-up:
val choice = netshieldPolicy.resolve(
    ProxyProviders.parseCountry(user ?: "", ProxyProviders.detectType(server ?: "", user ?: "")),
    PolicyPrefs(prefs.getBoolean(PREF_NETSHIELD_ENABLED, false),
                prefs.getBoolean(PREF_NETSHIELD_BLOCK_ADULT, false)),
)
if (!netshieldReconfigurator.reconfigure(DnsConfig(choice, dns ?: "8.8.8.8", dnsPort)).isSuccess)
    runOnMainThread { stopMe("pdnsd_initial_failed") }

// reconcileNetshield()  — the old 24-line body becomes 3:
private fun reconcileNetshield() {
    if (!mRunning) return
    netshieldReconfigurator.reconfigure(
        DnsConfig(netshieldPolicy.resolve(mCountryCode,
                   PolicyPrefs(prefs.getBoolean(PREF_NETSHIELD_ENABLED, false),
                               prefs.getBoolean(PREF_NETSHIELD_BLOCK_ADULT, false))),
                  mDns ?: "8.8.8.8", mDnsPort))
}

// stopMe(): netshieldReconfigurator.shutdown()   // replaces mPdnsdProcess teardown + pdnsd pid kill
```

Deleted from the service: `mNetshieldPolicy` field, `mPdnsdProcess` field, `launchPdnsd()`, `consumeProcessOutput()` (moves into the reconfigurator), the whole `reconcileNetshield` body, and the duplicated conf+spawn block at lines 702–717 (becomes the resolve+reconfigure pair above). The prefs listener and `applyIpInfo` trigger lines stay as-is — the "per-country live reapply" event flow is unchanged; only the mechanics are.

---

## 6. Depth analysis

**Before:**
- `Utility.netshieldPolicy` — shallow-behavior/wide-coupling: logic (rules) + state access (prefs) + derivation (username→country) + dead data (`NetshieldPolicy` = raw upstream string). Its knowledge is spread across three files to be used correctly.
- `Utility.makePdnsdConf` — pass-through: `String.format` + `File` I/O at the call site of whoever needs it; earns nothing (deletion test: delete it and the 4 conf sites each re-copy 8 lines).
- `reconcileNetshield` — duplicated logic with `start()` (conf write + spawn appear twice, drifting in behavior: start uses guess-country, reconcile uses real; only reconcile has a change-guard).

**After:**
- `NetshieldPolicy`: **deep** per interface — the entire rule semantics (carve-outs, tier selection, normalization, defaulting, locality of the "when is NetShield simply unavailable" knowledge) behind one 1-arg-ish method. Callers learn: `resolve(c, prefs) → choice`. Tests exercise: every rule path through that same call.
- `DnsReconfigurator`: **deepest module in the cluster** — rendering, atomic write, change-guard, kill/start, rollback, process-handle hygiene behind 2 methods. Callers learn: "call reconfigure with what you want; call shutdown when done". Its behavior (the bug-prone part: silent DNS-outage on failed reconcile) is implemented exactly once, tested exactly once, fixed exactly once.

**Leverage**: one `resolve` pays back 2 call sites (start, reconcile) and N golden tests; one `reconfigure` pays back 3 call sites (start, reconcile, stop) and the whole failure-mode test matrix. Adding a provider = one table row + one golden test (no engine edits). Adding a carve-out country = one set entry.

**Locality**: all pdnsd knowledge (template slots, pid file, daemonization quirk, cache file) concentrates in `PdnsdProcessReconfigurator`; all country/provider politics concentrate in the `RuleSet` data + its tests. The service is left with the only thing it uniquely knows: *when* things may have changed.

---

## 7. Deletion test

- **Delete `NetshieldPolicy`:** the carve-out rule and tier→provider mapping re-appear across the service's start path, reconcile, listener wiring, *and* inside the reconfigurator (if it had to re-derive upstream from raw prefs — exactly the coupling this design forbids). The rule logic is real behavior that would fan out across ≥3 callers → **earns its keep**.
- **Delete `DnsReconfigurator`:** conf writing, atomicity, cache touch, process kill/start, change-guard, and rollback re-appear at 4 sites (start, reconcile×2 triggers, stop) with independently drifting copies — precisely today's mess, *plus* the rollback that today doesn't exist anywhere → **earns its keep**.
- **Delete the wiring lines in the service:** the service becomes a pure tunnel spooler with no DNS knowledge — the cluster's remaining complexity truly disappears from it.

---

## 8. Testability

### 8.1 Policy — JVM golden tests (no Robolectric, no Android emulator)

```kotlin
class RuleBasedNetshieldPolicyTest {
    val rules = RuleSet(
        unsupportedCountries = setOf("CN", "RU", "IR"),
        adblockDefault = UpstreamDef("adguard", InetAddress.getByName("94.140.14.14")),
        adultFilter   = UpstreamDef("cleanbrowsing", InetAddress.getByName("185.228.168.168")),
    )

    @Test fun `disabled - plain regardless of country`() { ... }
    @Test fun `enabled unknown country - adblock`()          { /* resolve(null,..) == ADBLOCK */ }
    @Test fun `enabled DE adult - adult filter`()            { }
    @Test fun `enabled CN - plain even when adult`()         { /* carve-out beats tier */ }
    @Test fun `lowercase cn normalizes`()                    { }
    @Test fun `garbage country treated as supported`()       { }
    @Test fun `degraded config without adult - still adblock`() { }
}
```

Golden values are the current constants; these tests freeze today's behavior *before* the refactor so the migration can't silently change policy (write them first against `Utility.netshieldPolicy` outputs, or run both and diff).

### 8.2 Reconfigurator — fake process + temp dir

```kotlin
class PdnsdProcessReconfiguratorTest {
    // fake ProcessRunner records start(kill) calls; temp dir as filesDir; plain template
    @Test fun `same config - no side effects`()      { reconfigure(a); reconfigure(a); assertNoWrites }
    @Test fun `changed upstream - write + kill + start`() { }
    @Test fun `start failure - rolls back conf and restarts previous`() { ... rolledBack=true }
    @Test fun `conf write failure - nothing touched`() { ... ConfWriteFailed }
    @Test fun `shutdown kills and clears state`()     { }
    @Test fun `reconfigure after shutdown re-applies`() { }
}
```

All assertions cross the public `reconfigure` seam — no reflection, no internal state access (tests survive internal refactors per "replace, don't layer").

---

## 9. Migration sketch (for the implementer session — not executed here)

1. **Snapshot** (AGENTS.md rule): `git tag -a pre-netshield-refactor -m "Before NetShield policy/reconfigurator split"` + push. This touches engine files (`Utility.kt`, `SocksVpnService.kt`) — allowed because it is an engine refactor, not a UI change; flag in the commit.
2. **Add pure module** `net.typeblog.socks.nshield` (`NetshieldPolicy.kt`, `RuleSet.kt`, `UpstreamChoice.kt`, `RuleBasedNetshieldPolicy.kt`) + golden tests. Zero Android imports — compiles as a JVM unit-test target in the existing app module.
3. **Add reconfigurator** (`DnsReconfigurator.kt`, `PdnsdProcessReconfigurator.kt`, internal `ProcessRunner`) with the template injected from the service (`getString(R.string.pdnsd_conf)`); move the pdnsd launch/consume/kill logic in; add fake-runner tests.
4. **Rewire the service** per §5; delete `Utility.netshieldPolicy`, `Utility.makePdnsdConf`, `Utility.NetshieldPolicy`, and the service's `launchPdnsd`/`mPdnsdProcess`/`mNetshieldPolicy`/reconcile body.
5. **Build via CI** (`.github/workflows/build.yml` — never local), install `app-arm64-v8a-release.apk` over the existing app; verify live toggles + CN-simulated reconcile on device.
6. Update AGENTS.md filesystem map (new files, removed Utility methods) in the same commit.

---

## 10. Honest drawbacks

1. **Two interfaces for ~120 lines of behavior is real weight.** The cluster is small; a single `NetshieldReconfigurator` module would also "work". The split is justified because the two modules have *different failure-speed*: policy is pure math (testable to exhaustion on JVM), effects are device-bound (only fake-testable). Fusing them would drag the JVM-testable core back to Robolectric — the one regression this design exists to prevent.
2. **The username→country guess stays in the wiring line**, not in the policy module (kept out to preserve the 1-parameter interface). `ProxyProviders.parseCountry` is already pure, so this is honest plumbing — but it means "where does my country come from" is answered in two places (wiring guess + `resolve`'s real-country param).
3. **`Result<Unit>` creates a decision the old code didn't have.** Today failures are `Log.e` and life goes on. In the new world the service must decide what a `rolledBack=false` failure means (recommendation: treat as fatal → `stopMe`, since DNS is down and possibly unrecoverable). That's *better*, but it is new caller responsibility.
4. **pdnsd daemonizes** (`daemon=on` in the template) — the launched `Process` exits after forking, so the reconfigurator can't fully verify the new daemon serves DNS. This design concentrates the problem (one module to harden later with pid-file liveness checks) but does not perfect it. Rollback may also need the pid-file path, since the process handle can be stale — the adapter must handle both (documented in §3.2, not hidden).
5. **The template remains an Android resource** injected at the seam. Purity is "injected configuration", not "no Android at all" — a template typo is only caught by rendering tests, and syntactic validity is only provable with the real binary on-device (the old code had the same blind spot; not a regression).
6. **Engine refactor risk**: `SocksVpnService` is the system-critical startup path; the rewrite of lines 700–720 / 879–902 is the riskiest edit in the repo. Mitigated by the snapshot tag, running both conf-generation paths in parallel during testing, and CI + device verification before declaring done.

---

## 11. One-line verdict

Pure resolve-side policy + effect-only reconfigure-side reconfigurator, joined by a 1-line reconcile, moves the cluster's brains into two deep, independently testable modules and its data into replaceable tables — at the honest cost of two new interfaces for a small cluster and new caller responsibility around failure outcomes.