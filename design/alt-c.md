# Alt C — "Gate/Publish Split" for the parallel IP check + buffer-until-tunnel-up mechanics

**Design doc — deep-module analysis of splitting `mIpCheckRunnable` into `IpProbe` | `Gate`, an honest verdict, and the better alternative.**
No repo files were modified by this exercise. Grounding: `SocksVpnService.kt` (mIpCheckRunnable :211–298, parallel start :697, postStartOnMain :914–946, stopMe :462–551, constants :952–963), `Utility.kt` (:278–424), `SocksTester.kt`, `IVpnService.aidl`, `FloatingControlService.kt`, `VpnViewModel.kt`. Vocabulary per `codebase-design` skill (module, interface, seam, adapter, depth, leverage, locality, deletion test, two-adapters test).

---

## 1. Module names + shape

### 1.1 `IpProbe` — the producer (deep by construction)

`IpProbe` is a pure producer of IP/country facts about the SOCKS exit. It owns the four-provider race, per-fetch timeouts, the round latch, all four JSON schema quirks, validation (first-non-null-wins), and — per the assignment — the *failure-retry cadence up to a configurable cap*. A "session" is one `start(...)`; within a session the probe self-loops on failure at `policy.retryDelayMs` until the first `Result` (session ends) or `policy.maxAttempts` (session ends idle). It knows **nothing about tunnels**: no readiness flag, no buffering, no "connected" concept. It knows SOCKS (it must classify failures), but the tunnel is invisible to it. The service drives every significant beat: when to change cadence, when to stop.

```kotlin
interface IpProbe {
    fun start(exit: ExitSpec, policy: ProbePolicy)   // begin (or restart) a probe session
    fun stop()                                       // end session; no further events after return
}
```

Listener is wired once at construction (the service's listener never changes):

```kotlin
class IpProbeImpl(
    private val listener: (ProbeEvent) -> Unit,      // always invoked on the main thread
    private val fetcher: ProviderFetcher,            // seam: real / fake
    private val classifier: FailureClassifier,       // seam: SocksTester / fake
    private val scheduler: Scheduler                 // seam: real / fake clock+queue
) : IpProbe
```

Dependencies are accepted, not created (skill rule 1). `IpInfo` moves nowhere — it already lives in `util/` as a pure Kotlin data class.

### 1.2 `Gate` — the tiny buffered publisher

`Gate` is a latch: input side receives fully-classified *values* (`IpInfo?`), output side publishes them to the service's `applyIpInfo` funnel, and the release is a single explicit `markReady()` from `postStartOnMain`. Non-null values offered before ready are held (latest-wins); the held value is published exactly once at the ready edge; null offers are never held and never published (failure classification is the owner's job, via `FailedRound` events — the Gate is not the failure channel); values offered after ready pass straight through (the 30s health loop must not be swallowed). One instance per VPN session; one-way lifecycle (exactly one false→true edge exists today; everything else tears down the service, SocksVpnService.kt:914–946, :462–551).

```kotlin
class IpGate {
    fun markReady()
    fun offer(info: IpInfo?)
    fun onPublished(cb: (IpInfo) -> Unit)
    fun isReady(): Boolean
}
```

> Deviation from the assignment's "publishes exactly once": exactly-once is scoped to the **buffered phase** (the hold). Post-ready the gate is a pass-through, because the 30s health loop produces a new value every 30 seconds for the life of the session; a globally-once gate would force the service to route health results through a second, duplicate channel. Named deliberately.

---

## 2. Complete interface specs

### 2.1 `IpProbe`

**Types**

```kotlin
data class ExitSpec(val server: String?, val port: Int, val username: String?, val password: String?)

data class ProbePolicy(
    val httpTimeoutMs: Long = 3_000,    // per-fetch connect+read (matches Utility :407–408)
    val roundTimeoutMs: Long = 10_000,  // round latch cap (matches Utility :376)
    val retryDelayMs: Long = 500,       // between FailedRound and the next round
    val maxAttempts: Int = Int.MAX_VALUE // quiet default; health mode uses 1
)

sealed interface ProbeEvent {
    data class Result(val info: IpInfo) : ProbeEvent                        // non-null, validated
    data class FailedRound(val attempt: Int, val classification: ProxyProbe) : ProbeEvent
    data object Exhausted : ProbeEvent                                      // cap hit, loop idle
}
```

**Methods** — `start(exit, policy)`, `stop()`. Both are idempotent: `start` replaces any in-flight session (stop-then-start internally); `stop` is a no-op if no session.

**Invariants (caller must know these)**

1. At most **one** `Result` per session. After it, no further events of any kind until a new `start`.
2. Zero or more `FailedRound` events per session, one per failed round, strictly ordered by `attempt` (1..maxAttempts).
3. At most one `Exhausted`, always the session's final event, only when `attempt == maxAttempts` and all attempts failed.
4. `Result.info` is always non-null: the module treats a provider response as a win only if all validation passes (non-blank `ip` and `countryCode`; schema quirks — `AS` prefixing at Utility :339/:357, ipapi alias keys :312, freeipapi timezone array :359 — are internal).
5. If all four providers fail a round, the module runs the injected classifier **once per failed round** and attaches the result to `FailedRound`. The owner builds user-facing error strings from `classification` (SocksVpnService.kt:266–275) — no network I/O happens on the owner's stack.
6. A round is one race: ≤4 concurrent fetches, each bounded by `httpTimeoutMs`, the round bounded by `roundTimeoutMs`; rounds never overlap (next round is scheduled only after the previous completes — matches today's behavior, where reschedule always happens after the worker thread returns).
7. The module self-loops on failure at `retryDelayMs` up to `maxAttempts`, then idles. The service restarts with a fresh policy to change cadence ("go-healthy" = restart with `maxAttempts=1` and let the service own 30s/5s re-arms — see §9).
8. **Error modes:** none surfaced to the owner as exceptions. Network failure, malformed JSON, and total round failure are all normal values (`FailedRound`). A bug in the owner's listener is delivered on the main thread and propagates like any main-thread exception.

**Threading**

- All public methods are called from the **main thread** (single-threaded owner discipline; the service already funnels everything through main after marshaling).
- Internally: one sequential dispatcher thread owns rounds; the 4 provider fetches fan out from it. **The listener is always invoked on the main thread** (module posts delivery), strictly sequentially, never concurrently.
- `stop()` guarantee: after `stop()` returns, the listener is never invoked again. This holds because `stop()` runs on the main thread and delivery also lands on the main thread's FIFO queue: any event already queued executes before `stop()` returns; anything after observes the volatile stopped-flag and is discarded. (Callers must therefore call `stop()` on main — documented constraint.)

### 2.2 `IpGate`

**Methods** — `markReady()`, `offer(info)`, `onPublished(cb)`, `isReady()`.

**Invariants (caller must know these)**

1. **Hold-semantics:** `offer(nonNull)` before `markReady()` → held (latest-wins; a second non-null offer replaces the first — matches today's `mPendingIpInfo = info` at :233).
2. `markReady()`: if a value is held, invoke `cb(held)` **exactly once**, then forget it. This is the "never show exit-country before connected" guarantee, pinned in one place.
3. `offer(info)` after `markReady()` → `cb(info)` synchronously on every offer (pass-through for the health loop). The assignment's "publishes exactly once" is scoped to the buffered phase, per §1.2.
4. `offer(null)` → **ignored in all states** (never held, never published, no state change). Nulls are failure-channel material, handled by the owner via `FailedRound`.
5. `onPublished` must be registered before `markReady()`/`offer`; otherwise the event is lost (ordering constraint — the service registers once at session start).
6. `isReady()` is the single authority the service uses for its own decisions (strike counting, re-arm choice). The service's private `mTunnelUp` is deleted; no parallel flag.
7. One-way instance per VPN session: created in `onStartCommand`, discarded in `stopMe`. `offer` after session end is impossible (no reference). "Offer after stop is ignored" holds trivially; document that reuse is forbidden rather than implementing a `stop()` that silently no-ops.

**Threading** — main-thread confined, zero locks, zero blocking: `cb` runs on the caller's stack. This is deliberate and is *why* ordering is trivially correct: the confinement is an interface contract, not an accident.

**Error modes** — none. Exceptions from `cb` propagate to the caller of `markReady()`/`offer()` (the service wraps; it's its own `applyIpInfo`).

---

## 3. Seam placement

```
net.typeblog.socks.ipcheck/            (new package)
  IpProbe.kt        — interface + ExitSpec + ProbePolicy + ProbeEvent
  IpProbeImpl.kt    — race, parse, validation, retry loop, classification
  IpGate.kt         — buffered publisher
net.typeblog.socks.util/                (unchanged)
  IpInfo.kt         — stays (pure data class, no android deps → JVM-testable)
  SocksTester.kt    — unchanged; SocksTester.probeProxy is CONSUMED via the classifier seam
net.typeblog.socks.SocksVpnService      (owner/coordinator)
```

- **Seam 1 — producer seam:** `IpProbe` interface. This is the real external seam of the whole change. Package `net.typeblog.socks.ipcheck`; nothing in `util/` grows.
- **Seam 2 — gate seam:** `IpGate` interface, same package. Honest assessment below: this seam is **hypothetical** (one consumer).
- **SocksVpnService keeps** (deliberately — battle-tested, §9 mapping): the 3-strike counter, proxy-failure → user-facing error strings, teardown (`stopMe`), the health cadences (30s success re-arm, 5s strike backoff, 60s doze), `applyIpInfo` side effects (`reconcileNetshield()`, `updateNotification()`, stats/notify), the AIDL getter surface, screen-on re-arm. It **loses**: `mIpCheckRunnable`, `mPendingIpInfo`, `mTunnelUp`, `mIpCheckHandler`, the 500ms quiet branches, and the whole `if (!mTunnelUp)` lattice at :232/:246/:287.
- **UI observation is untouched end-to-end**: `IVpnService.aidl` getters, `FloatingControlService`'s 200ms poll, and `VpnViewModel`'s 200ms poll change zero lines. The doc's seam ends at the service; this is a strictly-internal refactor of the `:vpn` process.

---

## 4. Adapters — the two-adapters test, run properly

**Inside `IpProbeImpl` (internal seams per DEEPENING.md — private to the implementation, used by its own tests):**

| Seam | Production adapter | Test adapter | Verdict |
|---|---|---|---|
| `ProviderFetcher` (one `(url, spec) -> IpInfo?`-ish call, SOCKS+runtime auth wiring — moved verbatim from `fetchPublicIp` :380–424) | real `HttpURLConnection` through the SOCKS `Proxy` with `Authenticator` | scripted fake: per-provider latency + canned JSON/garbage/throw | **real** — the race's *winners* are only controllable through it |
| Provider list (the 4 `(url, parseFn)` entries :287–363) | the 4 production endpoints | 2 fake providers with controlled parse outcomes | **real as configuration** — it is data, but the *seam* is the fetcher+parser slot; two configurations exist (prod/test), and the parsing quirks are the module's core tests |
| `FailureClassifier` (`() -> ProxyProbe`) | `SocksTester.probeProxy` | fake ("always OK", "AUTH_FAILED once then OK") | **real** — two adapters |
| `Scheduler` (delay queue + fake clock) | `Handler`/`ScheduledExecutor` | manual tick | **real** — retry cadence tests need it |

**Inside `Gate`:**

| Seam | Production adapter | Test adapter |
|---|---|---|
| the `onPublished` consumer | `SocksVpnService::applyIpInfo` | a test recorder |

**One consumer. The gate seam is hypothetical.** Per the skill: *"one adapter means a hypothetical seam."* The gate cannot be justified as an interface with adapters; at best it is justified as a tiny invariant-pinning class (§5.2). Say it plainly: **the producer seam is real, the gate seam is not.**

No other seams exist: `Utility.checkPublicIp` has exactly **one** call site (SocksVpnService.kt:229 — verified by grep; the no-arg overload at Utility :279 is dead code). There is no second consumer hiding anywhere.

---

## 5. Depth analysis per module

### 5.1 `IpProbe` — deep

Interface: `start(exit, policy)` / `stop()` + one sealed event type = a small, learnable surface. Implementation behind it: the full provider race (4 concurrent HTTP fetches through SOCKS, CAS + latch first-wins, 3s timeouts, 10s round cap), four distinct JSON schema parsers with field-alias handling, `AS`-prefix normalization, validation, per-round failure classification, the bounded retry loop, cancellation, event ordering. **This is ~130 lines of today's code (Utility :284–424 + the runnable's probe plumbing at :227–244) collapsing behind a 2-method interface.** That is exactly the depth definition.

- **Leverage:** one implementation pays back across the service's *two* modes (quiet/health), the integration tests in §7.3, and any future consumer (an in-app "test exit" diagnostic on the status screen would take it for free). The service's per-branch complexity drops to dispatch code.
- **Locality:** provider schema changes (a provider renames a field, ip-api drops `status`) touch exactly one file. Today that change is buried among 90 lines of tunnel-spawn-adjacent handler logic. Fix once, fixed everywhere.

### 5.2 `IpGate` — shallow. Say so.

Interface: 4 methods + 1 callback + 6 invariants + a threading contract. Implementation: a nullable field, a boolean, and ~15 lines of branch logic. **The interface is nearly as complex as the implementation — a textbook shallow module.** It is not *exactly* a pass-through (it does transform: hold → release-exactly-once → pass-through), but the transform is a latch with one boolean. Honest: judged purely on depth, the assigned split is *IpProbe deep + Gate shallow* — the shallow tax is one extra interface to learn (~20 lines of invariants) for ~35 lines of logic.

**What justifies it anyway (partial):** the invariant it pins — *never surface exit-country before the tunnel is connected; surface it exactly once at the ready edge* — is the entire point of the just-landed buffering feature, and it currently lives in **three scattered places** (:232, :246, :928). Colocating it is a *locality* win even at zero depth. But that argument earns the gate a spot **inside** a module, not a standalone interface with a consumer seam.

### 5.3 The modules together (as assigned)

Combined interface count: 2 modules, 6 methods, 2 callbacks, ~10 invariants. Combined implementation: ~180 lines. The combined *behavior* is exactly what one module could hide behind a 3-method interface (§8.2). The split pays a full extra interface for the gate's 35 lines — the skill's canonical anti-pattern (two small modules where one deep module wants to be).

---

## 6. Deletion test per module

**Delete `IpProbe`:** the race, the four parsers, timeouts, validation, retry loop, and classification all reappear — spread across `SocksVpnService` (as the runnable's branches) *and* `Utility` (as `checkPublicIp`/`fetchPublicIp`), i.e. across **two** modules and ~6 handler branches. Complexity does not vanish; it re-scatters. **The module earns its keep.** ❗A new `FailedRound`-style event would also be invented locally by the service to feed its strike counter — confirming the probe really is a self-contained producer with a natural seam.

**Delete `IpGate`:** its behavior re-materializes as `mPendingIpInfo` + the `if (!mTunnelUp)` guard at three sites **inside a single module** (the service). Roughly one module → one module. **In skill terms: pass-through, deletion test fails** — with the nuance that the *bug the buffering feature fixed* would re-materialize along with it, because the invariant is subtle (it took a UI-visible bug to land this code). That nuance keeps the gate *worth keeping* — but as an internal seam, not a module.

---

## 7. Testability

The repo currently has **no tests at all** (`app/src/test` and `app/src/androidTest` are empty). This design is the first thing in the repo that is JVM-unit-testable through its interface, because `IpProbeImpl`, `IpGate`, `ExitSpec`, `ProbePolicy`, and `IpInfo` carry **zero `android.*` imports** (the real fetcher uses `java.net` only — `HttpURLConnection`, `Proxy`, `Authenticator`). All tests below are plain JVM tests in `app/src/test`.

### 7.1 Crossing the `IpProbe` interface

- **First-wins (the race):** two scripted fetchers — provider B answers at t+10ms with garbage JSON (parse → null), provider A answers at t+50ms with a valid `IpInfo`. Assert: exactly one `Result`, its value is A's, delivered ≈50ms after start (not 10s). A deterministic latency-control is exactly what the fake-fetcher adapter exists for.
- **All-providers-fail:** all four fetchers throw/serve null → one `FailedRound(attempt=1, classification=<fake classifier's answer>)`; assert the fake classifier was consulted exactly once per failed round.
- **Retry cadence + cap (fake clock):** fake scheduler, `ProbePolicy(retryDelayMs=500, maxAttempts=3)`, all fetchers failing → events `FailedRound(1)`, `FailedRound(2)`, `FailedRound(3)`, `Exhausted`, then **silence** until a new `start`. Advance the fake clock deterministically; assert inter-event intervals == 500ms.
- **Session semantics:** `Result` then silence (no post-result events, no self-restart): prove "at most one published value per start."
- **stop() mid-flight:** fetcher blocked on a `CountDownLatch`; call `stop()` from the test's "main" thread; release the fetcher → assert **no** `Result` ever delivered and no listener call after `stop()` returns; a subsequent `start` works.
- **Validation:** canned responses for each of the four production-parser paths (ip-api's `status!=success`, ipapi's missing alias keys, ipwho's `success:"false"`, freeipapi's empty `ipAddress`) → each yields a loser; winner logic still stands.
- **Threading:** with a scripted multi-response fetcher, record event order; assert strictly sequential main-thread delivery (no interleaving), even under a 10-provider fake where today's code would flake.

### 7.2 Crossing the `IpGate` interface

- offer(nonNull) → not published; `markReady()` → published exactly once with the held value.
- offer(A) then offer(B), then `markReady()` → published once, equals B (latest-wins).
- `markReady()` with nothing held → no callback, `isReady()==true`; subsequent offers pass through synchronously, **every** one (health loop not swallowed).
- offer(null) pre- and post-ready → never published, state unchanged.
- onPublished not yet registered → event lost (documents the ordering constraint).
- Concurrency test: feed offers+`markReady` from the single main thread in varied orders (markReady-before-offer, offer-hold-offer, null-null-hold, etc.) — a 6x3 combinatorial matrix, each asserting exactly-one/zero/pass-through counts. Doable in ~40 lines because the gate is a pure state latch.

### 7.3 Integration test: both modules together, owner in the middle

A fake `Scheduler` + fake `Fetcher` + fake `Classifier`, and a test "mini-owner" that replicates the service's actual dispatch rules from §9 (ignore failures while !ready; count strikes while ready; re-arm on 30s/5s; teardown at 3). Scripted timeline:

1. `start(quiet)` at t0 → fetcher answers t+100ms → `Result` → owner `gate.offer(info)` → **assert: not yet applied**.
2. owner calls `gate.markReady()` at t+150ms (as `postStartOnMain` would) → exactly one publish → owner `applyIpInfo` (recorder) → **assert applied after ready, never before** — the original bug, pinned in a test.
3. Failed round at t+5s → owner counts strike 1, re-arms single-shot; fake classifier "AUTH_FAILED"; fail twice more → owner teardown. Assert: exactly 3 strikes, teardown once, error-string choice correct.

This integration test crosses **both** interfaces plus the owner's own policy — the "interface is the test surface" rule applied to the whole seam.

**What cannot be tested this way (honest):** the 30s/5s/60s *real-time* re-arm scheduling inside the service, notification rendering, AIDL IPC. Those stay covered by the existing on-device run (screen-on/doze/rebind paths are environment behavior, not module behavior).

---

## 8. Honest verdict

### 8.1 Verdict on the assigned split

**Recommended with one honest correction: the producer half (IpProbe) is a genuinely deep module with a real seam — extract it, without hesitation. The consumer half (Gate) is a shallow latch with one consumer and a hypothetical seam — it is not a module in the skill's sense, and the two-module shape is the wrong final geometry.** Keeping IpProbe|Gate as two interfaces costs one full interface (6 invariants, a threading contract, registration-ordering rules) to own ~35 lines of latch logic. That is the shallow-module tax the skill tells you to refuse.

The corrected shape is not "Alt A" (fetcher-only — that leaves all the interesting complexity in the service) and not "Alt B" (full `TunnelSession` — over-extraction, §8.3). It is **Alt C′**: fold the gate into the probe, making the hold-then-release logic an *internal seam* of one deep module — exactly what DEEPENING.md prescribes ("merge the modules and test through the new interface directly"; internal seams are private to the implementation and used by its own tests). The tunnel keeps being invisible to the module: tunneness arrives only as a single `markReady()` **signal**, which the module interprets without knowing what a tunnel is.

### 8.2 The better alternative: `IpSession` (Alt C′)

```kotlin
class IpSession(
    private val exit: ExitSpec,                    // wired at session start
    private val fetcher: ProviderFetcher,          // internal seam: real / fake
    private val classifier: FailureClassifier,     // internal seam: SocksTester / fake
    private val scheduler: Scheduler,              // internal seam: real / fake clock
    private val listener: (IpSessionEvent) -> Unit // main thread, same guarantees as IpProbe
) {
    fun start(policy: ProbePolicy)   // quiet self-loop or single-shot
    fun markReady()                  // release held value exactly once
    fun stop()
}

sealed interface IpSessionEvent {
    data class Result(val info: IpInfo) : IpSessionEvent      // emitted ONLY when safe to apply:
                                                              //   immediately post-ready, or at markReady()
    data class FailedRound(val attempt: Int, val classification: ProxyProbe) : IpSessionEvent
    data object Exhausted : IpSessionEvent                     // quiet-loop cap hit; idle until restart
}
```

Same 3 entry points as IpProbe+IpGate combined (start/markReady/stop — identical count to IpProbe's 2 + Gate's markReady), but the buffer-hold, latest-wins, exactly-once-release, and pass-through rules live **inside**, untestable-to-forget. Event shape maps 1:1 onto the service's needs: `Result` (== the gate's publish — "emitted only when safe to apply" IS the invariant, now in the type), `FailedRound` (strike counting), `Exhausted` (idle). Deletion test: deleting `IpSession` re-scatters ~130 lines across two modules **and** the hold-invariant across three service sites — the strongest deletion test of the three candidates. It is deep: small interface, the same ~180 lines of behavior behind it.

**Trade-off, stated honestly:** the merged module is a slightly larger implementation than IpProbe alone, and its happy path ("Result arrives, markReady fires, both on main") is simple enough that a skeptical reader can call the gate logic a glorified ternary. That skepticism is *correct* — which is precisely why it must not be an interface: the ternary is the module's internal business, and the evidence it was easy to get wrong is the just-landed buffering commit itself.

### 8.3 Ranked recommendation

| Rank | Design | Verdict |
|---|---|---|
| **1** | **Alt C′ — `IpSession` (gate folded in)** | Deep, one interface to learn, one lifecycle to manage, all invariants internal. Recommended. |
| 2 | Alt C as assigned (IpProbe + Gate) | Producer half is right; gate half is a shallow module at a hypothetical seam. Acceptable if the team wants the latch independently unit-tested and anticipates a second consumer (e.g. StatusScreen subscribing directly) — that future consumer is the *only* thing that would turn Gate's seam real. |
| 3 | Alt B — full `TunnelSession` coordinator | Over-extraction: would absorb pdnsd/tun2socks spawn, sendfd polling, doze, and NetShield reconcile into one mega-coordinator. Those parts are already linear, localized (start() :686–836 is one method), and battle-tested; the coordinator interface would be large, and it violates the repo rule that engine code stays untouched by refactors. Deletion test would "pass" only by deleting most of the app. |
| 4 | Alt A — extract only the fetcher | Moves ~35 lines (connection/timeouts/auth) behind a seam while leaving the race, the parsers, the cadence, and the buffer lattice in the service. The seam is real only thanks to a test adapter, and the depth is minimal: the caller still learns the whole probe concept without getting any of its complexity removed. Locality unchanged. Worst ratio of migration cost to depth gained. (Its only virtue: smallest diff.) |

Bottom line, in skill vocabulary: **extract the deep producer; make the shallow latch internal; don't build the fetch-lattice outside the module.** Rank 1 — with rank 2 as the acceptable fallback if unit-testing the latch in isolation is valued more than interface economy.

---

## 9. Migration sketch (diff-level)

Target: Alt C′ (rank 1). The mapping table doubles as the as-assigned plan (differences noted inline).

### 9.1 New code — `net.typeblog.socks.ipcheck/`
- `IpSession.kt` — interface (above) + `ExitSpec` + `ProbePolicy`.
- `IpSessionImpl.kt` — **move** from `Utility.kt` (:284–424): the provider list + parsers, `fetchPublicIp` body (as `RealProviderFetcher`), CAS/latch race, round budget; add: classifier call per failed round, quiet retry loop via `Scheduler`, hold/release logic (from the service :232–238/:928–935), main-thread delivery.
- `IpSessionTest.kt`, `IpGateLogicTest.kt` (if the internal hold logic is worth a dedicated file), `IpSessionIntegrationTest.kt` — JVM tests per §7; needs `testImplementation("junit:junit:4.13.2")` added to `app/build.gradle` (currently absent).

### 9.2 `SocksVpnService.kt` — deletion + rewiring

| Current (line) | Becomes |
|---|---|
| `mIpCheckRunnable` :211–298 | **deleted**; branches re-homed per the table below |
| `mIpCheckHandler`/`mIpCheckFailures`-adjacent plumbing :154, :533 | `val session = IpSession(exit, listener=::onSessionEvent, …)` created in `onStartCommand`; counter resets stay |
| `mPendingIpInfo` :153, :535 | **deleted** (internal to session) |
| `mTunnelUp` :152, :534, :927 | **deleted**; service asks `session.markReady()` / tracks readiness itself (its own boolean is fine) |
| parallel fire :697 | `session.start(quietPolicy)` — same main-thread call site, same "before spawn thread" ordering |
| `!mTunnelUp` success buffer :230–238 | internal to session (hold); no service code |
| `!mTunnelUp` quiet retries :246–252, :287–290 | internal to session (`retryDelayMs=500`, `maxAttempts=60`); service's `onSessionEvent` early-returns on `FailedRound` while !ready |
| doze skip :217–220 | service-level: before each re-arm, if `isNetworkCheckBlocked()` delay 60s (quiet cap bounds the loop while tunnel spawns) |
| success-while-up :234–237 | `Result` → service applies (see below) + re-arm single-shot at 30s (`IP_CHECK_INTERVAL` stays) |
| probe-OK-failure :253–261 | `FailedRound(OK)` & ready → `mProxyVerified=true; updateNotification()`; re-arm at 500ms |
| probe-fail :262–281 | `FailedRound(≠OK)` & ready → strike++, teardown at 3 `(MAX_IP_CHECK_FAILURES)` with existing error strings; else re-arm 5s (`IP_CHECK_RETRY`) |
| exception branch :284–295 | folded into `FailedRound` semantics (module catches all fetch exceptions) |
| apply-buffered :928–935 | `postStartOnMain` now: `session.markReady()`; the held value arrives as `Result` → `applyIpInfo` + re-arm 30s. Exactly the same behavior, no buffer juggling |
| `applyIpInfo` :904–912 | unchanged (owner side effect: reconcileNetshield + notify) |
| screen-on re-arm :201–210 | unchanged intent: reset strikes, `session.stop()` + `session.start(policy)` (restarts loop) |
| constants :956–960 | `IP_INFO_RETRY` 500 → default of `ProbePolicy`; keep `IP_CHECK_INTERVAL`/`IP_CHECK_RETRY`/`MAX_IP_CHECK_FAILURES`/`DOZE_CHECK_INTERVAL` |

Key deletion-count: **~65 lines of service code removed** (the runnable + buffer lattice), replaced by ~15 lines of event dispatch; ~140 lines move from `Utility`/service-adjacent into the module, gaining unit tests.

### 9.3 `Utility.kt`
- Delete `checkPublicIp(…)` :284–378, `fetchPublicIp` :380–424, the provider list, and the dead no-arg `checkPublicIp()` :279–281 (verified: no callers).
- Keep `IpInfo` (pure data class, used by AIDL getters).

### 9.4 Untouched (must not be, per AGENTS.md)
`IVpnService.aidl`, `FloatingControlService.kt`, `VpnViewModel.kt`, all `ui/`, `ProfileManager.kt`, the native engines, `AndroidManifest.xml`. Per repo convention, the commit that lands this also updates the **AGENTS.md filesystem map** (`ipcheck/` package row).

### 9.5 Rollout order
1. `ipcheck/` package + tests (green on JVM).
2. Service rewiring; delete Utility race code.
3. Snapshot tag `pre-ipcheck-refactor` before step 1 (repo convention), CI build via `.github/workflows/build.yml` only.