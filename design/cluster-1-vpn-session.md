# Cluster 1 — VPN Session Lifecycle: the `Session` Stage-Machine Module

Design agent output. NO repo modifications — design doc only.

## 1. Problem frame

`SocksVpnService.kt` (964 lines) is the engine head. Reading it fully shows the status quo is **one implicit state machine spread across 15 mutable fields** (`mRunning`, `mTunnelUp`, `mProxyVerified`, `mIpCheckFailures`, `mPendingIpInfo`, `mNetshieldPolicy`, `mResolvedServer`, …) and **four concurrent owners of the lifecycle**:

- the `onStartCommand` → `configure` → `start` spawn thread (pdnsd/conf, hostname resolve, tun2socks, sendfd poll),
- `mIpCheckRunnable` (parallel ip-api race + probe fallback + 3-strike policy + doze gating + buffering),
- `mStatsRunnable` (TrafficStats sampling + usage persistence cadence),
- `stopMe` + `postStartOnMain` + `reconcileNetshield` + screen on/off receivers.

Transitions between states are **implicit** (a `mTunnelUp`-only-valid-when-`mRunning` convention checked at scattered call sites), failure policy lives *inside* the runnable's branches, and the whole thing is un-testable: it is welded to `VpnService`, `Handler(Looper.getMainLooper())`, `ProcessBuilder`, JNI `sendfd`, and sockets. There are zero tests in the repo (`app/src/test` does not exist).

**Design constraint given:** model the session as a linear pipeline of named stages with a compile-time stage matrix; every background effect (process spawn, race, poll) is a declared effect with a timeout, presented in a `StageDeclaration` table (stage, precondition, effect fn, timeout, failure action). The service becomes a thin host owning only Android bindings (VpnService API, notification, AIDL, receivers).

Radically different from both (a) the status quo and (b) a plain class extraction, which would merely relocate the same mutable fields and Handlers into another object while keeping transitions implicit and the failure paths un-enumerable.

## 2. Module shape

```
app/src/main/java/net/typeblog/socks/   (all in the :vpn process)
├── SocksVpnService.kt        → stays, shrinks to THIN HOST (~360 lines)
├── session/
│   ├── Session.kt            Module entry: Session (start/stats) + SessionHandle
│   ├── SessionConfig.kt      Data classes: Config, Stats, FailReason
│   ├── Stage.kt              enum Stage (IDLE, SPAWN, UP, READY, HEALTHY, FAILED, STOPPED)
│   ├── Effect.kt             sealed EffectOutcome hierarchy (compiler-enforced totality)
│   ├── StageMatrix.kt        THE TABLE — declare-everything, no hidden branches
│   ├── StageMachine.kt       Generic engine: precondition check → effect → timeout → lookup
│   ├── SessionEffects.kt     Port: ONE seam to the outside world (process/network/env/storage)
│   └── SessionObserver.kt    Reverse channel port (host notification projector)
└── session/adapters/
    ├── RealSessionEffects.kt Production adapter (I/O mechanics, NO policy)
    └── RealScheduler.kt / RealClock / RealLog   (tiny, internal-seam adapters)
```

The module is a **single deep object** (a `Session` instance), constructed once by the host. It is pure Kotlin — zero `android.*` imports in `session/` — because everything Android-specific sits behind ports (see §5). This is what makes it JVM-testable.

### 2.1 The StageDeclaration table (the heart)

Each row declares one stage: legal predecessors, the effect to run, its timeout, and what each `EffectOutcome` maps to. Recurring effects (health checks, stats) are declared with a *stage range*, an interval function, and a per-invocation timeout.

| Stage | Preconditions | Effect | Timeout | Success → | Retryable failure → | Terminal failure → |
|---|---|---|---|---|---|---|
| `SPAWN` | `IDLE` | `WritePdnsdConf` + `SpawnPdnsd` + `ResolveHost` (sequenced inside one effect call) | 2s + 2s + 10s | `UP` | — | `FAILED(pdnsd_start_failed)` / `FAILED(resolve_failed)` |
| `UP` | `SPAWN` | `SpawnTun2socks` then `SendFd` (adapter implements the 100×50ms poll *mechanics*; module owns the 5s deadline) | 5s each | `READY`* | — | `FAILED(tun2socks_start_failed)` / `FAILED(sendfd_failed)` |
| `READY` | `UP` | — (recurring effects declared below begin) | — | `HEALTHY` (on first verified check) | `READY` (transient) | `FAILED(proxy_*_classified)` at 3 strikes |
| `HEALTHY` | `READY` | recurring `CheckHealth` | 30s per invocation | `HEALTHY` | `READY` (transient) | `FAILED(proxy_*_classified)` at 3 strikes |
| recurring | `SPAWN..HEALTHY` | `CheckHealth` (single overlapped effect — ip-api race runs parallel to spawn; results buffered while `SPAWN`) | 30s (5s after strike, 500ms when ip-api down but proxy OK, 60s in doze) | apply ip info / reset strikes | `Skipped(doze)` → no strike, next poll | strike++, 3 → `FAILED` |
| recurring | `UP..HEALTHY` | `StatsPoll` + `PersistUsage` (every 5 ticks) | 1s | — | — | (non-fatal) |
| any active | any | `TeardownAll` (kill processes, close fd, remove pid files) | 2s | `STOPPED` | — | `STOPPED` (best-effort) |

\* `UP → READY`: `mProxyVerified` today is set on first probe-OK or first applied `IpInfo`. In the matrix, `READY` = "first confirmation received"; `HEALTHY` = READY that has survived ≥1 consecutive verified check, and a transient failure degrades `HEALTHY → READY`. The `READY ⇄ HEALTHY` edge is the *only* backwards edge — the invariant-testable rule.

The interesting coupling (strikes + buffering + doze) becomes **cells in the table**, not branches in a runnable:

- `CheckHealth → IpInfo` during `SPAWN` ⇒ buffered; surfaced on `UP` (observable only after `UP` — invariant, matches today's `mPendingIpInfo`).
- `CheckHealth → ProxyDead` ⇒ strike++. Only while `state ∈ {UP, READY, HEALTHY}`. Strike 3 ⇒ `FAILED` with classified message (AUTH_FAILED / NOT_SOCKS5 / CONNECT_FAILED / UNREACHABLE → user text lives in the module).
- `CheckHealth → Skipped(doze)` ⇒ no strike, reschedule at doze cadence. (Doze detection itself is adapter mechanics: `environment.isNetworkCheckBlocked()`.)
- `CheckHealth → IpInfo` (success) ⇒ post-hook declared effect `ReconcileDns` (NetShield drift-correction: recompute upstream from real country; restart pdnsd if changed — idempotent, so the SharedPreferences toggle-listener in the host is **deleted entirely**; drift is detected on the next IpInfo success, ≤30s).

**"Compile-time" totality:** the matrix is not reflection or codegen. `EffectOutcome` is a *sealed* hierarchy and the stage dispatcher is one exhaustive `when(state, outcome)` — the Kotlin compiler *rejects* any row that forgets an outcome. Property tests (§8) additionally verify the legal-transition table over all state pairs.

## 3. COMPLETE Interface spec

### 3.1 `Session` (module entry)

```kotlin
class Session private constructor(
    private val effects: SessionEffects,     // port — required
    private val observer: SessionObserver,   // port — required
    private val scheduler: Scheduler,        // internal seam — defaults to real
    private val clock: Clock,                // internal seam — defaults to real
    private val log: SessionLog,             // internal seam — defaults to real
) {
    companion object {
        fun create(effects: SessionEffects, observer: SessionObserver): Session
    }

    /** Starts a session. Idempotent: while a session is active, returns the same handle. */
    fun start(config: SessionConfig): SessionHandle

    /** Non-blocking O(1) snapshot of the live session (all-zero when none). */
    fun stats(): SessionStats
}
```

**Invariants:**
- At most one active session per process; `start()` while active returns the *same* handle (the host's `if (mRunning) return 0` guard moves into the module — it can no longer be gotten wrong by the caller).
- `start()` returns synchronously, before any effect runs. No callback from `start()` itself; everything arrives via `SessionObserver`.
- `stats()` never blocks, never allocates in the hot path beyond the snapshot copy, and is safe from any thread (`@Volatile` fields, exactly like today's counters).

**Ordering:** observer callbacks for a session are delivered strictly in stage order, on the module's single stage-runner thread (the host re-marshals to its own threads; the module never touches a Looper).

**Errors:** `handle.fail(reason, detail)` is idempotent, thread-safe, and wins over in-flight effects; after the first call the handle is invalidated and further calls are no-ops. All lifecycle errors surface as `state == FAILED` + `SessionStats.error` (user-displayable text) + one final `onSessionFailed` observer event — the host then performs its Android teardown (`stopForeground(DETACH)`, `stopSelf()`).

**Config (only what the engine needs — routing/per-app/route stay host-side in the VpnService.Builder):**

```kotlin
data class SessionConfig(
    val profileKey: String,              // usage persistence key (sanitized name)
    val server: String, val port: Int,
    val username: String?, val password: String?,
    val dns: String, val dnsPort: Int,
    val ipv6: Boolean, val udpgw: String?,
    val netshieldEnabled: Boolean, val netshieldBlockAdult: Boolean,
    val libDir: String, val filesDir: String,
)
```

### 3.2 `SessionHandle`

```kotlin
interface SessionHandle {
    val state: State                        // snapshot read
    /** Host-driven teardown: binder stop(), notification stop, screen-off
     *  auto-stop, onRevoke, onDestroy. Same semantics as stopMe(). */
    fun fail(reason: FailReason, detail: String? = null)
}
```

That is the complete host→module channel, exactly per brief: `start(config)` / `handle.fail(reason)` / `stats()`.

**Deliberately out of v1** (one reserved growth point, *not* part of the interface): `handle.recheck()` for instant screen-on/prefs re-verification. v1 instead relies on the declared doze cadence (60s reschedule) and NetShield drift-correction. Honest consequence: worst-case 60s re-verify latency after screen-on and ≤30s NetShield toggle latency vs. instant today — see §10, and the remedy is one method if the user rejects the latency.

### 3.3 `SessionStats` (projection of the AIDL binder)

```kotlin
data class SessionStats(
    val state: State,
    val error: String?,                     // user-display text ('' when none)
    val proxyVerified: Boolean, val ipCheckFailures: Int,
    val currentIp: String?, val ipInfo: IpInfo?,
    val connectedSince: Long,               // set at UP, not at first verification
    val receivedBytes: Long, val sentBytes: Long,
    val cumulativeRx: Long, val cumulativeTx: Long,
)
```

Every one of the 19 `IVpnService.Stub` getters becomes a one-line projection of `stats()` — the AIDL interface stops being a second *driver* of session state and becomes a pure read model.

### 3.4 `SessionEffects` (the ONE port — process/network/environment/storage)

```kotlin
interface SessionEffects {
    // process mechanics
    fun writePdnsdConf(cfg: PdnsdConfInput): Boolean
    fun spawnPdnsd(cfg: PdnsdLaunch): ProcessRef?             // non-blocking; daemonized
    fun resolveHost(host: String): String?                    // blocking, module-enforced 10s timeout
    fun spawnTun2socks(cmd: Tun2socksCommand): ProcessRef?    // engine-only command assembly stays HERE
    fun sendFd(fd: Int): Boolean                              // adapter loops the poll; module owns the deadline
    fun killAll(refs: Set<ProcessRef>, pidFiles: List<String>)// TeardownAll
    // network mechanics
    fun checkHealth(req: HealthRequest): HealthCheckOutcome   // 4-provider race INSIDE adapter (10s latch)
    fun reconcileDns(newUpstream: String?): Unit              // rewrite conf + restart pdnsd if changed
    // environment
    fun isNetworkCheckBlocked(): Boolean                      // Doze / screen-off (PowerManager, adapter-side)
    // storage
    fun readUsage(key: String): Pair<Long, Long>
    fun persistUsage(key: String, rx: Long, tx: Long)
}
```

**Ownership split (the crucial one):** the adapter owns *I/O mechanics* (poll cadence, provider race, daemonization, TrafficStats/pid handling); the module owns *policy* (strikes, buffering, doze gating decisions, cadences, teardown ordering). Today both are fused inside `mIpCheckRunnable`/`start()`.

### 3.5 `SessionObserver` (module → host reverse channel)

```kotlin
interface SessionObserver {
    fun onStateChanged(state: State, from: State)
    fun onIpInfo(info: IpInfo)                 // → host updates notification "Connected…"
    fun onHealthVerified(verified: Boolean)    // → proxy pill / READY badge
    fun onSessionFailed(error: String)         // → host: notification text + stopForeground + stopSelf
    fun onSessionStopped()                     // → host: final cleanup
}
```

### 3.6 Internal seams (private to the module, used by its own tests — never exposed)

```kotlin
interface Scheduler { fun schedule(delayMs: Long, task: () -> Unit): Token; fun cancel(t: Token) }
interface Clock { fun now(): Long }                      // elapsedRealtime semantics
interface SessionLog { fun d(tag: String, msg: String); fun e(tag: String, msg: String, t: Throwable? = null) }
```

## 4. Seam placement — the `:vpn` process constraint

`SocksVpnService` runs in its **own process** (`android:process=":vpn"`). The seam therefore has hard constraints that shape everything:

1. The tun fd is created by `VpnService.Builder.establish()` and lives in the `:vpn` process; the `sendfd` JNI passes it to tun2socks in the same process. **The Session module MUST live inside `:vpn`** — it cannot sit in the UI process, and it would be nonsense to shuttle the fd across AIDL only to hand it back. The module is compiled into the `:vpn` process; the UI process never sees it.
2. The module therefore must not assume a main Looper, must not touch `Handler`, and must own its execution (single stage-runner thread + scheduler). Today the service's `Handler(mainLooper)` postings are `:vpn`-process-local, which is exactly the coupling being cut.
3. **AIDL is perpendicular to the Session seam, not part of it.** The binder interface stays a thin projection (`stats()` + `fail()`), unchanged from the UI process's perspective. Do NOT design a port on the module for AIDL — that's a hypothetical adapter (see §5).
4. A session is 1:1 with the process lifetime and with the single foreground service. The "one active session, `start()` idempotent" invariant is the module-level encoding of what the manifest already forces — the constraint becomes documented, not accidental.
5. `START_STICKY` restarts with a null intent stay host-side (`return 0`) — process-restart policy is Android binding, not session logic.

## 5. Adapters — real vs hypothetical (one-vs-two rule)

| Port | Production adapter(s) | Test adapter(s) | Verdict |
|---|---|---|---|
| `SessionEffects` | `RealSessionEffects` (`:vpn` process: ProcessBuilder, JNI sendfd, `Utility` I/O, PowerManager, SharedPreferences) | `ScriptedEffects` (per-call scripted outcomes, e.g. "sendfd fails on attempts 1–99") | **Real seam** — two honest adapters, test one is not a mock of anemic convenience, it is the entire reason the module exists |
| `SessionObserver` | `SocksVpnService` (host) | `RecordingObserver` | Real (host is a genuine second implementation) |
| `Scheduler` | `RealScheduler` (single-thread executor) | `ManualScheduler` (never runs; test calls `runNext()`) | Real — internal seam, test-side only |
| `Clock` | `RealClock` (elapsedRealtime) | `FakeClock` (test advances time) | Real — internal seam |
| `SessionLog` | `RealLog` (android.util.Log) | collecting fake | Real (small, but removes the last android dep) |

**Hypothetical seams explicitly rejected:**
- No `SessionAIDL` port ("bind the module to the UI process directly") — the AIDL transport already exists and works; the host projects it. One adapter would exist (the binder) → pure indirection.
- No `VpnRouteBuilder` port — `Builder`/`Routes` are Android bindings and stay host-side; routing config never enters the module.
- No multi-host posture ("could later run on Wear/automotive") — that is speculative value; justification is testability alone.
- No blocking-style `Handler`-loop extraction, no `StateMachine` library, no annotation/codegen processor — the exhaustive `when` is the compile-time guarantee, cheaper and reviewable.

## 6. Depth analysis

**Leverage per entry point (high):**
- `start(config)` — turns 15 mutable fields + a spawn thread + parallel race wiring + buffering into one call that returns in microseconds and yields a fully-specified machine.
- `handle.fail(reason)` — replaces `stopMe` + the three kill paths + receiver unregistration + counter resets + "stop while spawning" race with one idempotent transition that uses the same `TeardownAll` row no matter which stage it fires from.
- `stats()` — a single snapshot that supplies all 19 AIDL getters, the notification text decision ("Connecting…" vs connected), and the UI's full status surface.

**Locality (where change concentrates):**

| Change | Today (status quo) | With matrix |
|---|---|---|
| Retry policy (3→5 strikes) | edit 3 branches inside `mIpCheckRunnable` + constants | one cell in the table |
| New cadence / timeout | new constant + hand-set `postDelayed` sites | one row's timeout/interval column |
| New stage (e.g. pre-flight auth probe before spawn) | new field + new branch in start thread + stopMe must learn it | one table row + one `SessionEffects` method + one adapter method (the only spread: 4 touch-points, all visible in one commit) |
| Doze handling | `isNetworkCheckBlocked` inline + 2-handler juggling | outcome cell `Skipped(doze)` |
| NetShield toggle | prefs listener + `reconcileNetshield` + pdnsd kill/relaunch on main thread | drift-effect cell; listener deleted |

**What stays thin:** the service host (~360 final lines: `configure()` Builder calls, notification, receiver registration, AIDL projections — no policy, no state machine). **What stays shallow per-method:** `SessionEffects` (each method is one mechanical operation with no internal branching policy). The policy — the interesting part — lives in exactly one file: `StageMatrix.kt`.

This is the profile of a deep module: big net (lifecycle) collected by a small interface, with the complexity confined behind the seam, and the variation point (the table) being data, not control flow.

## 7. Deletion test

Delete the `Session` module. What must come back into the service: spawn sequencing and its failure taxonomy, sendfd poll deadline, parallel-race buffering (`mPendingIpInfo`), 3-strike policy, doze gating, stats sampling + 5-tick persistence, NetShield drift reconciliation, teardown ordering, error classification — ~600 lines of today's `SocksVpnService` re-created by hand. No other class absorbs it: `VpnViewModel` reads only AIDL; `FloatingControlService` only shows stats. The module is not decorative.

Reverse direction: delete the thin host's remaining glue (Builder call, notification, receivers) and the Session still has complete, spec-verified semantics — which is precisely why it can be tested in a JVM with no Android at all. The seam is at the boundary where "session" stops and "Android binding" starts, and today's code proves that boundary is real: the service is 964 lines of which ~350 are genuinely Android-bound.

## 8. Testability — the stage matrix without sockets or processes

New `app/src/test/java/net/typeblog/socks/session/` (**a test source set that does not exist in the repo today — must be added from zero**). Tests are plain JUnit 4, zero Android, zero threads, zero sockets:

| Test surface | Suite | What it proves |
|---|---|---|
| Matrix legality | `StageMatrixTest` — property test over all (state, outcome) pairs | every pair maps through the exhaustive `when`; every row's precondition set matches the legal-transition table; exactly one backwards edge (READY⇄HEALTHY); terminal states have no outgoing edges |
| Spawn path | `ScriptedEffects`, `ManualScheduler.runNext()` | SPAWN→UP→READY; sendfd succeeds on attempt N; pdnsd failure → `FAILED(pdnsd_start_failed)`; each effect invoked exactly once per stage |
| Timeout | `FakeClock.advance(...)` | blocked `spawnTun2socks` past 5s → `FAILED(tun2socks_start_failed)` without any process being created (fake asserts killAll called) |
| Strikes | scripted `ProxyDead` ×3 | strikes 1–2 → retry (state still active), strike 3 → `FAILED` with classified text; observer got exactly one `onSessionFailed` |
| Buffering | ip-api "success" while state `SPAWN` | `stats().ipInfo == null` until `UP`; exactly one `onIpInfo` after UP; counter-reset semantics identical to today |
| Doze | `ScriptedEffects.isNetworkCheckBlocked = true` | `Skipped(doze)` outcomes accumulate no strikes; cadence switches to 60s; block+unblock sequence preserves failure count like today's screen-on reset |
| Teardown | `fail()` from every stage (SPAWN, UP, READY, HEALTHY) | idempotent, first-wins, killAll called with exactly the set of live refs, further `fail()` no-ops |
| Stats | 1s polls via `ManualScheduler` + `FakeClock` | `PersistUsage` exactly every 5 ticks; `stats()` snapshot matches recorded values |
| NetShield drift | scripted `IpInfo` with changing country | `reconcileDns` called only when computed upstream differs; pdnsd restart count ≥0 and no restart when unchanged |

Fakes at each stage replace the *mechanics* (process, race, poll, prefs); the *policy* is exercised for real every time because policy lives in the module.

Per DEEPENING.md: the interface is the test surface — tests assert observable outcomes through `stats()`/`observer` events and survive internal table refactors (a cadence column change must not touch tests). No legacy tests exist to delete; the suites above are net-new behavioral tests.

Note for the GitHub-only build rule: `assembleRelease` does not run JVM unit tests — if gate-keeping CI is desired, the build workflow would need a small `test` step added. Optional; the tests still give *meaningful local verification the machine has never had*, but per AGENTS.md all builds/verification happen through CI.

## 9. Internal seams (recap)

`Scheduler`, `Clock`, `SessionLog` are constructor-injected defaults, private to the module, consumed exclusively by the module's own tests via fakes. They are **not** part of the module's public interface — callers construct `Session.create(effects, observer)` and never see them. This respects the DEEPENING.md rule: internal seams stay internal, the external seam (effects + observer + the three methods) is the only surface.

## 10. Migration sketch — rough line-change budget

Snapshot first, per AGENTS.md convention: tag `pre-session-refactor` and push before touching the engine head. (Engine file is off-limits to UI work; this is the one legitimate engine change.)

| Step | Files | Rough Δ |
|---|---|---|
| 1. Test scaffolding | `app/src/test/.../session/` (fakes: ScriptedEffects, ManualScheduler, FakeClock, RecordingObserver, helpers) | +250 |
| 2. Module core | `session/` (Session, Stage, Effect, StageMatrix, StageMachine, SessionEffects, SessionObserver, SessionConfig) | +580 |
| 3. Behavior tests | `StageMatrixTest`, `SpawnPathTest`, `StrikePolicyTest`, `BufferingTest`, `DozeTest`, `TeardownTest`, `StatsAndPersistTest`, `NetshieldDriftTest` | +400 |
| 4. Production adapters | `session/adapters/` (RealSessionEffects absorbing process/JNI/race/doze/usage mechanics; RealScheduler/Clock/Log) | +120 |
| 5. Host rewrite | `SocksVpnService.kt`: delete `start()`, `mIpCheckRunnable`, `mStatsRunnable`, `stopMe` internals, `postStartOnMain`, `reconcileNetshield`, netshield listener, runnable fields, ~10 state fields; keep `configure()`/Builder, notification, AIDL projections, receiver registration, `onStartCommand` wire-up | −600 / +40 (rewrite) |
| **Net** | | **≈ +790 / −600 → +190** (plus the +250 scaffold folded above; net including tests ≈ +400 with real coverage that did not exist) |

Sequence: (1)+(2)+(3) land first and compile *alongside* the old service (new package, no wiring) → CI stays green, service untouched; then (4)+(5) swap the wiring in one commit. The old lifecycle is deleted, not adapted — this is a redesign, not a relocation.

## 11. Honest drawbacks

1. **Wake/prefs latency regression.** Screen-on re-verify and NetShield toggles are drift/doze-cadence-based in v1: ≤60s re-verify and ≤30s toggle latency vs instant today. Mitigation is one reserved method (`handle.recheck()`) — deliberately excluded to keep the interface at the briefed three entry points.
2. **Blocking-effect timeouts cannot interrupt a hung call.** A deadlocked `resolveHost`/`sendfd` JNI can't be canceled from Java; the timeout fires `TeardownAll` which kills the blocking side (process destruction, fd close) and unblocks it in practice. A truly unkillable native hang would stall the stage-runner until process death — the killer processes are the same UID today, so this is an honesty note, not a new risk.
3. **More machinery than the happy path needs.** A straight-line spawn thread is ~40 lines; the table + machine is ~250. The premium buys exhaustive failure-path enumeration (where today's bug history actually lives: strikes/buffering/doze interactions), not linear-path simplicity.
4. **AIDL projection coupling.** `IVpnService` getters must track `SessionStats` fields; the binder is a projection of the module, so drift is mechanical, but it's still a second interface to keep honest. (Mitigation: `SessionStats` is deliberately shaped to be the projection.)
5. **Matrix touch-point spread for new effect kinds.** Adding a brand-new effect touches 4 locations: sealed outcome type, matrix row, port method, adapter+fake. Every *policy change* is one cell; every *new capability* costs 4 visible touch-points — the correct tax for total transition enumeration.
6. **Biggest-risk surgical change in the app.** The engine head is the most battle-tested code in the codebase; per AGENTS.md it is only rebuilt on CI, so iteration cost per fix is a full push/watch/download/install cycle. The two-phase migration (module first, wiring second) and the `pre-session-refactor` tag are the mitigations; a full local build on this machine is still forbidden.
7. **One session per process is now an enforced invariant** at the module boundary. Today the guard exists as `if (mRunning) return 0` in one spot; the matrix makes simultaneous sessions structurally impossible, which is stricter than today — desirable, but it *is* a behavioral contract tightening (e.g., RESTART-DELAY craft would need a new stage row rather than a re-`start()` hack).

## 12. Guard rails (what this design is NOT)

- NOT an AIDL/multiprocess redesign — AIDL stays untouched and perpendicular.
- NOT a framework: no state-machine library, annotation processor, or generated code; the exhaustive `when` + a data table is the whole trick, reviewable in one file.
- NOT a UI/engine interface widening: `VpnViewModel`, `IVpnService.aidl`, `FloatingControlService`, profile CRUD, pdnsd/routing configs are all untouched.
- NOT a behavior-preserving refactor in disguise: it is a redesign whose test suite defines session behavior for the first time.