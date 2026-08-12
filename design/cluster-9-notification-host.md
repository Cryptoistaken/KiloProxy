# Cluster 9 — Notifications, FGS & Boot → one deep module: `NotificationHost`

Status-quo audit, radical redesign, interface spec, seams, adapters, depth analysis, deletion test, testability, migration, honest drawbacks. No repo modifications — design only.

---

## 0. Status-quo audit (what the code actually does today)

Sources: `SocksVpnService.kt` (964 lines), `FloatingControlService.kt` (1258 lines), `BootReceiver.kt`, `AndroidManifest.xml`, `notification_action.xml`, `Constants.kt`, `MainActivity.kt`.

### 0.1 The notification is already "shared" — by accident of duplicate constants

- `CHANNEL_ID = "floating_control"` and `NOTIFICATION_ID = 2` are each defined **twice** (SocksVpnService.kt:954-955, FloatingControlService.kt:1239-1240). The single-notification reality exists only because both files happen to hard-code the same pair.
- `createNotificationChannel()` is duplicated (SocksVpnService.kt:334, FloatingControlService.kt:686). The bubble's copy additionally sets `setShowBadge(false)`; the engine's does not — the channel is created twice with differing config; last creator wins.
- Both services call `startForeground(2, notif, FOREGROUND_SERVICE_TYPE_SPECIAL_USE)` on API 34+ (SocksVpnService.kt:587, FloatingControlService.kt:702) — the version branch is duplicated.
- Both maintain private dedup state: `mLastNotificationText`/`mLastNotificationActions` (SocksVpnService.kt:158-159, used at 613-617) and `lastNotificationText`/`lastNotificationState` (FloatingControlService.kt:84-85, used at 714-717). Same idea, different keys, both reimplemented.

### 0.2 Dual-writer on one notification id

`SocksVpnService` runs in the `:vpn` process, `FloatingControlService` in the main process. When both are alive, **both write notification id 2**:

- Engine: `showNotification()` (startForeground, plain builder, no actions) then `updateNotification()` on IP-check results (plain `notify(2, …)`, no pill, SocksVpnService.kt:602-607).
- Bubble: 200 ms `pollRunnable` → `updateForegroundNotification()` (FloatingControlService.kt:708-718) → `notify(2, …)` with a standard `.addAction()` pill built from `ACTION_START_VPN`/`ACTION_STOP_VPN`.

Last writer wins. Because the bubble polls at 200 ms and the engine only renders on events, the bubble effectively owns the content whenever it is alive. This **works today only by polling luck** — the arbitration rule is unwritten, and no code prevents a future third writer from colliding on id 2.

### 0.3 "NotificationActionReceiver" is not a manifest class — it's two dynamic receivers

There is **no** manifest-declared `NotificationActionReceiver`. Either action broadcast is caught by:

- `mNotificationActionReceiver` inside `SocksVpnService` (registered with `registerReceiverCompat` / RECEIVER_NOT_EXPORTED at :390, unregistered at :539) — handles `ACTION_STOP_VPN` → `stopMe("notification_stop")`.
- `actionReceiver` inside `FloatingControlService` (:167-180) — handles `ACTION_STOP_VPN` → `stopVpn()` **and** `ACTION_START_VPN` → `startVpn()`.

Both processes therefore receive the **same** package-scoped `ACTION_STOP_VPN` broadcast; the engine tears down and the bubble concurrently requests a stop through the AIDL binder. Safe only because `stopMe`/`stopVpn` are guarded (`mRunning`). The `registerReceiverCompat` API-level dance (API 33 `RECEIVER_NOT_EXPORTED` vs older 4-arg overload, SocksVpnService.kt:439-445) is written twice.

### 0.4 The DETACH rule (stopMe, SocksVpnService.kt:462-551)

```
stopForeground(STOP_FOREGROUND_DETACH)   // API 34+  → FGS ended, notification 2 stays in shade
stopForeground(false)                     // API 24–33 → FGS ended, notification 2 REMOVED
```

On API 34 the disconnected notification **lingers** (showing whatever content last won the dual-writer race — possibly stale "Connected"); on API < 34 it is removed outright. When the bubble is alive its 200 ms poll re-posts id 2 within 200 ms either way, papering over the divergence. This API-dependent keep-vs-remove rule is exactly the kind of platform subtlety a deep module should own once.

### 0.5 POST_NOTIFICATIONS is requested in three places, handled nowhere

`MainActivity.startFloatingControlIfPersisted` (:43-53) launches the permission request when floating control is enabled; `SettingsScreen` (:263-267, :335-339) and `StatusScreen` (:280-283) have rationale rows. **Nothing reads the result.** If denied, Android simply hides the FGS notification and everything keeps working — which is the correct degrade — but it's implicit, undocumented, and untested. The bubble does not even require the permission (overlay works without it), so denial is a legitimate, common configuration the module must treat as a first-class state.

### 0.6 Boot: there is NO "was connected" flag today

Audit result for `auto_start`/`boot`/`was` keys: none exist. `BootReceiver.onReceive` (:18-38) decides entirely from **desired-state prefs**:

- `p.autoConnect()` — per-profile pref key `"auto"` (Profile.kt:129-135; PREF_ADV_AUTO_CONNECT = `"adv_auto_connect"` is a red herring — the profile key is simply `auto`), AND `VpnService.prepare(context) == null` → `Utility.startVpn(context, p)`.
- `PREF_FLOATING_CONTROL` (`"floating_control"`) AND overlay permission → `FloatingControlService.start(context)`.

`PREF_AUTO_STOP` (`"auto_stop"`, Constants.kt:44) is unrelated — it's the screen-off auto-stop preference. "Restore what was running when the device shut down" is *not* current behavior; the current behavior is "restore what the user *wants* to run always". Whoever owns the boot decision must be told this honestly (see §8.5).

### 0.7 `notification_action.xml` is dead code

The comments at FloatingControlService.kt:757-760 state plainly: the custom RemoteViews layout "rendered as an EMPTY notification row on some devices", so the current code uses a **standard template + `addAction`**. Neither `buildForegroundNotification` nor either engine builder references `notification_action.xml`, `notification_pill`, or the `notify_button`/`notify_text` ids. The "RemoteViews pill" described in AGENTS.md matches the current file only in name. This matters for the Renderer seam verdict in §7.

### 0.8 Test infrastructure

`app/src/test` does not exist — there are **zero tests** in the repo today. Any testability claim for the new module must include the first-time cost of standing up a JVM test source set.

---

## 1. Module shape

**One deep module: `NotificationHost`.** All notification-and-foreground concerns of the app collapse into it: channel creation, the single-notification invariant, the FGS start/stop duality (DETACH-vs-REMOVE), idempotent rendering with dedup, action-receiver registration/compat, the permission-denied degrade, and the persisted boot state.

One **shared instance** of the interface; the module is instantiated **per process** (`:vpn` and main each construct their own host bound to their own `Service`/`NotificationManager`). System-level exclusivity (only one notification id 2, one channel) is enforced by the module **owning those constants** — neither service ever mentions `2` or `"floating_control"` again. Callers learn a tiny value type and five verbs; the module hides ~400 lines of platform rules behind it.

```
        :vpn process                                  main process
┌────────────────────────────┐            ┌──────────────────────────────┐
│ SocksVpnService            │            │ FloatingControlService        │
│  └─ NotificationHost ──────┤            │ BootReceiver ──┐              │
│      startForeground()     │            │ MainActivity ─┼─ NotificationHost
│      render()              │            │               └───────────────┤
│      stopForeground()      │            │   startForeground/render/     │
│      onAction()            │            │   stopForeground/onAction/    │
│      destroy()             │            │   maybeRestoreAfterBoot()     │
└───────────┬────────────────┘            └───────────────┬───────────────┘
            │ NotificationSink (external seam)            │
            ▼                                              ▼
   NotificationManager + Service        NotificationManager + Service
   (id 2, channel "floating_control" — constants private to the module)
```

Callers shrink to:

- `SocksVpnService`: `host.startForeground(connectingState)` on start; `host.render(state)` on every state change (ip info, errors, proxy verification); `host.stopForeground(detach = sdk >= 34, keepShown = false)` + `host.destroy()` in `stopMe`. The engine's own `showNotification`/`updateNotification`/`createNotificationChannel`/receiver blocks (~70 lines) are deleted.
- `FloatingControlService`: `host.startForeground(idleState)` in `onCreate`; `host.render(state)` inside the existing 200 ms poll and `setState`; `host.destroy()` on teardown. Its own notification block (~85 lines) is deleted.
- `BootReceiver`: becomes a thin adapter — `host.maybeRestoreAfterBoot(BootInput(...))` then executes the returned decision with existing `Utility.startVpn` / `FloatingControlService.start`. (~25 lines deleted.)
- `MainActivity`: keeps only the overlay-start + permission **request** (that is an activity permission flow, not a notification concern); deletes its `createNotificationChannel`-adjacent assumptions — it had none, so it only shrinks by coupling.

Dependency classification (DEEPENING.md): the Android framework (`NotificationManager`, `Service.startForeground`, `PendingIntent`, `SharedPreferences`) is **category 4 — true external**. The module takes the system dependencies as an injected port (`NotificationSink`); production uses a framework adapter, tests use a recording adapter. SharedPreferences is an in-process/local-substitutable dependency — injected directly; tests use an in-memory fake.

## 2. Complete Interface spec

The interface is everything a caller must know: types, invariants, ordering, errors, config, performance.

```kotlin
// ── value types ──────────────────────────────────────────────────────────

enum class Pill { CONNECT, DISCONNECT, NONE }

data class NotifState(
    val connected: Boolean,        // false while connecting/disconnected
    val connecting: Boolean,       // drives progress nudging; not the same as !connected
    val title: String,             // caller-resolved string (e.g. "KiloProxy")
    val body: String,              // "Connecting...", "🇯🇵 Japan · 1.2.3.4", "Not connected"
    val pill: Pill,                // CONNECT / DISCONNECT / NONE
    val progress: Boolean = false  // indeterminate progress indicator
)

data class RenderedNotif(          // pure data — no android.* types
    val title: String, val body: String,
    val pill: Pill, val progress: Boolean, val ongoing: Boolean,
    val channelId: String, val notificationId: Int      // always the module's constants
)

data class BootInput(val autoConnectVpn: Boolean, val overlayEnabled: Boolean)
data class BootDecision(val restoreVpn: Boolean, val restoreOverlay: Boolean)

sealed class HostAction {
    data object StopVpn : HostAction()
    data object StartVpn : HostAction()
}

// ── module ───────────────────────────────────────────────────────────────

class NotificationHost(
    private val sink: NotificationSink,      // external seam — injected port
    private val prefs: SharedPreferences,    // boot-flag persistence (in-memory fake in tests)
    private val sdk: Int = Build.VERSION.SDK_INT,
    callbacks: NotificationHostCallbacks = NotificationHostCallbacks()
) {
    fun startForeground(state: NotifState)
    fun render(state: NotifState)
    fun stopForeground(detach: Boolean, keepShown: Boolean)
    fun onAction(callback: (HostAction) -> Unit)   // registers the receiver; idempotent
    fun destroy()                                   // unregisters receiver, clears dedup
    fun maybeRestoreAfterBoot(boot: BootInput): BootDecision
}
```

### 2.1 Invariants (facts a caller may rely on)

1. **Single-notification guarantee (by construction).** `channelId`/`notificationId`/`channelName`/`importance`/`badge`/`smallIcon` live in private `HostConfig` inside the module. No path into the interface accepts an id or channel — a second notification is *unrepresentable* through callers. The module is the only code in the app that may call `NotificationManager` or `startForeground` (enforced by the deletion test / grep).
2. **Idempotent render.** `render(s)` with a state equal to the last rendered state is a strict no-op: no `notify`, no PendingIntent rebuild, no SharedPreferences write. Equality is `NotifState` data-class equality — this subsumes today's three hand-rolled dedup fields.
3. **FGS phase machine is the only way the notification is born or dies.** No caller may touch foreground state outside `startForeground`/`stopForeground`.
4. **Cross-process content arbitration is last-writer-wins with convergent shape.** Both host instances render the same shape (same renderer, same channel/id) from their own observed state; the system keeps the freshest post. The module guarantees *shape convergence*, not *state agreement* — two processes observe different truth (see drawbacks §8.2).
5. **`maybeRestoreAfterBoot` is pure** (prefs read + boolean math; never starts services) — the caller executes the decision. Side-effect-free → directly testable.

### 2.2 Ordering constraints

- `render()` is legal in **every** phase. If called before `startForeground` it is *deferred* — recorded as pending state (flags are still persisted on transitions) and emitted on the next `startForeground`. No exception path exists; either call order works.
- `startForeground(state)` may be called at most once per phase and re-enters `FOREGROUND` from `DETACHED`/`REMOVED`. Calling it twice back-to-back is a no-op on the second call.
- `stopForeground(detach, keepShown)` is only meaningful in `FOREGROUND`/`DETACHED`; in `REMOTED` it's a no-op wall clock. The engine's 5-second `startForeground` window after `startForegroundService` is a **system** deadline, not enforced here (callers run `startForeground` synchronously in their start path, as both do today).
- `onAction(cb)` before `startForeground` is legal (BootReceiver-style flows); `destroy()` after `destroy()` is idempotent.
- The boot flag is written on **transitions only** (see §9.3): a render crossing `connected=false → true` persists `true`; a render or stop crossing `true → false` persists `false`. Same-state renders never write. A process crash therefore leaves the last persisted value (usually `true` if it died connected — intended restore semantics).

### 2.3 Error modes

| Condition | Behavior |
|---|---|
| `startForeground` throws `ForegroundServiceStartNotAllowedException` / `SecurityException` | **Propagates to the caller.** The engine's existing `stopMe(reason)` path is the natural handler; the host does not swallow — silent teardown would hide the reason. |
| `update()` throws `SecurityException` while `sink.areNotificationsEnabled() == false` | **Swallowed** (documented degrade): permission-denied services keep foregrounding without visible content. |
| `notify` fails for any other reason | Propogates to the caller (engine logs + stops, matching today's `stopMe("start_failed")` habits). |
| Receiver registration on API 33+ without flags | Impossible by construction — the host owns the `RECEIVER_NOT_EXPORTED`/legacy-overload branch (today duplicated in both services). |
| `stopForeground(detach=true, keepShown=true)` on API < 34 | No DETACH exists; the host **synthesizes** it: `stopForeground(true)` (remove) then re-`update()` the last state so the notification reappears as a plain one. This is the one piece of platform magic callers never see. |

### 2.4 Required configuration (module-owned, invisible to callers)

- Channel: `id="floating_control"`, name "Floating Control", `IMPORTANCE_LOW`, `setShowBadge(false)` (merges the engine's and bubble's divergent channel setups; badge-off, last-creator-wins bug gone).
- Notification id `2`, small icon `ic_launcher`, `ongoing=true` for connected/connecting states.
- Actions: package-scoped `ACTION_START_VPN` / `ACTION_STOP_VPN` broadcasts with `FLAG_IMMUTABLE | FLAG_UPDATE_CURRENT` PendingIntents (request codes 1/2 — relocated from FloatingControlService.kt:721-730).
- Two processes → two instances → the channel is created twice, but now **identically**, so the duplicate-create is harmless.

### 2.5 Performance characteristics

- `render` is allocation-light: builds one `RenderedNotif` only when it will be emitted; the equality compare short-circuits before any notification construction.
- Called at the bubble's 200 ms cadence and the engine's event cadence with no batching requirement; the dedup makes the hot poll path free.
- Must be called from the main thread (as today); the engine's `applyIpInfo`/`postStartOnMain` already funnel through main-thread handlers.

## 3. Seam placement

The **external seam** sits between the callers and the module — one seam, crossed by the two services, the boot receiver, and tests. Behind it lives platform glue + rules.

The **`NotificationSink` port** is a *second* seam *inside* the module (external seam vs internal seams — DEEPENING.md): the host's logic (phases, dedup, permission rules, boot flag) must not touch `android.app.*` types directly, or it becomes untestable on the JVM. The sink is the module's dependency port:

```kotlin
interface NotificationSink {
    fun startForeground(notif: RenderedNotif)          // platform: Service.startForeground(id, notif, specialUse)
    fun update(notif: RenderedNotif)                    // platform: NotificationManager.notify(id, notif)
    fun stopForeground(remove: Boolean)                 // platform: STOP_FOREGROUND_DETACH/REMOVE / legacy stopForeground(false)
    fun areNotificationsEnabled(): Boolean              // platform: NotificationManagerCompat.areNotificationsEnabled
}
```

The module computes *what* to do (a pure state machine over `NotifState` → `RenderedNotif` → sink calls); the sink performs it against the framework.

## 4. Adapters

**At the external seam (the module's interface):** callers themselves — `SocksVpnService`, `FloatingControlService`, `BootReceiver`, `MainActivity` — plus the test suite as a fourth caller. One interface, four consumers (and the tests). This is the highest-leverage consumption pattern in the cluster.

**At the `NotificationSink` port — two adapters make it a real seam:**

1. **`FrameworkNotificationSink` (production)** — wraps `NotificationCompat.Builder` (standard template + `addAction`, not RemoteViews — see §7) and the host `Service`. Maps `RenderedNotif` → `Notification`.
2. **`RecordingNotificationSink` (tests)** — records a script of `(startForeground/update/stopForeground(remove), RenderedNotif)` calls, plus a settable `areNotificationsEnabled`.

Two adapters, one variation (framework vs recorded), so the port is justified per the one-vs-two rule. The `SharedPreferences` dependency is local-substitutable: inject a real in-memory fake in tests, no adapter needed.

**One-adapter honesty check:** the *Renderer* (`NotifState → RenderedNotif`) is a pure function inside the module — not an injected port at all. It does not get an adapter. See §7.

## 5. Depth analysis

**Interface surface a caller must learn:** the `NotifState` value type (5 fields + enum), five verbs, the `Pill`/`HostAction` enums, `BootInput`/`BootDecision` pair. Roughly one page.

**Behaviour behind it (all previously caller-borne):**

1. Per-API FGS start (`START_FOREGROUND_SERVICE_SPECIAL_USE` vs plain) — duplicated today across SocksVpnService.kt:586-590 and FloatingControlService.kt:701-705.
2. The DETACH-vs-REMOVE rule with the API < 34 synthesis (§2.3) — currently a one-line branch inside `stopMe` that silently diverges from the bubble's behavior.
3. Idempotent rendering + dedup (three hand-rolled fields across two processes replaced by one value-equality compare).
4. Channel creation with merged `badge=false` config (two divergent creators today).
5. Action receiver registration + API-33 compat + lifecycle (duplicated: SocksVpnService.kt:306-315, 439-445; FloatingControlService.kt:167-180).
6. Permission-denied degrade policy (implicit today, now a codified, tested rule).
7. Boot-flag persistence (write-on-transition, crash-safe) + `maybeRestoreAfterBoot` decision math.
8. The single-notification invariant (constants + "module is the only writer" structural rule) — *the* cluster invariant, today enforced by two files happening to agree.

Callers go from ~180 lines of interleaved platform code (two copies) to 4–6 one-liners each. The module is deep: a caller exercising every interface verb gets phases, dedup, permission policy, channel config, and boot semantics for the price of learning one value type. **Locality:** every notification bug in the app now has exactly one file to check; the DETACH divergence and the channel-config double-create exist today precisely because the knowledge was spread across two processes.

## 6. Deletion test

Delete `NotificationHost` and its sink adapters. What reappears?

- `SocksVpnService` regrows: channel creation, per-API `startForeground`, dedup state + compare, receiver registration/unregistration with the API-33 dance, `stopMe`'s DETACH branch — and a new temptation to give the engine its own notification id when someone wants connect/disconnect on the engine-only path.
- `FloatingControlService` regrows the same ~85 lines again, plus the `ACTION_START_VPN` receiver half.
- `BootReceiver` regrows the boot-decision logic + prefs coupling.
- The single-notification invariant reverts to "two files agree on 2 by convention" — exactly the state we found it in, where a one-line edit in one file silently fractures the whole notification system.

Four callers re-implementing one platform problem, each diverging slightly (they already have: badge config, action sets, dedup keys). Complexity does **not** vanish on deletion — it disperses. The module earns its keep.

## 7. Internal seams — the honest verdict on the Renderer

The spec (`render(state)`) calls for "the RemoteViews template behind an internal Renderer seam". **Two-adapter test applied to today's codebase: the seam is NOT real.**

- The one existing template, `notification_action.xml`, is **dead code** (§0.7). Both services render with the standard notification template + `addAction`. There is exactly one renderer in production.
- Introducing a `NotifRenderer` port with one production adapter and one test adapter would be indirection bought on credit — the test adapter exists only because the port was invented for it. That's a hypothetical seam. Verdict: **do not create a public Renderer port.**

What the design actually needs:

- A pure `NotifState → RenderedNotif` **function** (`internal`), exercised directly by JVM tests. This is not a seam — it's the module's own implementation, tested at the module's test surface.
- The `RenderedNotif` pure-data type is what keeps `NotificationSink` (and thus the entire host) Android-free on the test JVM. The framework adapter converts `RenderedNotif → Notification` at the sink, the one place `android.*` types are legal.
- The dead `notification_action.xml` is deleted in migration (§9.4). If a future device-specific issue resurrects RemoteViews (the pill rendering the empty-row bug that prompted the switch), a **second renderer appears and the seam becomes real** — that is the moment to introduce the port, not before.

**Internal seams that ARE real:** the phase machine and dedup are pure state, unit-testable without seams; the framework boundary is a real seam with two adapters (production + recording); everything else is by-value function calls.

## 8. Testability

The host is designed so its entire rule surface runs on a plain JVM: tests inject a `RecordingNotificationSink`, an in-memory `SharedPreferences`, and a fake `sdk` — no Robolectric required for the core.

### 8.1 Idempotent render (recorded calls)

```kotlin
val sink = RecordingNotificationSink()
val host = NotificationHost(sink, inMemoryPrefs(), sdk = 34)

val a = NotifState(true, false, "KiloProxy", "🇯🇵 Japan · 1.2.3.4", Pill.DISCONNECT)
host.startForeground(a)
host.render(a)            // equal → no-op
host.render(a.copy(body = "🇩🇪 Germany · 5.6.7.8"))   // changed → one update
host.render(a.copy(body = "🇩🇪 Germany · 5.6.7.8"))   // equal again → no-op

assertEquals(1, sink.updates.size)                 // 1 update for 3 renders
assertEquals("🇩🇪 Germany · 5.6.7.8", sink.rendered(0).body)
```

This replaces the dedup regression class that previously lived in two files with a 5-line assertion.

### 8.2 FGS phase machine (state-machine table tests)

`sdk` is a constructor param — the DETACH rule is tested under both API regimes:

| Test | sdk | Start sequence | Expected recorded calls |
|---|---|---|---|
| detach-34 | 34 | `startForeground(s)` → `stopForeground(true, true)` | `startForeground`, `stopForeground(remove=false)` — notification stays |
| detach-33-synthesis | 33 | `startForeground(s)` → `stopForeground(true, true)` | `startForeground`, `stopForeground(remove=true)`, `update(s)` (re-post) |
| remove | 34 | `startForeground(s)` → `stopForeground(false, false)` | `startForeground`, `stopForeground(remove=true)` |
| deferred render | 34 | `render(s)` → `startForeground(s)` | `startForeground` (deferred state flushes) |
| permission-denied | 34 | `sink.denyNotifications = true`; `startForeground(s)` → `render(s2)` | `startForeground` still called; `update` **never** — degrade is foreground-only |
| double-stop | 34 | full stop twice | second `stopForeground` is a no-op (idempotent) |

### 8.3 Boot flag + decision (transition-only writes)

```kotlin
host.render(connected)                        // flag → true
host.render(disconnected)                     // transition → flag → false
host.render(disconnected)                     // same-state → no write (idempotent persistence)

host.maybeRestoreAfterBoot(BootInput(true, true))  == BootDecision(true, true)
host.maybeRestoreAfterBoot(BootInput(false, true)) == BootDecision(false, true)
```

Crash-safety: the flag is only written on transitions, so a killed, connected process leaves `true` and boot restores.

### 8.4 Single-notification invariant

Testable by construction (no id/channel parameters exist), plus a sink-level assertion: every recorded `RenderedNotif` carries `notificationId == 2 && channelId == "floating_control"`. And a repo-level grep in CI: no `NotificationManager` / `startForeground` outside the module permits the regression drift that produced today's duplicate constants.

### 8.5 First-time cost, said plainly

The repo has **no test source set today**. Standing one up (JUnit + truth/assertk or plain JUnit asserts, in-memory prefs) is part of the migration's cost — and is the first meaningful test foothold the project gets. Robolectric is *not* required for the core; optionally add it later to smoke-test `FrameworkNotificationSink` (builder → Notification round-trip), presented as an explicit follow-up, not part of the module's definition of done.

## 9. Migration sketch (CI-only builds, tag discipline per AGENTS.md)

Respect the repo rules: build only via `.github/workflows/build.yml`, tag `pre-notif-and-dot-fixes` before starting, update AGENTS.md filesystem map in the same commit.

1. **Phases 0–1: skeleton in the main process (no behavior change).** Add `NotificationHost` + `NotificationSink` + adapters + pure renderer in `net/typeblog/socks/notif/`. Migrate `FloatingControlService` (its `onCreate` → `startForeground`, poll/`setState` → `render`, teardown → `destroy`), `BootReceiver` (decision through `maybeRestoreAfterBoot`, same inputs as today's prefs reads → identical behavior), and `MainActivity` (unchanged semantics). Push, CI-build, install over (`adb install -r`, keep data). The bubble is now the module's first consumer; the engine still runs its old code untouched — dual-writer continues exactly as today, since both still write id 2 with the same shape.
2. **Phase 2: migrate `SocksVpnService` (`:vpn` process).** Replace `createNotificationChannel`/`showNotification`/`updateNotification`/`mNotificationActionReceiver`/the `stopForeground` branch with `host.startForeground` / `host.render` / `host.stopForeground(detach = sdk >= 34, keepShown = false)` / `host.destroy()`. Delete the three dedup fields and `registerReceiverCompat`. **Behavior delta to approve:** the engine's notification now also carries the pill (previously plain, actionless) — the two processes render identical shapes, which is the point of the invariant; still an observable change on the engine-only path.
3. **Phase 3: land the tests.** JVM test source set + the table in §8. CI `build.yml` gains `./gradlew test` — or keeps build-only and tests run locally; AGENTS.md must say which.
4. **Phase 4: cleanup.** Delete `notification_action.xml` (dead RemoteViews; resurrect with a second renderer adapter only if a real device issue returns — §7). Collapse `Constants.ACTION_STOP_VPN/ACTION_START_VPN` ownership into the module (strings stay in `Constants.kt`, single writer). Update AGENTS.md filesystem map + snapshot table.
5. **Phase 5 (optional, opt-in product decision): boot semantics.** Ship `maybeRestoreAfterBoot` reading the persisted `was_connected` flag (§8.5) — "restore what was running" instead of "restore what autoConnects". The interface already supports it; the flag wiring is one boolean in `BootInput`. Recommend deferring so Phase 1–2 remain pure refactors.

## 10. Honest drawbacks

1. **Two processes, two instances — the invariant is structural, not runtime-enforced cross-process.** The module guarantees *shape convergence* (identical renderer, same id/channel) and *single ownership of constants*; it cannot stop a future process from writing id 2 behind its back. Enforcement beyond construction is grep + the deletion test. The dual-writer arbitration (bubble's 200 ms poll wins when both run) is *codified*, not *fixed*: the module centralizes the mechanics but does not create a cross-process state source of truth — the bubble and engine still derive "connected" from different observations (AIDL poll vs local engine state), so content can disagree momentarily with no module-level reconciliation.
2. **Behavior deltas on the engine-only path.** The engine's notification gains the pill (not previously present). That is the design working as intended, but it is a visible change that Phase 2 must ship as a deliberate feature, not a side effect. The boot-flag phase changes shutdown/reboot semantics unless deferred.
3. **Platform-magic synthesis (§2.3) is new.** `stopForeground(detach=true, keepShown=true)` on API < 34 (remove + re-post) does not exist today (the engine just removes; the bubble never calls stopForeground). The synthesis is *better*, but it is untested-in-the-wild behavior born in this refactor — a reason the phase-machine tests in §8.2 are mandatory, not nice-to-have.
4. **First test infrastructure in a zero-test repo.** New tooling (test source set, dependency, CI wiring decision) is real cost and new convention the maintainer must own. The promise — the DETACH rule and permission degrade becoming regression-proof — only pays if Phases 3+ actually land.
5. **Ceremony risk.** Five verbs + a sink port for two services and a receiver is defensible, but it is a port-and-adapter shape where a skeptical onlooker sees a wrapper around `NotificationManager`. The depth is real only because the *rules* (DETACH synthesis, degrade policy, idempotence, boot flag, invariant) live behind it — if later refactors let callers bypass the host "just for one thing", the module rots into the pass-through the deletion test would expose.
6. **The Renderer seam is not real (§7).** One production template exists; the doc does not add a port for it. Anyone expecting the "RemoteViews template with two adapters" from the brief will find a plain function instead — the honest answer to the one-vs-two-adapter test is that the second adapter does not exist until a real device regression justifies it.
7. **Boot flag ownership is a single-writer/single-reader pair inside one module**, which is right, but it couples the *notification* module to *session lifecycle* semantics — the flag is really engine state that the host happens to observe through render transitions. The coupling is contained and documented (§2.2), but the flag could equally (and arguably more honestly) live at the engine's `stopMe` boundary; the host holding it means BootReceiver, MainActivity, and both services never read or write it — the strongest locality argument for keeping it here.