# Alt A — `IpInfoFetcher`: the IP/country probe as one deep module

Vocabulary per `codebase-design` (DEEPENING.md / DESIGN-IT-TWICE.md): Module, Interface,
Implementation, Seam, Adapter, Depth, Leverage, Locality, deletion test, "interface is the
test surface", "one adapter = hypothetical seam, two adapters = real seam".

---

## 1. Module name + shape

**`net.typeblog.socks.util.IpInfoFetcher`** (new file `util/IpInfoFetcher.kt`, pure JVM —
no Android dependencies), owning `IpInfo` (moved here from `Utility.kt:46`).

The interface is one synchronous, blocking, nullable call:

```kotlin
data class ExitSpec(val server: String?, val port: Int, val username: String?, val password: String?)

class IpInfoFetcher {
    fun fetch(exit: ExitSpec): IpInfo?   // null = "no provider answered"
}
```

Everything about *where the exit IP comes from* hides behind that call: the 4-provider
race (ip-api.com, ipapi.co, ipwho.is, free.freeipapi.com), each provider's schema-shim
parse lambda, per-provider validation (status/empty-ip checks), SOCKS5 proxy routing with
credentials, the process-global `Authenticator` wart, 3 s connect + 3 s read timeouts,
first-non-null-wins orchestration, and the 10 s overall budget. `null` server in
`ExitSpec` = direct (no proxy) mode, which preserves the legacy no-arg behavior without a
second entry point. Everything the service already owns — *when* to poll, buffering until
tunnel-up, retry cadence, failure counting, notification updates, `reconcileNetshield` —
stays in `SocksVpnService` untouched.

---

## 2. Complete interface spec

### Methods

| Member | Type | Notes |
|---|---|---|
| `ExitSpec` | data class `(server: String?, port: Int, username: String?, password: String?)` | `server == null` → direct connection (no proxy). `server != null` + `port <= 0` → caller error (see invariants). |
| `IpInfo` | data class `(ip: String, countryCode: String, country, regionName, city, isp, org, asName, timezone: String = "")` | Moved verbatim from `Utility.kt:46`. Structurally unchanged, so AIDL flattening and `reconcileNetshield` need zero edits. |
| `fetch(exit: ExitSpec): IpInfo?` | only entry point | Synchronous; blocks the calling thread until done. |

Constructor takes only configuration defaults (see Configuration); there is **no** mock
port, no interface type, no `IpInfoSource` abstraction at the external seam.

### Invariants

- `fetch` is total on its inputs: any `ExitSpec` (including `server = null`) returns `null`
  or a valid `IpInfo`; it never throws.
- `ip` and `countryCode` are non-empty in a non-null result (all four parsers gate on this
  today); the other fields may be `""`.
- `fetch` is safe to call concurrently (no shared mutable state except the one-time
  `Authenticator` pin described below) and is idempotent — each call is an independent
  probe.
- A non-null result is produced by **exactly one** provider; later successes are discarded
  (`AtomicReference.compareAndSet`). The winning provider is not reported — the interface
  deliberately hides it (no `Source` field added to `IpInfo`; it would be a second answer
  nobody consumes).

### Ordering / state constraints

- Provider **result ordering is unspecified by design** — first-non-null-wins is the whole
  semantics; tests must assert on *which* result wins under controlled timing, never on
  call order.
- No state persists across calls: no cached `IpInfo`, no retry state, no failure counts.
  Retry cadence, buffering (`mPendingIpInfo`) and `MAX_IP_CHECK_FAILURES` remain the
  service's job — the module is stateless between calls.

### Error modes

- **All failure collapses to `null`**: proxy unreachable, SOCKS handshake fail, DNS fail,
  HTTP timeout, non-200, malformed JSON, schema mismatch, validation failure. This mirrors
  today's `catch (e: Exception) { null }` (`Utility.kt:418`) but as a *contract*, not an
  accident — the one surviving caller distinguishes "lookup failed" from "proxy dead" via
  `SocksTester.probeProxy` (`SocksVpnService.kt:244`), which is orthogonal.
- Internal worker threads never escape (each `fetchPublicIp` try/catch is retained).
- A failed provider is skipped; remaining providers continue until the budget expires.

### Configuration

Internal-ish, but spec'd now: `connectTimeoutMs = 3000`, `readTimeoutMs = 3000`
(per provider, `Utility.kt:407-408`), `overallBudgetMs = 10000` (the latch await,
`Utility.kt:376`), `providers: List<Provider>` where

```kotlin
internal data class Provider(val url: String, val parse: (JSONObject) -> IpInfo?)
```

Defaults reproduce today's list exactly (4 entries). All are constructor parameters so
the module's own tests can shrink the list, shrink budgets, or point URLs at a local
server — **without** polluting the external interface. These are implementation knobs;
the interface promises only `null`-vs-`IpInfo` within the configured budget.

### Performance characteristics

- **Worst-case wall latency: ~10 s** (all providers fail, budget expires). Typical success:
  0.2–3 s (fastest provider). Note the honest math: 3 s + 3 s per provider, four in
  parallel ⇒ the *dominant* term is the 10 s latch budget, not the per-provider timeouts.
- **Threading model**: one caller thread blocked; up to 4 short-lived worker threads
  spawned per call (`Thread { }` per provider, as today) — acceptable at the current
  30 s cadence (`IP_CHECK_INTERVAL`), no shared executor, no thread pool ownership.
- **Blocking guarantee**: `fetch` *blocks its caller* up to the budget. The module does
  **not** add a thread hop — `SocksVpnService` already calls it from a raw background
  `Thread` (`SocksVpnService.kt:227`); preserving synchronous blocking is the point, so
  the service's buffering logic needs no async rework.
- No retries, no connection pooling, no cancellation of in-flight providers once a winner
  is found (a winner just stops being *recorded*; stragglers burn their remaining timeout
  then die) — same economics as today, now documented.

---

## 3. Seam placement

The seam sits exactly where it already sits: the **util ↔ service boundary**, at the
static call `Utility.checkPublicIp`. The design converts a function-level seam into a
type-level seam — it does not move it, and it does not widen it to the UI.

**Every caller of `checkPublicIp`, with cross-seam changes:**

| Call site | Today | After |
|---|---|---|
| `SocksVpnService.kt:229` — `Utility.checkPublicIp(server, port, username, password)` inside `mIpCheckRunnable` | static call, resolved IP (`mResolvedServer ?: mServer`, line 223), wrapped in the runnable's own try/catch | `fetcher.fetch(ExitSpec(server, port, username, password))` — one line. The runnable's surrounding try/catch can be deleted wholesale (null-contract now guarantees non-throw); everything else in the runnable (buffer-else-apply, probe, failure counting, retry scheduling, lines 230–295) is unchanged. |
| `Utility.kt:279-281` — no-arg `checkPublicIp()` overload | **zero in-repo callers** (grep: only the definition matches) | Deleted. Its behavior is preserved as `ExitSpec(null, 0, null, null)` = direct mode, so nothing is lost. |
| `SocksVpnService.kt:46` — `import net.typeblog.socks.util.IpInfo` | — | Repoints to the new file; `IpInfo` type stays in the same package. |

**Non-crossing consumers (read `IpInfo`/derived state, unaffected):** AIDL getters
(`SocksVpnService.kt:63-97`) read `mIpInfo` fields; `applyIpInfo` (`:904-912`) writes
`mCurrentIp`/`mCountryCode`/`mIpInfo` and fans out to `reconcileNetshield()`
(`:884-902`, consumes `mCountryCode` only) and `updateNotification()`; buffering
`mPendingIpInfo` + `postStartOnMain` (`:914-946`); UI consumers get flattened strings
over AIDL — `FloatingControlService.kt:445-461` (pill), `:738-739`, `:836`;
`VpnViewModel.kt:231` → `StatusScreen.kt:64/321` / `ConnectionStatusCard.kt:34-78`.
None of these change — the interface is carved out *below* them.

So the seam is crossed by exactly **one production call site** (plus the deletion of one
dead overload). It's a narrow seam — that's the honest cost we price in §9.

---

## 4. Adapters — the honest two-adapter test

Two candidate seams, both fail the real-seam test:

1. **The anonymous `Authenticator` (`Utility.kt:400-404`)** — one adapter. It adapts the
   Java HTTP stack's process-global auth hook to our SOCKS credentials. Per
   DEEPENING.md: *one adapter = hypothetical seam, two adapters = real seam*. There is no
   second implementer of "supply proxy credentials", and none is wanted — this stays an
   implementation detail *inside* the module, not a port. (It's also a genuine wart:
   `Authenticator.setDefault` is process-global mutable state, set per call. Moving it
   into the module lets us pin it **once** to an immutable shared `Authenticator`,
   turning a latent race into a deterministic, single-owner side effect.)
2. **The 4-provider list (`Utility.kt:287-363`)** — this is a **configuration list**
   (URL + parse-shim per schema), not a strategy seam. All four share one transport, one
   orchestration, one validation policy; their differences are just field-name fallback
   chains (`Utility.kt:312-313`, `:339`, `:358-359`) and nested-object unwrapping
   (`:328-330`). An `IpProvider` interface ("fetch exit IP") would be a fake seam with a
   single production implementation dressed as four — both create and cross it in the
   same file.

Real-seam verdict: **no external port needed.** The interface stays one concrete class.
The only true seam in the design is the *transport* (HTTP-over-SOCKS vs in-memory fake),
and that belongs **inside** the module, below the interface, for its own tests only
(§7, §8) — per DEEPENING.md's internal-seams rule: don't expose internal seams through
the interface just because tests use them.

---

## 5. Depth analysis

**What hides behind `fetch(exit): IpInfo?`:**

- 4 provider URLs + the per-schema JSON parsing: ip-api's `status != "success"` gate
  (`Utility.kt:289-291`), ipapi.co's dual-key fallbacks `country_code`/`countryCode`
  (`:312-313`), ipwho.is's `success == "false"` gate and nested `connection`/`timezone`
  objects (`:325-341`), freeipapi's `ipAddress`/`asnOrganization`/`timeZones[0]`
  (`:344-360`), plus the `"AS"`-prefix normalization shared by two parsers (`:339`, `:358`).
- Validation collapse rules (empty-ip, status-key) → provider null.
- SOCKS5 routing: `Proxy(Proxy.Type.SOCKS, InetSocketAddress(server, port))`, conditional
  credential attachment, direct-vs-proxy branch (`Utility.kt:391-405`).
- Concurrency: thread-per-provider, `AtomicReference.compareAndSet` first-wins,
  `CountDownLatch` with 10 s budget, exception suppression per worker, straggler threads.
- Timeout policy: 3 s connect / 3 s read per provider, applied to every request.
- The `Authenticator.setDefault` global-state wart, now singleton-pinned.

That is ~140 lines of I/O-bound, failure-heavy, environment-sensitive logic with a
one-word answer. **Depth: high** — the interface inverts the difficulty: every failure
mode, every schema drift, every timeout tweak is *cheaper than the call*.

**What remains visible to callers:** a nullable struct; no threading, no timing, no
provider knowledge. The "knob count" at the seam is 1.

**Leverage** — the honest picture. Today there is exactly **one** production caller
(`SocksVpnService.kt:229`); the no-arg overload has zero. Leverage over *current* call
sites is therefore 1: instead of "many callers, one deep module", this is "one caller,
one deep module". The leverage is real but shifted: it buys (a) a **test surface** that
does not exist today — the interface becomes the test surface, per DEEPENING.md; (b) a
latent seam for future consumers that want the same answer (a "test this proxy" button,
a diagnostics/debug screen, reusing the race for `ProxyCard` country prefill — all
currently impossible because the logic is private to `Utility`); (c) deletion of the dead
overload + `org.json` parser code being able to live with its producer (testability needs
this co-location, since `parse` lambdas are the chargeable part).

**Locality.** Four classes of future fixes concentrate in one file:
1. Add/remove/deprioritize a provider (one list append + one parse shim);
2. A single provider's schema drift (one parse lambda — no caller change);
3. Timeout/budget tuning (three constructor defaults);
4. Proxy auth changes (one file, not the service).

Today those same fixes are already in one file (`Utility.kt`) — locality *within the
codebase* barely moves. What improves is locality *of the test surface* and the
module-environment boundary (the global `Authenticator`).

---

## 6. Deletion test

Delete `IpInfoFetcher` and ask: does the complexity vanish, or reappear?

It reappears — verbatim — at the single call site: `mIpCheckRunnable` would have to
re-grow its own thread-per-provider spawn, `AtomicReference`/`CountDownLatch` race, and
the four `(URL, parse)` pairs inline (or as private methods of the service), plus the
`Authenticator` pin and timeout policy. Because there is only one caller, the deletion
test passes in the weak sense (complexity doesn't *scatter across many callers*), but
fails the strong sense (the module is not a pass-through — the logic genuinely lives in
it, so it would all come back in `SocksVpnService`). That is *exactly* the property that
makes this a real module rather than an indirection layer: the module is its
implementation, and the caller is thin.

The reverse half of the test — deleting the *callsite's* plumbing — also passes: the
runnable's try/catch and its hand-rolled threading wrapper (`SocksVpnService.kt:227-296`)
shrink, and the dead `checkPublicIp()` overload disappears with no caller reaction. Every
restore path is exercised by the tag `pre-netshield` / `pre-ui-redesign` snapshots, so
the diff is recoverable either way.

---

## 7. Testability — the interface is the test surface

`IpInfoFetcher` is pure JVM (`java.net`, `org.json`) — unit-testable without Robolectric
for the orchestration; `org.json` needs Robolectric's implementation (or the Android
instrumented runner) for the parse layer, since local JVM tests get the stub. Tests sit
at `fetch`, per DEEPENING.md ("tests assert observable outcomes through the interface").

What tests look like:

- **Fixed-provider adapter (in-memory fake transport):** instantiate `IpInfoFetcher`
  with `providers = [Provider("http://127.0.0.1:PORT/…", parse)]` against a local
  `com.sun.net.httpserver.HttpServer` serving canned JSON per provider — no SOCKS, no
  real internet. The honest "adapter" in the test suite is the **transport seam** (§8):
  a fake `Transport` that returns canned bytes/throws instantly, making tests hermetic.
- **First-wins concurrency test:** two providers, provider A sleeps 50 ms then returns a
  well-formed payload, provider B returns immediately with a different IP; assert the
  result equals B's and only one provider's payload is observed as winning. Repeat with
  reversed timing to prove order-independence-by-design, not by accident.
- **Timeout test:** budget `overallBudgetMs = 150`, transport sleeps 10 s (or throws
  `SocketTimeoutException` after 3 s) ⇒ `fetch` returns `null` and *wall time* stays
  under the budget; asserts blocking semantics rather than elapsed-thread-count.
- **Null/validation tests:** malformed JSON, empty `ip`, ip-api `status: "fail"`,
  ipwho `success: "false"`, `AS`-prefix normalization (returns `AS-asn` both when the
  provider supplies the prefix and when it doesn't), all-fail ⇒ `null`.
- **Direct-vs-proxy test:** `ExitSpec(null, 0, null, null)` must hit the fake transport
  without a proxy step (transport seam records the proxy argument it was given).

No test touches `SocksVpnService`; retry cadence and buffering remain guarded by the
existing on-device behavior, which this design does not alter.

---

## 8. Internal seams (private, for the module's own tests)

Per DEEPENING.md, internal seams are allowed below the interface:

- `internal interface Transport { fun open(exit: ExitSpec, url: String): HttpURLConnection }`
  — production impl wraps `URL.openConnection(proxy)` + `Authenticator` pin (the *one*
  adapter, §4); test impl returns a connection wired to a canned reader or throws
  `SocketTimeoutException`. This is the only seam the tests cross, and it's invisible in
  the public contract.
- `internal constructor()` overloads taking `providers`, `connectTimeoutMs`,
  `readTimeoutMs`, `overallBudgetMs` — the primary public constructor always yields
  production defaults.
- `internal fun parse…` per-provider lambdas = pure data→`IpInfo?` functions, unit-testable
  with a minimal `JSONObject` shim if org.json is unavailable in the JVM test context.

The external interface stays `fetch(ExitSpec): IpInfo?` + `ExitSpec` + `IpInfo` — nothing
else is public.

---

## 9. Honest drawbacks vs the status quo

- **Indirection:** `Utility.checkPublicIp` (static, zero-implementation) becomes an
  instance class the service must hold (one field or a companion-shared singleton) and a
  new file for ~140 moved lines. The diff is real but mechanical; the import at
  `SocksVpnService.kt:46` changes.
- **Thread hops: none added.** The module is synchronous-blocking, so the service's
  threading model is untouched — but this also means we forgo the chance to make the API
  async (a `suspend` version would be a different interface and is explicitly out of
  scope for Alt A).
- **State visibility:** the `Authenticator.setDefault` side effect stays global; the gain
  is that it becomes single-owner and deterministic (pinned once) instead of
  re-set per call. No other state moves — `mIpInfo`/`mPendingIpInfo` remain service-side,
  and `IpInfo` fields crossing AIDL are byte-identical.
- **The status quo is arguably already deep.** Honest read: `checkPublicIp` +
  `fetchPublicIp` already *is* a shallow-but-real module boundary at function granularity;
  the provider list already lives in one place; the service already owns timing.
  Extraction buys mostly **testability** (nonexistent today — this race has never been
  testable in 140 lines of private code), the deletion of a dead overload, and the
  contraction of the global-side-effect surface. It does *not* buy a second consumer.
  So: recommend it as a **testability + hygiene** extraction, not a restructuring —
  and only if the test surface gets used; otherwise it's a neutral move of code from one
  util file to another.

---

## Verdict

`IpInfoFetcher` passes the deletion test (real logic enclosed, thin caller), honestly
fails the two-adapter test at the interface (no external port justified — provider list
is configuration, `Authenticator` is one adapter and stays internal), and its depth is
high with leverage concentrated in **testability + future consumers** rather than
plural current call sites. It is a correct, defensible Alt A: modest, honest, and it
leaves the service's buffering/retry/teardown state machine completely untouched.