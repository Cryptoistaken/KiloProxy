# Cluster 6 — `ExitMonitor`: exit-info as a stream of typed outcomes with backpressure and monotonic truth

Design doc only — no repo modifications. Vocabulary per `codebase-design` (DEEPENING.md / DESIGN-IT-TWICE.md): module, interface, seam, adapter, depth, leverage, locality, deletion test, one-vs-two adapters.

Radical-design brief, restated: NOT Alt A (blocking `fetch(): IpInfo?`), NOT Alt B (full TunnelSession coordinator), NOT Alt C/C′ (producer + latch / folded-latch producer). Instead: **the exit-info cluster is a stream**. The module owns a probe loop that emits *typed round outcomes* into a small event channel; buffering-until-tunnel-up is an internal conflating operator; retry/backoff is a declared *value*; the 3-strike policy is a fold whose placement is argued with the deletion test. The service's job shrinks to folding the stream into AIDL-visible state — a projection it must do anyway.

---

## 1. Module name + shape

**`net.typeblog.socks.exit.ExitMonitor`** — new package `app/src/main/java/net/typeblog/socks/exit/`, plain JVM (zero `android.*` imports in the module body).

```kotlin
class ExitMonitor private constructor(
    private val env: Environment,          // internal seam bundle, injected — see §4
) {
    fun start(exit: ExitSpec, policy: RetryPolicy)   // begin (or replace) a probe session
    fun markReady()                                  // tunnel-ready edge: release the hold
    fun stop()                                       // end session; flow completes normally
    val events: Flow<ExitEvent>                      // single-collector event channel
}
```

One paragraph: the module runs a **round loop** (one round = 4-provider SOCKS race + on-failure classification probe + schedule-next). Every round produces exactly one typed outcome: `ExitResolved`, `ExitUnresolved`, or `ExitStalled`. Outcomes flow through an internal `conflateUntil(Ready)` operator (pre-ready, only the *latest resolution* is held; unresolved rounds pre-ready are dropped — the service doesn't care) and then into a **conflated channel** (capacity 1, drop-oldest) that the service collects on the `:vpn` main looper. Retry math (500 ms quiet / 30 s healthy / 5 s strike / 60 s doze / 3-strike cap) is data — one `RetryPolicy` value. Truth is monotone: every resolution carries `resolvedAtMs` on the module's injected clock, and conflation only ever drops *older* truth, so a slow consumer can never observe a regressed state. The module knows nothing about tunnels or Android; "tunnel up" arrives as one `markReady()` command.

### Why this is genuinely different from alt-a/b/c

| | alt-a | alt-b | alt-c′ | **ExitMonitor** |
|---|---|---|---|---|
| Interface style | 1 blocking call | callback listener + phase machine + getters | callback listener + start/markReady/stop | **Kotlin Flow event channel + 3 commands** |
| Timing policy | none (service-owned) | constants in object | `ProbePolicy` per-start | **`RetryPolicy` value encoding both phases + doze** |
| Buffer-until-ready | service (`mPendingIpInfo`) | in session (attached to Ready) | in session (hold) | **a named internal operator: `conflateUntil(Ready)`** |
| Backpressure | irrelevant (blocking) | implicit (same thread dispatcher) | implicit | **explicit: conflated channel, O(1) pending, drops are provably safe** |
| 3-strike logic | service | session | owner (service) counts via FailedRound | **fold inside the module, justified by drop-safety (§6)** |
| Test seam | fake transport/HTTP server | per-adapter fakes | fake fetcher/classifier/scheduler | **virtual-time Flow tests (`runTest` + TestDispatcher)** |

---

## 2. Complete interface spec

### 2.1 Types

```kotlin
// exit/ExitSpec.kt
data class ExitSpec(
    val server: String?,      // null → direct (no SOCKS hop) — preserves the dead no-arg
                              // checkPublicIp() behavior, so that overload is deletable
    val port: Int,
    val username: String?,
    val password: String?,
)

// exit/RetryPolicy.kt  — ONE value type encoding the whole ladder
data class RetryPolicy(
    val preReady: Phase = Phase(delayMs = 500, maxAttempts = 60),   // fixed 500 ms, capped: 60×500ms = 30 s window
    val postReady: Phase = Phase(
        successDelayMs = 30_000,     // IP_CHECK_INTERVAL — unlimited attempts
        recoverableDelayMs = 500,    // IP_INFO_RETRY: probe healthy, lookup failed
        failureDelayMs = 5_000,      // IP_CHECK_RETRY: probe failed, strike
        maxConsecutiveFailures = 3,  // MAX_IP_CHECK_FAILURES
    ),
    val suspendedDelayMs = 60_000,   // DOZE_CHECK_INTERVAL — gate-skips re-arm to this
    val roundTimeoutMs = 10_000,     // the race latch budget (Utility:376)
    val httpTimeoutMs = 3_000,       // per-provider connect+read (Utility:407-408)
) {
    data class Phase(
        val delayMs: Long = 500,
        val maxAttempts: Int = Int.MAX_VALUE,
        val successDelayMs: Long = 30_000,
        val recoverableDelayMs: Long = 500,
        val failureDelayMs: Long = 5_000,
        val maxConsecutiveFailures: Int = 3,
    )
}

// exit/ExitEvent.kt
sealed interface ExitEvent {
    /** A provider answered and validated. resolvedAtMs = module clock at resolution (not emission). */
    data class ExitResolved(val info: IpInfo, val resolvedAtMs: Long) : ExitEvent
    /** A round failed. exitHealthy=true ⇔ SOCKS handshake probe succeeded (lookup down, proxy up). */
    data class ExitUnresolved(val attempt: Int, val nextRetryAtMs: Long, val exitHealthy: Boolean) : ExitEvent
    /** The loop cannot continue per policy. Terminal for the current phase. */
    data class ExitStalled(val reason: StalledReason) : ExitEvent
}

sealed interface StalledReason {
    /** preReady.maxAttempts exhausted while still unready. Polling idles; markReady() resumes it. */
    data object PreReadyBudgetExhausted : StalledReason
    /** 3rd consecutive unhealthy round after ready. Loop is dead until the next start(). */
    data class ConnectivityLost(val probe: SocksTester.ProxyProbe) : StalledReason
}
```

`IpInfo` stays a pure data class in `util/` (unchanged, so AIDL flattening and `reconcileNetshield` don't move). Classifier verdict reuses `SocksTester.ProxyProbe` as-is — the service maps `ConnectivityLost.probe` to the existing four error strings verbatim (SocksVpnService.kt:266-275).

### 2.2 Commands

| Member | Contract |
|---|---|
| `start(exit, policy)` | Begin a session: reset strikes/attempts, **keep the hold** (§2.4 invariant 2), fire round 1 immediately. *Replace* semantics: calling while a session runs restarts the cadence from zero strikes with an immediate round — exactly today's screen-on re-verify (SocksVpnService.kt:201-210: reset `mIpCheckFailures`, remove callbacks, post). Main-thread confined. |
| `markReady()` | The tunnel-ready edge (called from `postStartOnMain`). Releases the held resolution at the next internal emission point, then switches all cadence to `postReady`. Idempotent; safe while the loop is idle (post `PreReadyBudgetExhausted` — resumes polling). Main-thread confined. |
| `stop()` | Ends the session: cancels the round loop, closes the channel → in-flight collection **completes normally** (not exceptionally). After return, no further events, ever. Idempotent. Main-thread confined. |

### 2.3 The Flow contract (`events: Flow<ExitEvent>`)

**Cold/hot.** The flow is a *cold collector over a hot conflated source*: the module produces independently of collection (rounds run even with no collector — the channel conflation holds the latest event), and each `collect` realizes the stream over the internal `Channel(Channel.CONFLATED)`. Collection never starts rounds; cancelling the collector never stops them — **lifecycle is the commands, never the collector**.

**Single consumer.** Exactly one active collector. Enforcement: the flow's `onStart` CAS-checks an internal `collecting` flag and throws `IllegalStateException` from the collector if a second one engages; the flag resets on completion. Justification: monotonic truth requires exactly one fold; nothing in the codebase reads exit state twice (AIDL getters read the *folded* fields, not the stream).

**Cancellation.** `stop()` → channel closed → collector completes cleanly (`onComplete`, no exception — documented so the service's `collect` scope treats completion as "session over", not error). Collector cancellation mid-session: delivery stops, monitor unaffected, next emission conflates into the channel — legal only because the module is the sole owner of strike state (§6). Generally: *cancel the collector only after `stop()`*; the module tolerates it otherwise.

**Backpressure.** The channel is CONFLATED: at most **one** undelivered event exists at any time; `send` from the round coroutine never suspends; memory O(1). This is backpressure by coalescing — the emitter is never blocked and the consumer never floods, and the *only* thing a conflated drop can discard is older truth, which is worthless (`resolvedAtMs` guarantees the remaining event is newer). Why drops can never corrupt the strike count is the load-bearing argument for fold placement — §6.

**Concurrent emissions.** Emission is strictly serialized by the producer coroutine (one round at a time, invariant 3); the channel handoff is thread-safe; the collector observes events one-at-a-time, in round order, on the collector's dispatcher (main looper — §5). A consumer calling `start()`/`markReady()`/`stop()` from inside `collect` is legal and deadlock-free (channel handoff is non-reentrant).

**Replay.** None across sessions: each `start()` begins a fresh stream; a late collector attaching after `start()`+`stop()` sees a completed flow. The service attaches once in `onStartCommand` (before the first `start()`) and re-collects on each `start()` — connector pattern owned by the service (§9).

### 2.4 Invariants (ordering / state)

1. **One round, one event.** Every round emits exactly one of the three event types. Rounds are sequential — the next round is scheduled only after the previous completes (matches today: reschedule always happens from the worker's terminal branch).
2. **Start replaces cadence, not the hold.** `start()` while running resets strikes, attempts, and schedule, but keeps any held pre-ready resolution: a buffered IP survives a screen-on restart and is released at the ready edge unless a newer resolution overwrites it (latency-wise *less* lossy than today, where mPendingIpInfo survives only by not being touched).
3. **No resolution before Ready.** `ExitResolved` is never emitted ahead of `markReady()`. A pre-ready resolution is held (latest-wins) and released exactly once at the ready edge — carrying its *original* `resolvedAtMs`. This is the buffering feature (FIX: "never show exit-country before connected") as a structural contract inside the module.
4. **Pre-ready failures are silent to the consumer and uncounted.** Unresolved pre-ready rounds are dropped by `conflateUntil(Ready)` (today's `if (!mTunnelUp) return@runOnMainThread` at :246-252/:287-290); strike counting starts only post-ready.
5. **Gate skips are not rounds.** When the round gate (§4) says suspended, the round is postponed `suspendedDelayMs`, no event, no attempt consumed, no strike.
6. **Monotonic truth.** `resolvedAtMs` is strictly increasing across `ExitResolved` emissions. The consumer fold may additionally compare with the last-applied `resolvedAtMs` and drop anything older — belt-and-braces against any future channel change.
7. **`ExitStalled` is phase-terminal.** `PreReadyBudgetExhausted`: polling idles until `markReady()` (which resumes post-ready cadence) or `stop()`. `ConnectivityLost`: the loop is dead; only a new `start()` revives it. Both reach the consumer exactly once.
8. **Health flag semantics.** `ExitUnresolved.exitHealthy == true` iff the module's injected classifier mapped the round to OK (`SocksTester.ProxyProbe.OK`) — the module runs the probe (as today's worker does, :244) so this is first-hand, not guessed.
9. **Totality.** No exception crosses the seam. Provider/parse/timeout/probe failures are all normal values. `IllegalStateException` is reserved for the two documented contract violations (second collector; command called after `stop()` before a fresh `start()`).

### 2.5 Configuration

Everything above is data: `RetryPolicy` (cadence/strikes/budgets — defaults reproduce today's constants exactly: 500/30000/5000/3/60000/10000/3000), plus the constructor-configurable **provider list** (URL + parse shim — §8.1) and timeouts. Adding a provider, changing the quiet cap, or tuning timeouts = a value change, not a code change.

### 2.6 Performance

Worst round: ~10 s (all providers stall, latch cap); typical success: 0.2–3 s; nominal cadence 30 s. Memory: O(1) pending throughput — conflation bounds it. Threads: **one** producer coroutine per session instead of today's 4 ephemeral `Thread`s per round, fanned out over the injected dispatcher's pool (provider I/O), never holding the main looper. No per-packet work (native tun2socks is invisible to the module).

---

## 3. Seam placement

```
net.typeblog.socks.exit/                 (new, plain JVM)
  ExitMonitor.kt       — commands + round loop + conflateUntil(Ready) + channel
  ExitEvent.kt         — ExitEvent / StalledReason
  ExitSpec.kt, RetryPolicy.kt
  Providers.kt         — the 4 URL+parse-shim entries, validation, AS-normalization (moved from Utility:287-363)
  RealFetcher.kt       — HTTP-over-SOCKS transport (java.net only; Authenticator pin moved in, Utility:380-424)
net.typeblog.socks.util/
  IpInfo.kt            — unchanged
  SocksTester.kt       — unchanged; ProxyProbe consumed via the classifier seam
net.typeblog.socks.SocksVpnService        — the one consumer (fold + projection)
```

- **The external seam is `exit/`'s own interface** — crossed by exactly one production call site, `SocksVpnService`. `Utility` loses `checkPublicIp` ×2 and `fetchPublicIp` (~145 lines) and imports nothing new; `SocksTester` and `IpInfo` stay where they are.
- The seam deliberately **does not widen to the UI**: `IVpnService.aidl`, the AIDL getters, `FloatingControlService`'s 200 ms poll, and `VpnViewModel`'s poll change zero lines. This is a strictly-internal refactor of the `:vpn` process (same posture as alt-a/alt-c).
- What moves behind the seam: the 4-provider race + latch + per-provider timeouts, all parse/validation logic, the `Authenticator` global pin (now set once, singleton-owner), the runnable's cadence ladder, buffering (`mPendingIpInfo`/`mTunnelUp` lattice :232/:246/:287 — gone from the service), the strike counter (`mIpCheckFailures`), doze skip (`isNetworkCheckBlocked`), screen-on reset semantics.
- What stays in the service (its identity, not engine logic): `VpnBinder` (reads folded fields), `configure()`/`establish()` (VpnService-only API), `applyIpInfo` side effects — `reconcileNetshield()`, `updateNotification()`, AIDL mirrors — teardown `stopMe`, spawn orchestration, stats, receivers. `applyIpInfo` is not deleted; it becomes the *fold's* sink.

**Android-free parsing boundary (explicit).** `exit/` has zero `android.*` imports: transport is `java.net` (`HttpURLConnection`/`Proxy`/`Authenticator` — already JVM); classification is `java.net.Socket` (`SocksTester`); parsing is `org.json` with pure `JSONObject → IpInfo?` shims; the clock is an injected `() -> Long`. The only Android code anywhere in the cluster is the *service side* (collector scope, `markReady()` call site) and the round gate's production adapter (`PowerManager`) — which itself lives behind a seam (§4). Unit-testable on plain JVM with `org.json:json` as a test artifact.

**Threading / dispatchers (the `:vpn` main-looper constraint).**

| What | Dispatcher | Why |
|---|---|---|
| Commands (`start`/`markReady`/`stop`) | main looper (`Dispatchers.Main.immediate`), service's own thread | today every decision lands on main after marshaling (`runOnMainThread`, :948); preserves that discipline as a contract |
| Collector (`events.collect`) | main looper, in a `CoroutineScope` owned by the service (Job cancelled in `stopMe`) | the fold's sinks (`applyIpInfo`, `stopMe`, notification) are main-thread APIs |
| Provider I/O + probes (per-round races) | injected `roundDispatcher` (prod: `Dispatchers.IO`; test: `TestDispatcher` on the test scheduler) — `flowOn` boundary inside the module | main looper must never block; tests get virtual time for free |
| Scheduling (delays) | coroutine `delay()` on the round dispatcher | virtual-time-testable without a hand-rolled Scheduler seam (alt-c's `Scheduler` becomes gratuitous) |

Coroutines precedent in `:vpn` already exists: `SocksTester` uses `withContext(Dispatchers.IO)` and runs inside this process; R8 coroutines keep-rules are already in `proguard-rules.pro:32-34`.

---

## 4. Adapters — the two-adapter test, run properly

All seams below are **internal** (DEEPENING.md's internal-seam rule: private to the implementation, used by its own tests, never visible through the interface). The `Environment` bundle is an implementation detail of the constructor — the public shape is the commands + flow.

| Internal seam | Production adapter | Test adapter | Verdict |
|---|---|---|---|
| `fetcher: (url, ExitSpec) -> String` (HTTP round-trip) | `RealFetcher` — `HttpURLConnection` over SOCKS proxy with the pinned `Authenticator` | scripted fake: per-provider latency + canned JSON / garbage / throw (hermetic race control) | **real** — two adapters; the race's winners are controllable only through it |
| `classifier: (ExitSpec) -> ProxyProbe` | `SocksTester::probeProxy` (existing, unchanged) | scripted fake ("always OK", "OK then fail-fail-fail", …) | **real** — two adapters |
| `roundGate: () -> Boolean` | `PowerManager.isInteractive && !isDeviceIdleMode` (moved from `isNetworkCheckBlocked`, :300-304) | fixed true/false/scripted sequence | **real** — doze timing is exactly what cadence tests must control |
| `nowMs: () -> Long` (monotonic timestamp provenance) | `System.nanoTime()/1e6` | fake: manual counter | **real** — `resolvedAtMs` strict-monotonicity is asserted in tests |
| `roundDispatcher: CoroutineDispatcher` | `Dispatchers.IO` | `StandardTestDispatcher(testScheduler)` | **real** — the virtual-time seam (§7) |
| provider list (`url` + parse shim) | the 4 production endpoints | 2 fake providers with controlled parse outcomes | **real as configuration/data**, not a strategy seam — the four parsers are one transport with schema fallback chains; no `IpProvider` interface invented |

Every listed seam names its second adapter (test) — none is a hypothetical production-only indirection. The seam the *service* sees is only the commands + `events` flow; DEEPENING.md's "don't expose internal seams through the interface" is honored.

---

## 5. Depth analysis

**What hides behind `events: Flow<ExitEvent>` + 3 commands:**

- ~145 lines of provider machinery (Utility:284-424): 4 URLs, 4 schema shims with alias fallbacks (`country_code`/`countryCode`, nested `connection`/`timezone`, `timeZones[]`, empty-ip and `status`/`success` gates, `AS`-prefix normalization ×2), thread-per-provider race, `AtomicReference`+`CountDownLatch` first-wins, 10 s latch budget, exception suppression.
- The cadence ladder: pre-ready 500 ms, post-ready 30 s / 500 ms / 5 s, doze 60 s re-arm, the 3-strike policy, screen-on reset — today interleaved across `mIpCheckRunnable` (:211-298), `mScreenOnReceiver` (:201-210), `postStartOnMain` (:914-946), and `stopMe` (:533-535).
- The buffering contract: hold-latest pre-ready, release-exactly-once at ready edge, pass-through post-ready — today scattered at :232/:246/:928-935 with the `mTunnelUp` flag.
- The failure classification and its scheduling math (probe OK ⇒ 500 ms no-strike; probe fail ⇒ 5 s + strike) and the doze gate.
- The `Authenticator.setDefault` process-global wart, now pinned once at module construction instead of per request.

That is the entirety of the cluster's difficulty — failure-heavy, time-sensitive, environment-dependent — behind a sealed event type and a scheduler value. **Depth: high.** The service keeps only what it needs to *project*: state mirrors plus the one terminal reaction. The "knob count" a caller must learn: 3 commands, 1 flow, 4 types — and then it can forget every timeout, provider, and cadence constant in the codebase.

**Leverage.** Honest ledger: one production consumer today — same as every other alt. The leverage buys: (a) the repo's first real test surface (the flow contract is testable headlessly with virtual time — the current race has never been testable in ~150 lines of private code); (b) a latent stream for future consumers that want the same truth — a StatusScreen "test this exit" button, BootReceiver pre-flight, ProfileManager proxy verification — each just `exitMonitor.start(spec, policy).events.first()`; (c) deletion of the dead no-arg overload and of ~90 lines of lattice from the battle-tested service; (d) a fold/projection split under which the service's remaining code is purely its own identity (AIDL/notification), not engine logic.

**Locality.** Four classes of future change concentrate in one package: provider add/deprioritize (one list entry + one parse shim), provider schema drift (one lambda), cadence/strike/budget tuning (one `RetryPolicy` default), backoff/buffering semantics (one operator, one channel). Today those changes are split across `Utility.kt` (providers) and three service regions (cadence/buffer/strikes) — locality genuinely improves, not just moves.

---

## 6. Deletion test — and the fold-placement decision

**Delete `ExitMonitor`:** the provider race + parsers + transport re-materialize in `Utility` (exactly as today); the cadence ladder, strike counter, doze skip, and buffering lattice re-materialize in the service (exactly as today); and the flow's conflation semantics re-materialize as ad-hoc `AtomicReference` juggling. Complexity scatters across two files and ~8 handler branches — it does not vanish. **The module earns its keep.**

But the assigned question was narrower: *where does the 3-strike fold live — service or module?* Apply the deletion test to each placement:

- **Fold in the service** (count `ExitUnresolved` on the collector): delete the module's *fold serving* parts and the service re-owns the count — which is fine — but to count it must know *healthy vs unhealthy*, which requires the probe result. The probe results from the module (it must run it anyway to pick 500 vs 5000 ms). So the classification must cross the seam → `ExitUnresolved` gains a classification field the service only consumes to re-implement the module's own scheduling decision. Worse: the conflated channel **can drop an `ExitUnresolved`** (a new round lands before the main looper drains). A dropped event = a *missed strike* — the consumer fold would silently under-count and never tear down. The fold is then wrong *by the design of the channel it runs on*.
- **Fold in the module** (as built): the strike count is a policy secret (`postReady.maxConsecutiveFailures`) computed where its inputs live (classifier verdict, cadence, gate state). The stream never carries the count, so drops cannot corrupt it — an `ExitUnresolved` dropped mid-flight loses only *display* metadata, never policy state. The module emits the count's terminal consequences as `ExitStalled(ConnectivityLost(probe))`; the service maps `probe` to the existing error strings verbatim.

**Decision: strike fold inside the module** — the deletion test cuts exactly there: a consumer-side fold is *broken by the conflation* (the module's own backpressure design proves the fold must live where drops cannot observe it), and a module-side fold leaves the consumer fold to be exactly the projection it must build anyway (state mirrors for AIDL). The service still has a fold — over the *status* stream (ip/country/verified/error → `applyIpInfo`, notification, `stopMe`) — but every rule in it is a display rule, and deleting them leaves the AIDL projection untouched: the service's fold is its identity, not hidden engine logic.

---

## 7. Testability — Flow tests with virtual time

Plain JVM tests (`app/src/test`), `kotlinx-coroutines-test` `runTest` + `StandardTestDispatcher` injected as `roundDispatcher`, scripted fetcher/classifier/gate, fake clock. The clock and dispatcher are the *only* two seams tests need for determinism — no hand-rolled Scheduler (alt-c's `Scheduler` seam is subsumed by coroutine `delay` on the test scheduler; schedules are asserted via runtime, not ticks).

| Scenario | Script | Assertion (all through `events`) |
|---|---|---|
| First-wins race | fetcher B answers +10 ms garbage, A +50 ms valid | exactly one `ExitResolved`, value == A's, appears ≈50 ms of virtual time, not 10 s |
| All-stall round | all 4 throw/timeout, classifier OK | `ExitUnresolved(attempt=1, nextRetryAtMs=500, exitHealthy=true)` |
| **Hold contract** | provider answers at t=0, `markReady()` at t=5 s | **no** `ExitResolved` before the ready edge; at the edge exactly one, with `resolvedAtMs == 0` (original timestamp preserved) |
| Hold-latest-wins | resolve A at t=0, B at t=3 s, ready at t=5 s | one emission at the edge, value == B |
| Pre-ready silence + cap | fetcher never answers, advance 60×500 ms | zero consumer events while unready; then `ExitStalled(PreReadyBudgetExhausted)`; `markReady()` at t=40 s → loop resumes post-ready |
| 3-strike teardown | post-ready classifier fail,fail,fail at 5 s cadence | strikes internal: `ExitUnresolved(…healthy=false)` ×2, then `ExitStalled(ConnectivityLost(AUTH_FAILED))` — never a 4th event |
| 2-strike recovery | fail,fail,OK | no `ExitStalled`; next failure restarts from strike 1 (internal), cadence back to 30 s on `ExitResolved` |
| Healthy proxy, dead lookup | classifier OK forever, fetcher null | 500 ms fast retries indefinitely, `exitHealthy=true`, no strikes, no teardown |
| Backpressure/drop safety | collector `delay()`s 200 ms per event while rounds fire every 100 ms (fake clock) | collector sees a subset; count of observed `ExitUnresolved` ≠ real rounds — **and no teardown accuracy loss** (strikes stayed internal: scripted 3 consecutive unhealthy still yields exactly one `ExitStalled`) |
| Doze | gate=false for 2 rounds then true | nothing emitted, 60 s re-arm each skip, zero attempts consumed (cap intact) |
| `stop()` mid-round | fetcher blocked on a virtual-time latch | after `stop()`: flow **completes normally**, no events; `collect` is clean, not exceptional |
| Monotonic timestamp | fake clock non-monotone (bug-injected) | test asserts strict increase of `resolvedAtMs` across emissions |
| Screen-on replace | run, `start()` again pre-ready with held B | hold B survives (§2.4 invariant 2); strikes/schedule reset; immediate new round |

Every test is hermetic, deterministic, sleep-free. The one existing behavior that moved into the module — "never show exit-country before connected" (the just-landed buffering feature) — is pinned at the interface level as its own test (rows 3–4). Tests replace, not layer: there are no service-side tests today to delete; the *new* interface is the entire test surface.

---

## 8. Internal seams (private; for the module's own tests only)

1. **`conflateUntil(Ready)`** — the named internal operator (the design's centerpiece seam). Signature (module-internal): `Flow<RoundOutcome>.conflateUntil(ready: Flow<Unit>): Flow<ExitEvent>`. `RoundOutcome` is an internal type (`Resolved(IpInfo) | Unresolved(attempt, healthy) | Stall(reason)`); the operator owns: pre-ready hold-latest, exactly-once release at the ready edge (with original `resolvedAtMs`), drop-unresolved-pre-ready, stitch of `StalledReason`, pass-through post-ready. The `ready` flow is fed by `markReady()` via a `MutableSharedFlow<Unit>(replay=1)` — one seam, three lines, both production and tests cross it the same way.
2. **`Environment`** bundle (fetcher/classifier/gate/clock/dispatcher) — §4 table.
3. `Providers` list + parse shims — configuration data with a JUnit-expressible shape.
4. The round loop state (strikes, attempts, phase) — deliberately *not* extracted as a separate pure state machine class: it's ≤20 lines and the operator tests already cover it through the interface; extraction would be a second interface for the same surface (DEEPENING.md: don't expose internal seams because tests use them).

---

## 9. Migration sketch

**End state.** `SocksVpnService.kt` 964 → ~830 lines: `mIpCheckRunnable` (:211-298), `mIpCheckHandler` (:154), `mIpCheckFailures` (:151), `mTunnelUp` (:152/:534/:927), `mPendingIpInfo` (:153/:535), the screen-on reset (:205-207), `isNetworkCheckBlocked` (:300-304), runnable re-arms in `stopMe` (:533) — all deleted. Replaced by: `val exitMonitor = ExitMonitor(env)` field, `val exitScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)`, and a ~45-line fold:

```kotlin
private fun onExitEvent(e: ExitEvent) {               // collector body — the service's fold (display rules only)
    when (e) {
        is ExitResolved -> applyIpInfo(e.info)                        // side effects unchanged
        is ExitUnresolved -> if (e.exitHealthy) { mProxyVerified = true; updateNotification() }
        is ExitStalled -> when (val r = e.reason) {
            is StalledReason.ConnectivityLost -> stopMe(messageFor(r.probe))  // strings from :266-275, verbatim
            StalledReason.PreReadyBudgetExhausted -> {}               // idle; markReady() resumes
        }
    }
}
```

Call sites: `onStartCommand`/`start()` (:697) → `exitMonitor.start(ExitSpec(resolvedServerOrServer, port, user, pass), defaultPolicy); exitScope.launch { exitMonitor.events.collect(::onExitEvent) }`; `postStartOnMain` → `exitMonitor.markReady()` (the buffered-IP apply at :928-935 becomes the operator's ready-edge release — no service code); screen-on receiver → `exitMonitor.start(...)` (replace semantics = reset + immediate round, matching :205-207); `stopMe` → `exitScope.cancel(); exitMonitor.stop()` before `stopSelf()`.

**Step order.** (1) Snapshot tag `pre-exit-monitor` per AGENTS.md, push tag. (2) `exit/` package + JVM tests; add `testImplementation` deps (`junit:junit:4.13.2`, `org.jetbrains.kotlinx:kotlinx-coroutines-test`, `org.json:json`); extend `.github/workflows/build.yml` with a `testDebugUnitTest` step. (3) Service rewiring (mechanical swap, no behavior redesign); delete `Utility.checkPublicIp` ×2 + `fetchPublicIp` + the provider list; delete the runnable/lattice. (4) Build via the GitHub builder only; on green, update the **AGENTS.md filesystem map** in the same commit (repo rule). (5) Parity check: overall exit-check cadence, notification text, reconcile-on-country-change, screen-on + doze behavior — each backstopped by the §7 suite plus one on-device connect cycle.

**What breaks / touches:** `IVpnService.aidl`, UI, `FloatingControlService`, `VpnViewModel`, `ProfileManager`, native engine, manifest — all untouched. `SocksTester` consumed (not modified). `IpInfo` untouched. The two behavior deltas to watch: the pre-ready cap (new: bounded quiet polling, §10.3) and the screen-on hold-preservation§ (invariant-2 nuance, §10.4).

---

## 10. Comparison — alt-a / alt-b / alt-c′ / ExitMonitor

| Axis | Alt A (`IpInfoFetcher.fetch`) | Alt B (`TunnelSession`) | Alt C′ (`IpSession`) | **ExitMonitor (this design)** |
|---|---|---|---|---|
| **Depth** (interface : hidden behavior) | 1 call : ~140 lines — deep, but hides *no scheduling*; the cadence/buffer/strike difficulty stays in the service | ~6 types : ~370 lines — deepest, but absorbs spawn/doze/NetShield that are already linear and untouched by policy | 3 methods : ~180 lines — deep, gate folded in (alt-c's own recommendation) | 3 commands + 1 flow + 4 types : ~250 lines incl. policy, buffer, strikes — deep; removes the *hardest* part of the service's lattice, not just the race |
| **Seam placement** | util↔service, one static call becomes one instance call — narrowest, least invasive | new `engine/` coordinator seam inside `:vpn`; all engine logic behind it | new `ipcheck/` seam; producer real seam, latch folded in | new `exit/` seam: same cross-section as C′ plus main-dispatcher/flow contracts; AIDL untouched everywhere |
| **Migration risk** | lowest (mechanical move, service untouched) | highest (big-bang move of battle-tested spawn logic; parity suite required) | low–medium (runnable lattice surgery, ~65 service lines deleted) | medium: lattice deletion is mechanical, but the flow/collector lifecycle, coroutines in `:vpn` (precedent exists), and a CI test step are new surfaces; backstopped by snapshot tag |
| **Testability** | good (fake transport + local HTTP server), but cadence/buffer/strikes remain **untestable** (service-hosted) | very good headless, but Robolectric-required (Context, prefs) and probe-pool tests need manual schedulers | good: fake fetcher/classifier/scheduler ticks; latch tests via 6×3 matrix | **best**: plain-JVM virtual time (`runTest`), no manual tick seams, cadence asserted by virtual runtime; drop-safety and monotonicity have dedicated tests no other alt can express |
| **Strike/policy ownership** | service | session | service (owner counts FailedRound)** | module (fold inside — argued via drop-safety, §6) |
| **Backpressure** | n/a (blocking) | implicit | implicit | explicit conflation with a *proof* of drop-safety (the fold-inside argument) |

Bottom line: alt-a is the smallest diff and the least depth gained; alt-b over-extracts into a mega-coordinator the repo rules tell us not to touch; alt-c′ is the strongest of the three (its own recommendation). ExitMonitor differs on the one axis the others share: all three still model *"cause a fetch, handle a result"* — a request/response pair. ExitMonitor models the cluster as *"a policy-governed stream of outcomes"* — which is what the code actually is (an infinite retry loop with two phases, a hold, and a strike fold), makes the backpressure and buffering semantics *named operators* instead of scattered branches, and turns its hardest-to-test properties (drop-safety, ordering, monotonicity) into interface-level tests. It is the only design where the fold placement is *forced* (not chosen) by the backpressure primitive.

---

## 11. Honest drawbacks

1. **Flow ceremony for one consumer.** Three commands + a single-collector Flow contract is a real interface to learn for a subsystem with exactly one production caller. If no second consumer ever materializes, part of the machinery (backpressure vocabulary, monotonicity guarantees) is speculative. Hedge: it is also the machinery the tests run on — it pays for itself even at one consumer.
2. **Conflation drop-safety depends on the fold-inside decision.** A future developer moving the strike count consumer-side (e.g. to expose "failures: 2/3" on the UI) will re-import the correctness hole unless they widen the channel. Mitigation baked in: `attempt`/strikes are policy data, and a UI strike readout should come from a new `ExitEvent` field, not a re-derived count.
3. **The pre-ready cap is new behavior.** Today's quiet loop retries 500 ms forever until ready or stop (:246-252). The cap (60 → 30 s) bounds it; with spawn worst case ~5 s (sendfd 100×50 ms) the cap realistically never fires, but a pathological spawn hang now idles *silently* (an `ExitStalled(PreReadyBudgetExhausted)` the service currently ignores) instead of polling. Documented, tested, but a real semantic delta.
4. **Replace-start nuance.** Screen-on now also preserves the buffered hold (invariant 2) whereas the old code could overwrite-then-keep `mPendingIpInfo` differently in one edge order (screen-on during spawn). Directionally safer (an IP isn't lost), but it changes one corner of the buffering timeline — flagged for the on-device parity pass.
5. **First coroutines in the service body.** `:vpn` already executes coroutines transitively (`SocksTester.withContext`, R8 keep-rules present), but the service itself gains its first `CoroutineScope`/collect. Scope lifecycle (cancel in `stopMe` before `monitor.stop()`) must be right or the collector leaks; the §7 tests assert the module side, the scope is 3 lines.
6. **Greenfield test infra + CI change.** The repo has zero tests and the workflow currently builds only; adding JUnit/coroutines-test/org.json and a `testDebugUnitTest` step touches `.github/workflows/build.yml` — new failure modes in CI, and `org.json:json` (JVM artifact) is not byte-identical to Android's `org.json` in every corner (the four shims only use `optString`/`optJSONObject`/`optJSONArray`/`getString` — safe, but the dependency must be declared deliberately).

---

## Verdict

`ExitMonitor` passes the deletion test twice — delete the module and the entire cluster re-scatters; put the strike fold in the consumer and the conflation design itself breaks it, proving the fold belongs inside. It is the only candidate whose backpressure primitive *forces* the correct ownership split, whose buffering semantics are a named operator rather than scattered `if (!mTunnelUp)` branches, and whose hardest-to-test props (ordering, drop-safety, monotonic truth) become compile-checked interface contracts with plain-JVM virtual-time tests. It is also the candidate with the most ceremony for one consumer — that is the honest price of a stream-shaped interface, and the tests + latent consumers are what pay it.