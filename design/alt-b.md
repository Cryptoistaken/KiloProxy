# Alt B — `TunnelSession`: one deep module owning "start → ready → exit IP"

Design doc (no repo changes). Vocabulary per the `codebase-design` skill: **module**, **interface**, **seam**, **adapter**, **depth**, **leverage**, **locality**.

---

## 1. Module name + shape

**`net.typeblog.socks.engine.TunnelSession`** — a class in a new `engine` package (sibling of `util/`).

One paragraph: `TunnelSession` owns the entire arc from "user asked for a session" to "tunnel is up, exit IP is known, and stays known". Everything currently scattered through `SocksVpnService.kt` behind `start("/mIpCheckRunnable")` — pdnsd conf write + spawn, SOCKS hostname resolution (incl. IPv6-bracket logic), tun2socks cmdline construction + spawn + exit monitor, the sendfd poll, the in-parallel 4-provider IP race, the buffer-until-ready rule, the 30s/500ms/5s/60s retry ladder, the 3-strike teardown policy, doze gating, screen-on re-verify, and NetShield reconcile (country-driven pdnsd restart) — is one opaque behavior surface. The service constructs it with the established tun fd and a profile, calls `start()`, receives events on its main looper, and the module itself tears everything down (processes, pid files, fd, timers) on `stop(reason)` or on fatal failure. The caller sees a state machine (`CONNECTING → READY → FAILED`), an `Exit` enrichment stream, and two snapshot getters — nothing else. It is fully construtable with fake adapters, so it runs headless in JVM tests today and headless in a future no-UI mode tomorrow.

---

## 2. Complete Interface spec

```kotlin
// ---- net.typeblog.socks.engine ----

data class SessionConfig(
    val server: String, val port: Int,
    val username: String?, val password: String?,
    val dns: String, val dnsPort: Int,
    val ipv6: Boolean, val udpgw: String?,
    val profileName: String            // for logs/notify-derived text only, never engine logic
)

enum class Phase { STOPPED, CONNECTING, READY, STOPPING }

sealed class FatalReason {
    object PdnsdSpawnFailed
    object Tun2socksSpawnFailed
    data class Tun2socksExited(val code: Int)
    object FdHandoffTimeout               // 100 attempts x 50ms (=~5s cap, current FIX #1 behavior)
    object ConnectivityLost               // 3 consecutive post-READY probe failures
}

sealed class SessionEvent {
    object Connecting                              // spawn begun
    data class Ready(val connectedAt: Long, val exit: IpInfo?)  // tunnel up; at most once per start
    object Verified                                // proxy reachability confirmed (handshake OK or IP obtained)
    data class Exit(val info: IpInfo)              // exit IP/country known; may repeat (residential rotation)
    data class Failed(val reason: FatalReason, val message: String)  // terminal; engine already torn down
}

class TunnelSession private constructor(...) {
    companion object {
        fun create(
            context: Context,
            tunFd: ParcelFileDescriptor,   // established by VpnService; ownership transfers to the session
            config: SessionConfig,
            env: SessionEnv,               // adapter bundle (production or test) — see §4
            handler: Handler,              // event-delivery thread; main looper in the :vpn process
            listener: (SessionEvent) -> Unit
        ): TunnelSession
    }

    fun start()                    // returns immediately; CONNECTING → READY/FAILED arrive later on `handler`
    fun stop(reason: String = "user")  // idempotent; async; no event after it returns
    fun ping()                     // request an immediate health re-check (screen-on, UI refresh)
    val phase: Phase               // snapshot for binder re-sync after process restart
    val exit: IpInfo?              // last confirmed exit, same snapshot purpose
}

// ---- net.typeblog.socks.engine.internal ----
// SessionEnv: constructor bundle of adapters + clock + handler-thread executor + doze gate (see §4).
// All engine constants (IP_CHECK_INTERVAL=30000, IP_INFO_RETRY=500, IP_CHECK_RETRY=5000,
// DOZE_CHECK_INTERVAL=60000, MAX_IP_CHECK_FAILURES=3, sendfd 50msx100) live inside the module.
```

### Invariants

1. **Lifecycle.** `start()` twice, or `start()` after `stop()`, throws `IllegalStateException`. One session = one run.
2. **No event after stop.** Once `stop()` returns (or `Failed` is delivered), no further events are delivered. A `stopping` flag is checked on the delivery thread; in-flight worker results are discarded, not suppressed-then-delivered-late.
3. **Ordering.** First event is always `Connecting`. `Ready` at most once, `Failed` at most once and terminal. `Verified`/`Exit` never precede `Ready`. Nothing after `Failed`.
4. **Buffered IP never precedes READY** (the current `mPendingIpInfo` rule, made structural): an IP obtained pre-READY is *attached to* `Ready(exit = info)` — it is never emitted as an `Exit` event ahead of `Ready`.
5. **Spawn-phase single-shot + retry semantics preserved.** A successful pre-READY answer ends the spawn-phase poll loop (no further checks until READY arms the 30s cadence); a failed pre-READY answer keeps polling at 500ms until READY.
6. **Failure policy.** The 3-strike counter exists *only* post-READY. Pre-READY failures (tunnel still spawning) never count and never tear down.
7. **Cadence (post-READY).** Success → 30s. ip-api lookup fails but SOCKS handshake OK → not counted; `Verified`; 500ms retry. Handshake fails → counted, 5s retry → 3rd consecutive → `Failed(ConnectivityLost)` with the message mapped per probe class (auth failed / not SOCKS5 / refused / unreachable — existing strings preserved).
8. **Doze.** While screens-off/idle, checks are suspended with a 60s re-arm and accrue no strikes (`ping()` respects the same gate — this preserves the current screen-on "reset + immediate re-probe" behavior via the caller).
9. **Rotation.** `Exit` may repeat with a different IP/country; each country change triggers the internal NetShield reconcile (same rule as today: `netshieldPolicy(server, user, realCountry)` → conf rewrite → pdnsd restart, skipped if policy unchanged).
10. **fd ownership.** The session owns `tunFd` from `create()`: closes it on `stop()` and on `Failed`. The service never touches it again after handing it over.
11. **Threads (guarantees).** All events delivered on the injected `handler` thread, in order, never re-entrantly from blocking work. All blocking I/O (spawn, resolve, probes, race) on private daemon threads, bounded (probe pool ≤ 5 threads: 4 providers + 1 handshake probe). `start()`/`stop()`/`ping()` are safe from any thread and never block.

### Error modes — fatal vs recoverable

| Kind | Examples | Effect |
|---|---|---|
| **Fatal** (terminal, `Failed(reason, message)`, engine self-tears-down: kill processes, kill pid files, close fd, cancel timers) | pdnsd spawn threw; tun2socks spawn threw; tun2socks exited with code != 0 while running; sendfd timeout after 100×50ms; 3rd consecutive probe failure (ConnectivityLost) | Service reacts: set `mError`, `stopSelf`. |
| **Recoverable** (no event, internal retry) | ip-api/ipapi.co/ipwho.is/freeipapi all unreachable or timeout (10s race latch) while handshake OK; single probe failure (< 3); doze suspension; any pre-READY failure | Cadence ladder handles it; silent. |

### Configuration & performance

All tuning (intervals, strikes, poll attempt counts, provider URLs) is private constants — moving a knob is a one-line change in one file. Performance: the module's hot path is one scheduled runnable cadence + short-lived probe threads; no per-packet work (tun2socks is native, invisible to the module).

---

## 3. Seam placement

- **Processes.** The app UI runs in the default process; `SocksVpnService` runs in **`:vpn`** (manifest `android:process=":vpn"`). The *existing external seam* is the AIDL `IVpnService` interface between the UI process and `:vpn` (VpnViewModel polls it every 200ms for `isRunning`, `getCurrentIp`, `isProxyVerified`, `getErrorMessage`, stats, etc.).
- **New seam.** `TunnelSession`'s interface lives **inside `:vpn`**, constructed by `SocksVpnService` in `onStartCommand` on the service's main looper. It does **not** replace or duplicate the AIDL seam: `IVpnService` stays byte-for-byte unchanged; the service keeps mirroring session state into the same fields the 16 binder methods read. The UI never learns `TunnelSession` exists.
- **AIDL interaction.** The binder is the *output* surface (feeds `mCurrentIp`/`mCountryCode`/`mIpInfo`/`mConnectedSince`/`mError`/`mProxyVerified` from session events) and the *input* surface (`stop()` → `session.stop("binder_stop")`).

### What moves behind the seam (every engine member)

`mTun2socksProcess`, `mPdnsdProcess`, `mDns`, `mDnsPort`, `mNetshieldPolicy`, `mResolvedServer`, `mServer`, `mPort`, `mUsername`, `mPassword` (as `SessionConfig`), `mIpCheckFailures`, `mTunnelUp`, `mPendingIpInfo`, `mIpCheckHandler` + `mIpCheckRunnable` (whole ladder + mapping), `isNetworkCheckBlocked`, engine half of `mScreenOnReceiver` (→ `ping()`), `reconcileNetshield`, `applyIpInfo`, the error-string mapping, the spawn side of `start()` (pdnsd conf/spawn, resolve+brackets, cmdline, sendfd poll, exit monitor), the tunnel-up half of `postStartOnMain` (connectedAt, buffer flush, cadence arming), the engine half of `stopMe` (process destroy, `killPidFile`, fd close, timer cancels, resets), engine constants, and the NetShield prefs listener (module registers/unregisters it internally — it owns pdnsd, so it owns the live-reconcile reaction).

### What stays in the service (Android-service concerns only)

`VpnBinder` (16 methods — reads mirrored fields), `onStartCommand` intent parsing, **`configure()`/`establish()`** (VpnService.Builder + Routes + per-app — a VpnService-only API), notification channel/show/update (incl. `mLastNotificationText` dedup and the "Connecting..." / profile text derived from mirrored `mCurrentIp`), `mNotificationActionReceiver`, screen receivers *registration* + `mScreenOffReceiver` (auto-stop is a service policy decision), stats (`mStatsHandler`, `TrafficStats` baselines, `persistProfileBytes`), `mProfileName`/`mRunning`/`mProxyVerified`/`mConnectedSince`/`mError`/IP mirrors, `loadProfileBytes`/`persistProfileBytes`, the slimmed `stopMe` (persist → `session.stop(reason)` → unregister → `stopSelf()`).

---

## 4. Adapters (behind the seam, via the `SessionEnv` bundle)

Honest double-check per the **"one adapter = hypothetical seam, two adapters = real seam"** rule — every adapter below names its second adapter, so no production-only indirection:

| Adapter | Production | Test (second adapter) |
|---|---|---|
| `ProcessSpawner` | `ProcessBuilder` spawn of `libpdnsd.so` / `libtun2socks.so` from `nativeLibraryDir`, stream drain, `waitFor` | `FakeSpawner`: records argv, completes/exit-codes on demand, no processes |
| `SocksProbe` | `SocksTester.probeProxy` (raw SOCKS5 handshake) | `FakeProbe`: scripted `ProxyProbe` sequence (fail×3, fail-then-ok, always-ok…) |
| `IpProviderRace` | the 4-provider `Utility.checkPublicIp` race (latch, 10s) | `FakeRace`: immediate answer, answer-after-n-calls, or never (null) |
| `FdHandoff` | `System.sendfd(fd)` poll, 50ms×100 | `FakeHandoff`: succeeds on attempt N, or always −1 |
| `DozeGate` | `PowerManager.isInteractive` + `isDeviceIdleMode` | `FakeGate`: fixed screensOff/doze state |
| `Clock` | `System.currentTimeMillis` | `FakeClock`: manual advance (drives cadence/doze tests deterministically) |

Two honest caveats: (a) the clock/doze seams exist *because* their test fakes are needed to make the ladder deterministic — that is a real second adapter, not decoration; (b) **the AIDL seam is reused, not duplicated** — we deliberately do *not* add a parallel session seam for the UI, and we do *not* move `configure()`/`establish()` (VpnService Builder API cannot live outside the service subclass).

---

## 5. Depth analysis

**Hidden behavior (~370 lines today) behind a ~6-type interface:**
- spawn orchestration ~120 (pdnsd conf/spawn ~25, resolve + IPv6 brackets ~15, tun2socks cmdline (password redaction incl.) ~40, sendfd poll ~20, exit monitor ~15)
- health/race/buffer ~150 (the whole `mIpCheckRunnable` ladder ~90, buffering/flush ~15, `applyIpInfo` + reconcile triggers ~10, cadence plumbing ~35)
- NetShield country-reconcile ~45, teardown ~40

**Interface size:** constructor + 3 methods + 2 getters + 5 event types + 5 fatal reasons. A caller learns a 4-state machine and one rule ("domain questions are answered by events, never by peeking").

**Leverage.** The same interface serves (1) `SocksVpnService` as a thin client — its engine half shrinks to an event handler that mirrors state; (2) **JVM tests** — the whole fail/retry/buffer policy is exercisable off-device, which is impossible today; (3) a **future headless mode** (BootReceiver-driven or a Worker) that constructs a session without any Activity/bubble; (4) per-ABI/dev fake-proxy tooling built on the same fakes. One implementation, N callers, M tests.

**Locality.** Every timing, failure, and buffering rule concentrates in one file. Examples: adding a 5th IP provider = one change in `IpProviderRace`; changing the retry ladder or strike count = one constant; fixing the pipelined "probe + race in flight simultaneously" behavior = one internal optimization, invisible to the service.

**Depth verdict:** deep — a large amount of behavior per unit of interface learned; the interface is smaller than the *list of fields it removes* from the service.

---

## 6. Deletion test

Delete `TunnelSession`:
- All ~370 lines of spawn/buffer/ladder/reconcile/teardown logic reappears — interleaved back into `SocksVpnService`, which re-bloats from ~420 to 964 lines.
- The JVM test suite (8–10 order/failure/rotation tests, §7) dies at once — the surface they test ("no events after stop", "buffered never precedes READY", "pre-READY failures uncounted") cannot be re-asserted anywhere.
- Headless mode is gone; every future consumer would need to re-implement spawn+ladder inside itself.

Complexity does not vanish with the module; it resurfaces across every caller. The module earns its keep.

---

## 7. Testability (the interface is the test surface)

Tests construct `TunnelSession.create(context, fakeFd, config, testEnv, handler, listener)` with all fakes (§4), run Robolectric (for `Context`/`filesDir`/prefs) or plain JVM, and assert **only on the recorded event stream**:

| Scenario | Fake setup | Assertion |
|---|---|---|
| Happy path | handoff OK on 1st, race answers during spawn | `[Connecting, Ready(exit=info)]` — buffered IP **attached to Ready, never pre-emitted** |
| Buffer-until-ready ordering | race answers immediately, spawner completes slowly | no `Exit` before `Ready`; `Ready(exit=info)` exactly once; cadence armed |
| Spawn-phase no-count | fake probe fails 10× while spawning | still `Connecting`; no strikes, no `Failed`; then `Ready` |
| 3-strike teardown | post-READY probe scripted fail,fail,fail; fake clock +5s between | `Failed(ConnectivityLost)` with auth/refused/unreachable message variant; no further events |
| 2-strike recovery | fail, fail, ok | no `Failed`; counter reset; next failure starts from 1 |
| Healthy proxy, dead lookup | race → null forever, probe → OK | `Verified`, no strikes, 500ms fast-retry (clock-advanced), never `Failed` |
| Rotation | race returns IP-A then IP-B | `Exit` twice, two `Ready`? no — one `Ready`, `Exit` twice; `FakeSpawner` sees pdnsd restarted on country change |
| Doze | gate = doze; 3+ probe ticks pass | zero strikes, zero events, 60s re-arm; `ping()` while dozed is ignored |
| Fatal spawn | spawner throws on pdnsd | `Failed(PdnsdSpawnFailed)`; fake fd closed; no events after |
| No events after stop | start, stop mid-race | events after `stop()` returns = none; `stop()` twice = no-op |

`SocksProbe`/`IpProviderRace` fakes mean **no real sockets, no real network**; `FakeSpawner` means **no real processes or `.so` files**; `FakeHandoff` means **no real tunnel fd**. The only Android touchpoints are `Context` (Robolectric) and the prefs listener (Robolectric prefs). All ordering tests are deterministic — no sleeps.

---

## 8. Internal seams (private to the module, used by its own tests)

- The adapter interfaces themselves (`ProcessSpawner`, `SocksProbe`, `IpProviderRace`, `FdHandoff`, `DozeGate`, `Clock`) — package-visible only, deliberately **not** part of the public constructor signature beyond the opaque `SessionEnv` bundle.
- An internal **pure state machine** (phase + strike counter + pending-exit + armed-timers), no I/O — unit-testable in isolation as a function `State × Result → (State, List<Event>, Deadline?)`.
- A private **schedule/timer** built on the injected `handler` (single runnable re-post pattern, replacing `mIpCheckHandler`).
- A private **`SpawnPlan`** builder (cmdline/brackets logic) — pure string/args function, tested without spawning.

Rule per the vocabulary: these stay private; tests touch them only where the public interface can't express a race deterministically, and everything observable is re-asserted through `session.events`.

---

## 9. Migration sketch

**End state — `SocksVpnService.kt` shrinks from 964 lines to ~400–450:**
- `VpnBinder` ~120 (unchanged text), `onStartCommand` ~75 (intent parse + configure + session create/start), `configure()` ~65, notification + receivers ~95, stats ~85, slim `stopMe` ~40, **`onSessionEvent()` ~60** (the only new glue: maps `Ready/Failed/Verified/Exit` onto mirrored fields, notification, `stopSelf("proxy_connect_failed")` for `ConnectivityLost`), `postStartOnMain` slims to stats baseline + receiver registration ~25.
- New files: `engine/TunnelSession.kt` (~420), `engine/internal/SessionEnv + adapters + state machine` (~260), JVM+Robolectric tests `engine/TunnelSessionTest.kt`.

**Step order:** (1) snapshot tag `pre-tunnel-session` per AGENTS.md; (2) extract members into the session **mechanically first** (same behavior, no event redesign) and green the CI build; (3) switch the service to the event mirror + `ping()`; (4) land the parity test suite (event-log replay of the scenarios in §7) *before* deleting any old behavior paths.

**What breaks / touches:**
- **AIDL: unchanged.** All 16 `IVpnService` methods keep identical signatures and semantics (VpnViewModel, bubble, notification all unaffected).
- `Utility.checkPublicIp` / `SocksTester` / `Routes` remain as-is — now called by the session instead of the service.
- Comment markers relocate: FIX #1 (sendfd poll) and FIX #5 (IPv6 brackets) move into `SpawnPlan`; FIX #2/#3 (connected-at-tunnel-up, post-start in background) become the `Ready` event + service mirror.
- The NetShield prefs listener moves out of the service into the session (it owns pdnsd).
- `mResolvedServer` disappears from the service (the resolved host now feeds the probe dedup inside the session).
- CI: builds stay on `.github/workflows/build.yml` (repo rule — never local); new JVM tests run in the same workflow.
- Main risk: **behavioral drift in a big-bang move of working engine code** — mitigated by the snapshot tag, the mechanical-extraction-first order, and the parity suite.

---

## 10. Honest drawbacks vs the status quo

1. **Indirection cost.** Every engine answer now flows through a one-way event loop + mirrored fields instead of direct field writes. Roughly +150 lines of glue and two snapshot getters exist solely for binder-resync after `:vpn` restarts — real, if small, ongoing cost.
2. **Event-loop re-architecture.** Today the mIpCheckRunnable writes fields synchronously on whichever thread finished; the redesign funnels everything through one ordered dispatcher. Ordering bugs are the *exact* class the tests target, but the rewrite itself is where they'd be introduced.
3. **Big-bang risk of working code.** This is battle-tested engine behavior (FIX #1–#5 accumulated over real failures: sendfd ramp, buffer races, doze strike-storms). Moving ~370 lines wholesale risks regressions that tests must earn back; the migration must be staged, not a single night's rewrite.
4. **Single production consumer.** Today only `SocksVpnService` calls it. The depth argument leans on the test surface and a speculative headless mode; if neither ships, this is expensive indirection (the two-adapters rule is satisfied by test fakes, but production still has one caller).
5. **fd ownership handover.** `ParcelFileDescriptor` lifecycle crosses a class boundary (service establishes, session closes). A missed close path is a resource leak invisible in unit tests; must be covered by a Robolectric-level test and preserved in `stopMe`'s failure paths.
6. **Android-context leakage into a "pure" module.** filesDir, prefs, PowerManager, and nativeLibraryDir still pin the session to Android — it is not a plain JVM module, which slightly weakens the "headless" story (Robolectric required) and the purity argument in §5.
7. **Duplication risk during migration.** Until the parity suite lands, two codepaths (old service logic vs new session) can coexist in the tree — developers must not "fix" the old one while the session ships.