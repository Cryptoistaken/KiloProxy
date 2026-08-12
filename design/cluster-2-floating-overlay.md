# Cluster 2 — Floating Overlay: StatusFeed / OverlaySurface

Design doc (no code changes). Vocabulary: Module, Interface (invariants/ordering/errors/config/perf), Adapter, Seam, Depth, Leverage, Locality, deletion test, one-vs-two adapters — per `DEEPENING.md` / `DESIGN-IT-TWICE.md`.

Analyzed: `FloatingControlService.kt` (1258 L), `BubbleMenuOverlay.kt` (600 L), `notification_action.xml`, `Constants.kt` usage (id 2, `floating_control` channel, `ACTION_START_VPN`/`ACTION_STOP_VPN`).

---

## 1. Problem frame — what varies

The service does two very different jobs glued into one class, and it reads as a web of handlers:

| Concern | Currently lives in | What makes it vary |
|---|---|---|
| Connectedness state machine, proxy-verified gating, 20 s timeout, flicker-avoidance rules | 130 L of `pollState`/`setState` | VPN lifecycle in a **cross-process** service (`:vpn`), time, binder death |
| AIDL bind/rebind, escalating backoff, dead-binder detection | `serviceConnection`, `scheduleRebind`, poll-exception heuristic | Process lifecycle; never gives up |
| 200 ms poll + 1 s timer cadences | `pollRunnable`, `timerRunnable`, 3 Handlers | Time |
| Country/flag/IP extraction, `supportsCountrySwitch`, username rewriting, previous-country memory | `updateFlagPill`, `onBubbleCountrySelected`, `openBubbleMenu` | Profile/provider catalog, **shared by notification too** |
| Window lifetimes, drag+clamp, inset math (2 API paths), pill-follows-bubble math, config-change re-clamp | `createBubbleView`, drag listener, `currentDragBounds`, `updateFlagPillPosition` | Android window system, density, rotation, overlay permission |
| Gesture segmentation (tap/double-tap/long-press/drag, slop, 480 ms / 300 ms windows) | `createTouchListener`, `handleTap` | Touch input; pure logic |
| Animation choreography (gradient crossfade, breathing, connect-pop, squeeze) | 6 animators | Presentation |
| Notification (id 2) — text from state+IP+country, Connect/Disconnect button, string-dedup hack | `buildForegroundNotification`, `lastNotificationText/State` | A **second consumer of the same state** |

The natural seam: **questions about the VPN (and its answers) vs. questions about windows.** The notification shares the first, never the second. The passenger train analogy from the codebase-design vocabulary applies: today `pollRunnable` drags UI updates behind it and `setState` reaches into both bubble and notification — every change to either side re-runs the other's code.

---

## 2. Design at a glance

```
                     ┌─────────────────────────────────────────┐
                     │  FloatingControlService (HOST, thin)    │
                     │  lifecycle shell + broadcast receiver   │
                     └──────┬──────────────────────┬───────────┘
                            │                      │
              diff subscribe│                 events→commands
                            ▼                      │
   ┌──────────────────────────────┐      ┌─────────▼───────────────────────┐
   │ StatusFeed (publisher, deep) │      │ OverlaySurface (renderer, deep) │
   │ snapshot()/observe(diff)/    │      │ open/close/render/showMenu +    │
   │ connect/disconnect/switch…   │      │ events (Tap/DoubleTap/LongPress)│
   │                              │      │  │ owns BubbleMenuOverlay,      │
   │  internal: AIDL bind/rebind, │      │  │ drag/clamp/insets/anims      │
   │  poll, timeout, timer,       │      └──────────┬──────────────────────┘
   │  country rotation            │                 │
   └──────────────┬───────────────┘                 │
                  │ same port (second adapter)      │
       ┌──────────▼──────────────┐                  │
       │ NotificationSurface     │                  │
       │ OverlayState → notif id2│                  │
       └─────────────────────────┘                  │
```

Three deployable units today: **StatusFeed** owns every state question the overlay can ask; **OverlaySurface** owns every window/layout/interaction answer; **the host** is the only place that knows both, keeping them decoupled from each other. **NotificationSurface** is a sibling consumer of the feed — this sibling is what makes the feed seam real (two adapters, §8).

---

## 3. Module 1 — `StatusFeed` (interface + value types)

### 3.1 Port (external seam)

```kotlin
interface StatusFeed : AutoCloseable {
    // -- Query ----------
    val state: OverlayState                     // consistent snapshot, never null

    // -- Subscribe (diff-based) --------------
    fun observe(observer: (OverlayDiff) -> Unit): AutoCloseable
    // close() unregisters; feed itself is independent of how many observers exist.

    // -- Commands (async, fire-and-forget; effects reflected via diffs)
    fun connect()                               // or pause on VPN-permission screen
    fun disconnect()                            // request stop, set DISCONNECTED
    fun switchCountry(code: String)             // rewrite username, remember prev, restart-if-connected
    fun switchToPreviousCountry()               // double-tap target: prev ?, random from catalog
    fun refresh()                               // force an immediate poll (post-bind/config change)
}
```

```kotlin
enum class ConnectionState { DISCONNECTED, CONNECTING, CONNECTED }

// Null-free value type: every "no answer" is a typed empty/0 sentinel.
data class OverlayState(
    val connection: ConnectionState,
    val flag: String,            // "" = unknown        (country-code → flag already derived)
    val countryCode: String,     // "" = unknown
    val countryName: String,     // "" = unknown
    val ip: String,              // "" = unknown
    val connectedSince: Long,    // 0 = not connected   (epoch ms)
    val elapsedMillis: Long,     // 0 = n/a              (derived from connectedSince at snapshot time)
    val countrySwitchable: Boolean,                    // provider type != CUSTOM
    val canConnect: Boolean,     // ≥1 real profile exists (not just Default placeholder)
    val profileName: String      // "" = unknown
)

sealed interface OverlayDiff {
    data class Connection(val connection: ConnectionState) : OverlayDiff
    data class Endpoint(val flag: String, val countryCode: String, val countryName: String, val ip: String) : OverlayDiff
    data class Timer(val elapsedMillis: Long) : OverlayDiff
    data class Capabilities(val countrySwitchable: Boolean, val canConnect: Boolean, val profileName: String) : OverlayDiff
}
```

### 3.2 Invariants

1. **Single writer.** Only the feed mutates `OverlayState`; host and surfaces are readers. No renderer ever calls AIDL or `ProfileManager`.
2. **Sticky CONNECTING.** Once CONNECTING, the feed never emits `Connection(DISCONNECTED)` spontaneously before the 20 s timeout or a user `disconnect()` (preserves the spin→shield flicker rule, today at L1010–1016). CONNECTED requires `isRunning && connectedSince > 0 && proxyVerified` (L999–1004); tunnel-up alone is never CONNECTED.
3. **CONNECTED latch with fallback glyph.** `connectedSince == 0` while CONNECTED is legal (stop-glyph state, L1070–1075); `Timer` diffs are suppressed then.
4. **Timeout is armed on *any* entry to CONNECTING** — from `connect()`, from `refresh()` promotion, from permission-return (`pendingProfile`) — and disarmed on every exit (L1036–1044).
5. **Snapshot consistency.** `state` is read-only for consumers; a snapshot taken between diffs is internally consistent (never country=B while flag=A).

### 3.3 Ordering

- `observe` registration → immediately replay nothing; a `feed.refresh()` (or the in-flight poll) emits the first diff within ≤200 ms.
- Command → effect ordering: `connect()` emits `Connection(CONNECTING)` synchronously **before** any bind/poll work (so the UI never lags a visual frame behind intent) — except when the placeholder-profile guard fires, where `connect()` emits a `Capabilities(canConnect=false)` diff instead of a toast (toast becomes host's job: host maps `canConnect=false` results into the current Toast — actually the feed can't toast; see §7 wiring note).
- Rebind: on binder loss the feed announces nothing except through state it can no longer read; it re-emits `Connection(DISCONNECTED)` only when it can no longer prove otherwise (today L1025–1027), and always `refresh()`es after a landed rebind so the latch recovers.
- `switchToPreviousCountry` when nothing remembered → catalog-random (today `listOf("DE","DZ","FR","CI").random()`) — deterministic default optional via config.

### 3.4 Errors

- **Binder death** (explicit `onServiceDisconnected` or poll throwing): internal, non-fatal. Feed nulls the gateway, freezes spinner, schedules rebind forever on backoff 200→1000→3000 ms (attempt ≤3, ≤10, else), guarded by `rebindInFlight`. No error surface — the API is all quiet diff streams, exactly like today's self-healing behavior.
- `connect()` with no real profiles → no state change, diff `Capabilities(false)`; host shows the "No proxy configured yet" toast (the feed must not toast — keep it UI-free).
- `switchCountry` on unsupported provider (CUSTOM / unparseable username) → no-op diff; host/host-triggered menu gets `countrySwitchable=false` so the menu already disables the row.

### 3.5 Config (constructor-injectable, defaulted to today's constants)

`pollInterval=200`, `timerInterval=1000`, `connectTimeout=20_000`, rebind backoff table `(200,1000,3000)`, timeout → force `DISCONNECTED` behavior, random-catalog seed.

### 3.6 Perf

- One poll cycle = one gateway call trio (`isRunning`, `connectedSince`, `isProxyVerified`) plus two reads (`countryCode`, `currentIp`, `country`) only when CONNECTED and only when the previous diff implies staleness — no field re-read just to satisfy the dedup hack; **the diff stream replaces `lastNotificationText/State`**.
- `Timer(elapsed)` ticks are the only 1 s emissions and are suppressed when not CONNECTED-with-`connectedSince`.
- Observers are batched identically per diff (one iteration, no per-observer re-derivation).

### 3.7 What hides behind the seam

Cross-process bind/rebind policy, poll loop, the verification-gated state machine, timeout escape latch, permission-suspense (`pendingProfile` + `VpnService.prepare()` wait), timer epoch derivations, country/flag/IP extraction, `supportsCountrySwitch`, `previousCountryCode` memory, provider username rewriting (Owl sticky-suffix regex, Rapid/Clip base rebuild, Generic separator/upper preservation), `Utility.addRecentCountry`, `ProfileManager.switchDefault`, auto-restart-with-500 ms-delay on country switch while CONNECTED.

---

## 4. Module 2 — `OverlaySurface` (interface + events)

### 4.1 Port (external seam)

```kotlin
interface OverlaySurface {
    // -- Lifecycle ------
    fun open(initialTopLeft: Point): SurfaceOpenResult   // addView bubble + flag pill
    fun close()                                          // removeView both; cancel anims/handlers
    // -- Render (single idempotent call; surface diffs internally per widget) --
    fun render(visual: OverlayVisual)
    // -- Menu -----------
    fun showMenu(request: MenuRequest)                   // BubbleMenuOverlay at bubble anchor
    fun hideMenu()
    // -- Events (host supplies impl) --
    var onEvent: (SurfaceEvent) -> Unit                  // set by host at construction
}

data class OverlayVisual(
    val bubble: BubbleVisual,
    val pill: FlagPillData
)

data class BubbleVisual(
    val connection: ConnectionState,
    val elapsedMillis: Long            // 0 when timer hidden
)
// presentation decisions (gradient colors, glyph drawables, spinner, timer formatting,
// breath/pop/squeeze choreography) live INSIDE the surface, derived from these.

data class FlagPillData(
    val visible: Boolean,
    val flag: String,        // text content in
    val countryCode: String,
    val ip: String           // surface derives lastOctet
)

data class MenuRequest(
    val connectedCountryCode: String?,  // ""/null → no pinned row
    val countrySwitchable: Boolean,
    val bubbleCenterX: Int, val bubbleCenterY: Int, val bubbleSizePx: Int
)

sealed interface SurfaceEvent {
    data class Tap(val x: Int, val y: Int) : SurfaceEvent
    data class DoubleTap(val x: Int, val y: Int) : SurfaceEvent
    data class LongPress(val x: Int, val y: Int) : SurfaceEvent
    data class CountrySelected(val code: String) : SurfaceEvent   // from menu row
    data object MenuDismissed : SurfaceEvent
}

enum class SurfaceOpenResult { OK, BAD_TOKEN, UNKNOWN }
```

### 4.2 Invariants

1. Window math is display-frame based exactly as today: `TOP|START` bubble, `TOP|CENTER_HORIZONTAL` pill, inset-aware `currentDragBounds` (API 30 `currentWindowMetrics`, else status/nav identifiers), grow-margin 12 dp so scale anims never clip (L301–308).
2. Drag never leaves the inset-aware bounds, and the pill tracks the bubble's measured geometry (including the `post` re-run when pill height was 0, L496–498).
3. The surface owns **all** animation state (`breatheAnimator`, `colorAnimator`, squeeze/pop) and cancels everything on `close()`. It never leaks handlers past `close()`.
4. Double-tap semantics preserved **bit-for-bit**: a first `Tap` is emitted immediately on `ACTION_UP` (so the host's tap action already ran before the second tap arrives) — exactly today's retroactive behavior; if a second tap lands ≤300 ms later, `DoubleTap` is emitted too. This is deliberate: changing to "suppress first tap, wait 300 ms" would change UX (tap→connect would lag 300 ms) — it is a documented, test-locked behavior.
5. `render` is idempotent and cheap; per-widget short-circuit inside the surface (e.g., 1 s `Timer` diffs → only the `TextView.text` write).

### 4.3 Ordering / Errors

- `open` must be called once before `render`; `render` after `close` is a no-op (host strips calls when `isOpen` false).
- `BAD_TOKEN` (permission revoked mid-flight) → surface emits nothing, returns `BAD_TOKEN` from `open`; the host decides toast+stop-self, keeping the text out of the surface.
- `showMenu` when the menu is already showing → ignored (mirrors `BubbleMenuOverlay.isShowing`). `hideMenu` always safe. `CountrySelected` is swallowed internally if a suppressed row fires while `countrySwitchable=false` (the "not available for this profile" snackbar becomes a surface-owned message — it is a menu affordance, today drawn by `BubbleMenuOverlay.showMessage`).

### 4.4 Config

`bubbleSize=60dp`, `growMargin=12dp`, `glyph=26dp`, long-press `480ms`, double-tap window `300ms`, `scaledTouchSlop`, breath `1.0→1.07/650ms`, pop `1.18×/140ms + overshoot`, crossfade `260ms`, squeeze `0.92/120ms`. All constructor-injectable on the pure parts (`GestureSegmenter`, animation specs object).

### 4.5 What hides behind the seam

Both overlay windows and their `LayoutParams`; flag-pill create/position/follow math; drag+clamp; `reClampBubblePosition` on `onConfigurationChanged`; insets helpers (API-21 vs API-30 paths, deduplicated — today's copies in the service and in `BubbleMenuOverlay` collapse to ONE ownership); `BubbleMenuOverlay` wiring (positioning, IME re-flow, scrim, snackbar); all six animators; touch segmentation.

---

## 5. Module 3 — the Host (thin). Wire tables

`FloatingControlService` remains a `Service` shell: `onCreate` (FGS start, notification channel via `NotificationSurface`, overlay-permission guard, wire everything), `onDestroy` teardown, broadcast receiver → commands. Everything else:

| SurfaceEvent | Action (host-side mapping, reads `feed.state`) |
|---|---|
| `Tap` | `DISCONNECTED → feed.connect()`; `CONNECTED → feed.disconnect()`; `CONNECTING → ignore` (with the same "tap ignored" log) |
| `DoubleTap` | `feed.switchToPreviousCountry()` (guarded: not while CONNECTING — today L806) |
| `LongPress` | if `feed.state.countrySwitchable` and overlay permission OK → `surface.showMenu(request from feed.state + bubble anchor)` — today the LongPress opens the menu even when not switchable; inside the menu the row tap yields the surface snackbar (L399–406). Keep: menu opens regardless, rows show snackbar when unsupported. |
| `CountrySelected(code)` | `feed.switchCountry(code)` → feed emits diffs → host re-renders both surfaces; menu closes via `surface.hideMenu()` |
| `MenuDismissed` | clear surface `longPressFired` state only (it lives in host gesture bookkeeping until split finishes; then it lives in the surface) |

| Feed diff | Host re-render |
|---|---|
| `Connection` / `Endpoint` / `Capabilities` | `surface.render(OverlayVisual(bubble(connection, elapsed), pill(visible, flag, cc, ip)))` |
| `Timer(elapsed)` | `surface.render(... elapsedMillis=elapsed)` — surface short-circuits to one TextView write |
| any diff | `notificationSurface.onDiff(diff)` (dedup happens in surface/notification by diff-masking) |
| `Capabilities(canConnect=false)` after `connect()` | Toast "No proxy configured yet" (the one side effect the feed refuses) |

Also host owns: `VpnService.prepare()` — no wait. The **feed** owns the permission suspense (it already owns `pendingProfile` and the poll loop). Hmm — `VpnService.prepare` requires a Context and launches an activity; that side effect is a real gray zone. Cleanest: feed exposes `connect()` internally doing prepare-while-pending exactly as today (it needs no UI; the system permission dialog is silent on result until the next poll re-check, L980–985 — the mechanism is *poll-based detection*, not a callback). Keep it in the feed; the host never sees the permission intent. This preserves the existing behavior with minimal moving parts; the only host-visible consequence is the CONNECTING→timeout path, which already exists.

---

## 6. Module 4 — `NotificationSurface` (adapter)

Trivial class: implements the same observer port as the host's bubble-rerender loop.

```kotlin
class NotificationSurface(context, feed, channelId="floating_control", notificationId=2)
// open(): create channel, startForeground(id, build(state))
// onDiff(diff): rebuild only when diff mentions Connection/Endpoint/Capabilities
//               (Timer diffs are ignored — the dedup becomes structural, not string-hacked)
// close()
```

Rebuilds exactly today's text (flag · country · ip / Connected / Connecting… / Not connected) and the Connect/Disconnect pill action via `NotificationCompat.Builder` + broadcast intents. Stays on the **standard template** — see §8 for why RemoteViews is not required by this design.

---

## 7. The RemoteViews question — answered with the two-adapters test

> "The notification RemoteViews must reflect the same OverlayState — is RemoteViews an adapter over StatusFeed?"

**Two-adapters test:** a seam is real only if ≥2 adapters sit on it; one adapter is a hypothetical seam (indirection). Count the adapters on the StatusFeed port in production:

1. **Bubble renderer** — host mapping `OverlayDiff → OverlayVisual → OverlaySurface`.
2. **Notification renderer** — `NotificationSurface` mapping the same diffs to notification id 2.

Two real production consumers of identical data → **the seam is real, on production grounds alone, before any test fake exists.** The notification is the adapter that saves the seam: if it stayed inside StatusFeed, the feed would have exactly one consumer (the bubble), the seam would be hypothetical, and StatusFeed + bubble UI should be merged back into one class.

RemoteViews specifically is **not** a distinct adapter kind here — it is one *implementation option* for adapter (2): the same port, a different renderer output (custom `notification_action.xml` RemoteViews vs the standard `NotificationCompat.Builder` template). Today the standard template wins (L757–760: custom RemoteViews rendered as an empty row on some devices); `notification_action.xml` is dead code. Either renderer attaches to the identical diff port with zero changes to StatusFeed. The design does not care which one ships — that is precisely what the seam buys: **renderer experiments (RemoteViews return, tile, etc.) no longer touch state code, and state changes no longer touch renderer code.**

Test adapter = third adapter on the same port (scripted fake feed reproducing recorded state histories, §10).

---

## 8. Seam placement justification

Cut where the **sharing boundaries** are, i.e. along "what varies":

- **AIDL + connectivity + profile knowledge vary with the VPN's process/time**, and are consumed by **two** outputs (overlay visuals + notification). Both outputs read the same questions → one publisher module, `StatusFeed`.
- **Windows/touch/insets/density/permission vary with the Android shell** and are consumed by **one** logical output (the overlay bubble + its menu). It never needs AIDL, never needs `ProfileManager`, never needs time-of-connectedness policy → one sink module, `OverlaySurface`.
- Anything that needs *both* (tap semantics, long-press→menu-gating, diff→render mapping) has its own shallow home in the host — and that list is short and stable.

Locality payoff: every binder/poll/timeout bug lands in `StatusFeed`; every window/gesture/animation bug lands in `OverlaySurface`; every notification-format bug lands in `NotificationSurface`. Today those bugs live in 1258 interleaved lines where a timeout handler and a gradient animator share field state (`setState` calls into both worlds, L1032–1047). The seam is also drawn exactly where the poll loop stops being a UI driver: `pollRunnable` today is the *de facto* render scheduler (L130–137); after the split, cadence ownership is explicit (feed owns polls, surface owns frames).

---

## 9. Adapter inventory

| Seam / port | Production adapter | Test adapter |
|---|---|---|
| `StatusFeed` observer port | bubble/host renderer; `NotificationSurface` | scripted fake feed replaying recorded states |
| StatusFeed → `VpnGateway` (internal, §11) | `AidlVpnGateway` — real `IVpnService` binder, poll-timeout, rebind backoff | `FakeVpnGateway` with scripted `isRunning/connectedSince/isProxyVerified` |
| StatusFeed → `TickScheduler` (internal, §11) | `HandlerTickScheduler` (main looper, `SystemClock`) | `VirtualTickScheduler` — deterministic time advance |
| `OverlaySurface` gesture seam (internal) | `GestureSegmenter` fed by real `MotionEvent`s | raw down/move/up/up-after-300ms sequences |
| `OverlaySurface` port | real `WindowManager` impl | `FakeSurface` recording renders, emitting scripted events |

Every seam here has ≥2 adapters → every seam here is real.

---

## 10. Depth & leverage analysis

**StatusFeed — deep.** Interface: 4 methods + 2 observers + a value type. It hides the hardest logic in the cluster: binder-rebind policy that today is *the* subtle bug vacuum (never-give-up backoff, `rebindInFlight` guard, exception-as-death heuristic, position-aware latch recovery), plus the verification-gated state machine, the timeout latch, and country rewriting. High leverage: one truth source for bubble, flag pill, notification — and a future status-screen/tile/glance consumer (today StatusScreen re-implements the 20 s timeout separately, comment L1241–1244; the diff-delivered invariant could replace that duplicated constant). Each of the ~450 moved lines does real work; the interface cost per capability is ~4 lines.

**OverlaySurface — deep.** Interface: 6 methods + event channel. Hides window lifetimes, inset math (two API generations), drag clamping, pill choreography, menu window, animation orchestration, and the subtle grow-margin/clip contract. Its per-widget render short-circuit keeps the 1 s timer tick cheap while the diff stream arrives at 1 Hz. **This is where the deletion test bites hardest** (§11) — the surface is the module whose internals no one else may touch.

**Host — thin and correctly so.** Pure wiring + tap policy. Held to ~120 lines; if it grows a state machine, the seam moved.

**Leverage vs. weight:** two deep modules + a thin host beats one 1858-line service: the host is the only place that knows both worlds; neither world's change ripples into the other.

---

## 11. Deletion test

**Delete `FloatingControlService.kt` (1258 L) and `BubbleMenuOverlay.kt` (600 L entirely.**) What survives?

- `SocksVpnService`, `IVpnService.aidl`, `ProfileManager`, `ProxyProviders`, `Utility` — untouched (engine seam intact).
- Consumers of the old class: `BootReceiver`, `MainActivity` (bubble toggle) call `FloatingControlService.start/stop` — the **shell keeps the same name**, so no call-site churn.
- The notification's site contract (id 2, channel `floating_control`, specialUse type) moves verbatim into `NotificationSurface` config.
- Inside the new cluster, deletion continues to be free:
  - delete `NotificationSurface` → StatusFeed unchanged;
  - delete the flag pill → surface internal only;
  - replace bubble with a launcher shortcut / tile → StatusFeed re-renders into a third adapter untouched;
  - reverse case: future "status screen also consumes feed" (kills the duplicated `CONNECT_TIMEOUT_MS`) → feed unchanged, +1 adapter.

The reverse direction must also hold: deleting **any present-day field of the service** (e.g. `lastNotificationState` hack, `bubbleGrowMarginPx` documented ad hoc) is possible only because it belonged to exactly one module — the structural dedup evidence speaks for itself: `currentDragBounds`/insets appear twice today (service + menu) and `formatElapsed` style formatting once — after the split, one owner per concept.

---

## 12. Testability

### 12.1 Fake feed — the host/rendering tests (unit, no Robolectric for the wire layer)

`FakeStatusFeed : StatusFeed` implements `state` + `observe` + commands, scripted:

```kotlin
fake.drive(
    Diff(Connection(CONNECTING)),
    Diff(Connection(CONNECTED)), Diff(Endpoint("🇩🇪","DE","Germany","10.0.0.7")),
    Diff(Timer(5_000)) )
assertThat(surface.rendered().bubble.connection == CONNECTED)
assertThat(surface.rendered().pill.text contains "DE")
assertThat(notification.builds) == 2   // Connection+Endpoint coalesced into one rebuild; Timer ignored
```

Steps-level assertions lock the **exact** host wire table (§5) and the notification dedup contract. Gesture-simulation on the host: `fakeSurface.inject(DoubleTap)` → assert `feed.lastCommand == switchToPreviousCountry`; `inject(CountrySelected("FR"))` with `countrySwitchable=false` → assert no command, snackbar emitted.

### 12.2 StatusFeed tests — real module, fake gateway, virtual clock (JVM)

`FakeVpnGateway` + `VirtualTickScheduler`:

- *gating*: `isRunning=false` → still CONNECTING (sticky, no flicker); then `proxyVerified=true, connectedSince>0` → CONNECTED; never emits DISCONNECTED between.
- *timeout*: enter CONNECTING, advance 20 s → DISCONNECTED diff. Not at 19 s.
- *rebind policy*: gateway dies → no immediate diff; advance 200/1000/3000 ms steps → assert exactly 1, then 1 bind attempt per interval, `rebindInFlight` never double-schedules, and post-rebind the fresh poll re-latches CONNECTED.
- *pendingProfile*: feed `connect()` with prepare-returning-non-null → CONNECTING; next `refresh()` with prepare==null → `doStart` path, CONNECTING stays.
- *switching*: Owl sticky-suffix regex cases, Rapid/Clip base rebuild, Generic separator/upper, unparseable → no-op; previous-country ring: `switch("DE")`→`switch("FR")`→`switchToPrevious` == "DE".
- No Robolectric needed except the thin `ProfileManager`/`VpnService.prepare` touchpoints — those stay real calls inside feed; tests for pure branches run with a stub via seam.

### 12.3 GestureSegmenter tests (pure)

Raw event stream → semantic output: down→move>slop → `Drag` (no event), down/up → `Tap`; up then up at 299 ms → `Tap,DoubleTap`; at 301 ms → `Tap,Tap`; down hold 480 ms → `LongPress` (and no `Tap`); cancel → nothing.

### 12.4 What the tests replace

Odds are zero today: none of the 1258 lines are unit-tested (the only safety net is the emulator + eyeballs + the AGENTS.md snapshot tags). The new tests make the three most delicate behaviors — flicker suppression, never-give-up rebind, retroactive double-tap — executable specs instead of folklore.

---

## 13. Internal seams (private to modules, kept out of the external port)

| Inside | Seam | Production adapter | Test adapter |
|---|---|---|---|
| StatusFeed | `VpnGateway` (the AIDL surface: isRunning, connectedSince, isProxyVerified, countryCode, currentIp, country, stop) | `AidlVpnGateway` (bind/rebind/backoff) | `FakeVpnGateway` |
| StatusFeed | `TickScheduler` (postDelayed abstraction) | `HandlerTickScheduler` | `VirtualTickScheduler` |
| StatusFeed | `CountrySwitcher` (pure username rewriting + previous-ring) | real impl | trivial (pure funcs) |
| OverlaySurface | `GestureSegmenter` (pure) | fed by `OnTouchListener` | raw sequences |
| OverlaySurface | `WindowMetrics` (insets/bounds, 2 API paths) | impl | fixed Rect fixtures |

Per DEEPENING.md: internal seams serve the module's own tests; exposing them through the external port would be an interface leak. `CountrySwitcher` is worth noting twice: it is the one piece of the feed that is *also* plausibly reusable by the status screen — but it stays private until a second consumer appears (one-adapter discipline).

---

## 14. Migration sketch (+ rough line budget)

Phases are individually shippable; every phase leaves green CI and a working installation (`master` build on the GitHub builder per AGENTS.md; verify + install `app-arm64-v8a-release.apk` after each push).

| Phase | Work | Δ lines (rough, source + tests) |
|---|---|---|
| **0. Snapshot** | `git tag -a pre-floating-split` + push (AGENTS.md rule) | 0 |
| **1. Lift state out** | `OverlayState.kt` (+diffs) ~120; `StatusFeed.kt` ~330 (poll/setState/rebind/timeout/timer/country + CountrySwitcher pure ~90); service delegates polls to feed; old bubble code *reads feed* (AIDL access removed from service: −70 net) | +450 new / −250 in service |
| **2. Lift surface out** | `OverlaySurface.kt` ~520 (bubble view, pill, drag/clamp, insets, anims, menu delegation, GestureSegmenter ~90); service → host (event wire table, ~110) | +610 new / −530 moved; service −300 |
| **3. Notification adapter** | `NotificationSurface.kt` ~70; delete `lastNotificationText/State` + dedup + dead `notification_action.xml` usage −50 | net −35 |
| **4. Tests** | feed tests ~220, host/surface tests ~130, segmenter ~80 (+ recorded-state fixtures) | +430 |
| **5. Cleanup** | delete duplicated insets in `BubbleMenuOverlay` (surface becomes sole owner), collapse `openBubbleMenu` bookkeeping | −60 |

Rough result: 1858 service+menu lines → ~1400–1500 total across 6 files, with the interleaving deleted for real (−350 to −450 net). The honest framing: **the win is not the line count — it is that every remaining line belongs to exactly one owner, and the risky logic (rebind, latch, flicker, dedup) becomes executable through an interface.**

Suggested landing order risk: Phase 1 is lowest-risk (behavior-identical extraction; watch the `setState`→`updateBubbleUi` path — it must keep working while UI still reads `feed.state`); Phase 2 is the payoff; Phase 3 trivial.

---

## 15. Honest drawbacks

1. **Diff-API tax.** `OverlayDiff` is more surface than a full push + equality check. Subtle risk: a renderer forgets a diff case and shows stale widget state (the exact class of bug the `lastNotificationState` hack was born from). Mitigation: `OverlayDiff` is sealed → exhaustive `when` at compile time; the "apply diff → widget" table is itself tested. Accept the menu of `(diff, state)` recorded fixtures in tests.
2. **Three layers instead of one class.** Host indirection is real but small (~120 lines); the alternative (surface subscribes to feed directly) kills independent testability of the interaction layer — rejected.
3. **Retroactive double-tap is weird and must not be "fixed".** Today tap₁ (stop) executes *before* tap₂ decides it was a double-tap. A naive rework "wait 300 ms before firing Tap" would add 300 ms latency to disconnect — the segmenter spec (§4.2–4) exists precisely to prevent a well-meaning "fix" from changing UX.
4. **AIDL exception-as-death heuristic is preserved, not fixed.** The feed still treats any poll exception as a lost binder (L1017–1028) and relies on glue-state `bound` flags. The seam localizes it (and tests it) but does not eliminate the platform quirk.
5. **`StatusFeed` is not pure.** It needs `Context` for `ProfileManager`/`VpnService.prepare`. The gateway/tick seams cover the *timing and binder* logic; the profile touchpoints stay Robolectric-lite (thin) unless we port `ProfileManager` behind a seam — out of scope, noted for cluster 4.
6. **NotificationRenderer duality stays.** Two notification shapes in flight (standard template now, possibly RemoteViews later) — the diff port handles both, but the FGS "always visible" requirement (id 2 must keep the stopMe DETACH hygiene with `SocksVpnService`'s shared notification) is service-shell logic that can only be *unit-tested* declaratively, not end-to-end without a device.

---

## 16. Alternatives considered (why the radical design)

| Alternative | Verdict |
|---|---|
| **A. Plain split (state service + view service)** | Rejected: two AIDL services multiply lifecycle/release-ordering bugs; the diagnosis stays split across process boundaries with no gains. |
| **B. Notification inside StatusFeed** | Rejected *by the two-adapters test*: then the only consumer of the feed is the bubble and the seam is hypothetical — merging is indicated. The design deliberately pushes the notification **across** the seam to make it real. |
| **C. Full-snapshot push (no diff)** | Rejected for perf-by-construction: notifications would rebuild every 1 s timer tick without the string-dedup hack (which returns verbatim). Diff keeps each adapter's work proportional to what changed. Cost: §15.1. |
| **D. Surface subscribes to feed directly, host disappears** | Rejected: interaction layer ceases to be independently testable/reusable; the surface would re-implement tap-policy that depends on state it must not own. |
| **E. Keep `BubbleMenuOverlay` as a host-called utility (bypass surface)** | Rejected: host would then do WindowManager work and the surface seam would be a lie; the menu already is a surface concern (anchor math, IME, scrim). |

---

## 17. Behavior inventory → owner map (completeness check)

| Current behavior (line refs) | New owner |
|---|---|
| 200 ms poll (L130–137, 979) | StatusFeed |
| 1 s timer (L141–146, 1197–1215) | StatusFeed (diff) + OverlaySurface (render) |
| bind/rebind backoff forever (L147–165, 647–684, 986–994) | StatusFeed via `AidlVpnGateway` |
| verification-gated CONNECTED, sticky CONNECTING (L999–1016) | StatusFeed (invariants §3.2) |
| 20 s timeout on any CONNECTING (L968–977, 1032–1047) | StatusFeed |
| `pendingProfile` permission flow (L980–985, 934–946) | StatusFeed |
| country switch + username rewrite + auto-restart (L855–904) | StatusFeed (`CountrySwitcher` internal) |
| previous-country memory / double-tap target (L121–122, 806–813) | StatusFeed (memory) + GestureSegmenter (timing) |
| bubble + pill windows, params (L299–365, 397–499, 770–799) | OverlaySurface |
| drag clamp, insets, pill follow (L551–571, 473–499, 589–617) | OverlaySurface (`WindowMetrics` internal) |
| gestures / slop / 480 ms / 300 ms (L573–645, 801–822) | OverlaySurface (`GestureSegmenter` internal) |
| animations (L1091–1167) | OverlaySurface |
| config-change re-clamp (L232–261) | OverlaySurface |
| menu show/hide/position/IME (BubbleMenuOverlay 600 L) | OverlaySurface (delegated, mostly unchanged) |
| notification build + dedup (L708–767) | NotificationSurface |
| FGS start/stop, channel, broadcast receiver, permission guard (L182–297, 686–706, 1246–1256) | Host shell |