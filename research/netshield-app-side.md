# NetShield — App-Side Implementation (ProtonVPN Android)

Repo: `B:\Studio\Tools\protonvpn-android-app` (branch as-is, 2026)
Package root: **`com/protonvpn/android`** (NOT `me/proton/android/vpn`) — sources under `app/src/main/java/com/protonvpn/android/`.

All line numbers verified against the checked-out source.

---

## 1. The state machine: `NetShieldProtocol` enum

`app/src/main/java/com/protonvpn/android/netshield/NetShieldProtocol.kt:21-27`

```kotlin
enum class NetShieldProtocol(val protocolString: String) {
    DISABLED(""),
    @Deprecated("f1 is deprecated, only off or on values for netshield, except for TV")
    ENABLED("+f1"),
    ENABLED_EXTENDED("+f2"),
    ENABLED_EXTENDED_ADULT_CONTENT("+f3"),
}
```

- Phone UI only ever uses **`DISABLED`**, **`ENABLED_EXTENDED`** (+2 = ads+trackers+malware), and **`ENABLED_EXTENDED_ADULT_CONTENT`** (+3 = + adult content). `ENABLED` ("+f1") is deprecated.
- The `protocolString` field is **never referenced elsewhere in the app** — the value sent over the wire is the **enum `ordinal`** (see §4). `+f2`/`+f3` are protocol sugar for the server side.
- Availability (free tier): `netshield/NetShieldAvailability.kt:24-32` — `AVAILABLE` vs `UPGRADE_VPN_PLUS`; free user → `UPGRADE_VPN_PLUS`.

## 2. Storage of the setting

- **Field:** `settings/data/LocalUserSettings.kt:95` — `val netShield: NetShieldProtocol = NetShieldProtocol.ENABLED_EXTENDED` (default ON; JSON key `"netShield"`, no `@SerialName` override).
- **Container:** `CurrentUserLocalSettingsManager` persists `LocalUserSettings` as JSON via Proton DataStore (`settings/data/CurrentUserLocalSettingsManager.kt:41-51`, `LocalUserSettingsStoreProvider`; `@Serializable data class` at `LocalUserSettings.kt:85-113`). Contains the field `netShield: NetShieldProtocol` (line 95).
- **Legacy DB key:** old profiles table column `"netShield"` mapped to `TEXT` in `db/MigrateToNewProfiles.kt:40`.
- **Per-profile override:** profiles carry their own NetShield — `profiles/data/ProfilesDao.kt:62` `updateNetShield(profileId, netShield)`; column `NetShield` in the new profiles table (see `profiles/data/Profile.kt` / `ProfilesDao`).

## 3. Toggle functions (settings state holder)

`app/src/main/java/com/protonvpn/android/settings/data/CurrentUserLocalSettingsManager.kt:82-101`

```kotlin
suspend fun updateNetShield(newNetShieldProtocol: NetShieldProtocol) =
    update { current -> current.copy(netShield = newNetShieldProtocol) }

suspend fun toggleNetShield() = update { current ->
    val newNetShieldState =
        if (current.netShield == NetShieldProtocol.DISABLED) NetShieldProtocol.ENABLED_EXTENDED else NetShieldProtocol.DISABLED
    current.copy(netShield = newNetShieldState)
}

suspend fun toggleNetShieldAdultContentBlock() = update { current ->
    // Adult content block is tied to extended netshield. If it was enabled, then the off state for adult content block
    // still has netshield extended enabled. There is a separate toggle controlling the extended netshield state.
    val newNetShieldState = if (current.netShield == NetShieldProtocol.ENABLED_EXTENDED) {
        NetShieldProtocol.ENABLED_EXTENDED_ADULT_CONTENT
    } else {
        NetShieldProtocol.ENABLED_EXTENDED
    }
    current.copy(netShield = newNetShieldState)
}
```

Key insight: **the "Block adult content" sub-switch is not a separate boolean.** It flips `netShield` between `ENABLED_EXTENDED` and `ENABLED_EXTENDED_ADULT_CONTENT` — a single enum drives everything. Off-state of adult content still leaves NetShield ON (extended).

UI wiring:
- `redesign/app/ui/SettingsChangeViewModel.kt:70-74` `toggleNetShield()` → `userSettingsManager.toggleNetShield()`; `:188-190` `toggleNetShieldAdultContentBlock()`.
- `redesign/settings/ui/SubSettings.kt:152-153` — `onNetShieldToggle = settingsChangeViewModel::toggleNetShield`, `onToggleNetShieldAdultContentBlock = ...`.
- Main-screen (home) control: `redesign/home_screen/ui/HomeViewModel.kt:336-340` `setNetShieldProtocol()` → `SetNetShield` usecase (`HomeViewModel.kt:102-116`): if a profile is connected, writes to `profilesDao.updateNetShield(profileId, …)`, else `userSettingsManager.updateNetShield(…)`.
- Settings switch UI (explicit set, not toggle): `netshield/NetShieldComposable.kt:232-239` — checked = NOT `DISABLED`; on change sends `ENABLED_EXTENDED` or `DISABLED`.
- TV: `tv/settings/netshield/TvSettingsNetShieldViewModel.kt:111-113`.

## 4. What actually changes at connection time

NetShield is **not** applied app-side to DNS/connection params. It is delivered to the **LocalAgent** as feature `"netshield-level"` = enum ordinal, and filtering (including DNS filtering + adult content) is executed by the LocalAgent/server.

`app/src/main/java/com/protonvpn/android/vpn/VpnBackend.kt:427-444`

```kotlin
private fun initFeatures() {
    settingsForConnection
        .getFlowForCurrentConnection()
        .onEach { settings ->
            agent?.setFeatures(getFeatures(settings.connectionSettings))
        }
        .launchIn(mainScope)
}

private fun getFeatures(settings: LocalUserSettings) = Features().apply {
    setInt(FEATURES_NETSHIELD, settings.netShield.ordinal.toLong())
    setBool(FEATURES_RANDOMIZED_NAT, settings.randomizedNat)
    setBool(FEATURES_SPLIT_TCP, settings.vpnAccelerator)
    ...
}
```

`VpnBackend.kt:638` — `private const val FEATURES_NETSHIELD = "netshield-level"`.

Ordinal → level mapping sent to the agent: `DISABLED=0`, `ENABLED=1` (unused), `ENABLED_EXTENDED=2`, `ENABLED_EXTENDED_ADULT_CONTENT=3`.

- The flow is fed by `redesign/vpn/usecases/SettingsForConnection.kt:85-106` (`getFlowForCurrentConnection` / `getFlowForIntent`): combines profile-derived connect-intent, raw user settings, applies `SettingsOverrides` (`SettingsForConnection.kt:130-141`, incl. `netShield = overrides.netShield ?: netShield`) and `ApplyEffectiveUserSettings`. The features are also handed to the `AgentConnection` at creation (`VpnBackend.kt:325-339`, `features` ctor param) and updated live via `agent.setFeatures(features)` (`VpnBackend.kt:345-347`) whenever the settings flow emits — **so flipping NetShield while connected takes effect without reconnecting** (server-side local agent).
- **No app-side DNS/protocol parameter changes from NetShield.** Custom DNS, the only app-side DNS mechanism, flows through `ConnectionParams` instead (`models/vpn/ConnectionParamsWireguard.kt:117` `addAll(userSettings.customDns.effectiveDnsList)`; `vpn/protun/ConnectionParamsProTun.kt:84`) — unrelated to NetShield. The LocalAgent handles the filtering using Proton's own DNS infrastructure.
- Real user settings flow: `redesign/vpn/usecases/SettingsForConnection.kt:75-82` → `applyEffectiveUserSettings` which enforces plan/level restrictions (below).

## 5. Effective-value gating (free tier / level 3 check)

`app/src/main/java/com/protonvpn/android/settings/data/EffectiveCurrentUserSettings.kt:114-126`

```kotlin
val netShieldProtocol = when {
    vpnUser.getNetShieldAvailability() != NetShieldAvailability.AVAILABLE -> {
        NetShieldProtocol.DISABLED
    }
    vpnUser?.hasNetShieldLevelThreeAvailable != true && settings.netShield == NetShieldProtocol.ENABLED_EXTENDED_ADULT_CONTENT -> {
        NetShieldProtocol.ENABLED_EXTENDED
    }
    else -> { settings.netShield }
}
```

- Level-3 (adult content) entitlement comes from the API: `models/login/VPNInfo.kt:41-45` — `NetShieldConfig(Malware, AdsAndTrackers, AdultContent)` deserialized from `@SerialName("NetShield")` (`VPNInfo.kt:35`); surfaced via `auth/data/VpnUser.kt:76-77` `hasNetShieldLevelThreeAvailable = netShieldConfig?.adultContentBlockingAvailable == true`.
- Settings screen hides the adult-content checkbox unless: `redesign/settings/ui/SettingsViewModel.kt:170` — `isAdultContentBlockAvailable = isNetShieldLevelThreeAvailable && value && dnsOverride == DnsOverride.None`.
- Upgrade to Plus auto-enables NetShield: `vpn/UpdateSettingsOnVpnUserChange.kt:87-91` → `userSettingsManager.updateNetShield(Constants.DEFAULT_NETSHIELD_AFTER_UPGRADE)` (`utils/Constants.kt:74` = `ENABLED_EXTENDED`). Downgrade to free resets to default (`UpdateSettingsOnVpnUserChange.kt:79`).

## 6. DNS conflicts (custom DNS / private DNS)

App-side detection only — no data change:

- `vpn/DnsOverrideFlow.kt:34-36,71-78` — `enum DnsOverride { None, CustomDns, SystemPrivateDns }`; `getDnsOverride(isPrivateDnsActive, effectiveSettings)` = SystemPrivateDns if Android private DNS active, else CustomDns if `customDns.effectiveEnabled`, else None.
- When `DnsOverride != None`, UI shows a conflict banner/“NetShield unavailable” state (`netshield/NetShieldViewState.kt:28-39`; `NetShieldComposable.kt:191-217`; `settings/usecases/DisableCustomDnsForCurrentConnection.kt:31`; SettingsViewModel.kt:143-148) — but **the stored protocol is not touched**; the agent feature still gets the ordinal. Filtering just won't work through a third-party DNS.

## 7. Stats (ads/trackers/saved bytes)

- Wire format: `vpn/StatsGroup.kt:49-67,69-74` — `StatsGroups` parses LocalAgent status `featuresStatistics` map; group key = `LocalAgent.constants().statsNetshieldLevelKey`; counters `"ads"`, `"tracking"`, `"savedBytes"`, `"malware"` (human-readable mapping).
- Ingestion: `vpn/VpnBackend.kt:211-223` — in `onStatusUpdate`, `status.featuresStatistics?.toStats()` → emit `NetShieldStats(adsBlocked, trackersBlocked, savedBytes)` into `netShieldStatsFlow` (`VpnBackend.kt:282`); cleared on TLS session end (`:273-277`).
- Polling: `vpn/VpnBackend.kt:254-271` — while app is in foreground and user is not free, `agent?.sendGetStatus(true)` every 60 s (`LOCAL_AGENT_STATUS_DELAY_MS`, `VpnBackend.kt:636`).
- Exposure to UI: `vpn/VpnConnectionManager.kt:189-190` — `val netShieldStats = activeBackendFlow.flatMapLatest { it?.netShieldStatsFlow ?: flowOf(NetShieldStats()) }`.
- Model: `netshield/NetShieldStats.kt:21-25` — `data class NetShieldStats(adsBlocked: Long, trackersBlocked: Long, savedBytes: Long)`.
- Shown in `netshield/NetShieldComposable.kt:112-133` (`BandwidthStatsRow`).

## 8. Telemetry

- Connection telemetry dimension: `vpn/telemetry/VpnConnectionTelemetry.kt:264-265` — if `settings.netShield != DISABLED` add dimension `"netshield"`; value via `telemetry/TelemetryExtensions.kt:44-48`: `DISABLED→"off"`, `ENABLED→"malware"`, `ENABLED_EXTENDED→"ads_trackers_and_malware"`, `ENABLED_EXTENDED_ADULT_CONTENT→"ads_trackers_malware_and_adult_content"`.
- Settings heartbeat: `telemetry/settings/GetSettingsTelemetryHeartbeatDimensions.kt:222-223,346` (`DIMENSION_NETSHIELD_LEVEL = "netshield_level"`).
- Profile telemetry: `telemetry/ProfilesTelemetry.kt:50,155-160`.

---

## How it works (narrative)

1. **Settings:** a single `NetShieldProtocol` enum inside the JSON-serialized `LocalUserSettings` (DataStore). Main toggle ⇄ `DISABLED`/`ENABLED_EXTENDED`; the “Block adult content” sub-toggle only moves between `ENABLED_EXTENDED` ⇄ `ENABLED_EXTENDED_ADULT_CONTENT` (never a separate boolean). If a profile is active, the value is stored on the profile (DAO) instead.
2. **Gating:** effective settings downgrade the value for free users (`DISABLED`) and drop `ENABLED_EXTENDED_ADULT_CONTENT → ENABLED_EXTENDED` if the API's `NetShieldConfig.AdultContent` capability is false. Custom/private DNS only triggers UI "unavailable" banners, no data change.
3. **Connection time:** `VpnBackend.initFeatures` observes the per-connection settings flow and pushes a LocalAgent `Features` map keyed `"netshield-level"` with `netShield.ordinal` (`0/2/3`), at agent creation and on every setting change (live update, no reconnect). No DNS/connection-param modification in the app — filtering is executed by the LocalAgent with Proton's DNS, server-side.
4. **Stats:** LocalAgent status messages carry a netshield stats group (`ads`, `tracking`, `savedBytes`), polled every 60 s while foregrounded and not free; parsed in `StatsGroup.kt`, surfaced as `NetShieldStats` via `VpnConnectionManager`.