# NetShield DNS/Blocking Engine — How It Actually Works

**Research date:** 2026-08-12
**Scope:** `B:\Studio\Tools\protoncore_android` (per request) + sibling repo `B:\Studio\Tools\protonvpn-android-app` (where the real engine lives — see §0).
**Status:** Read-only research. No code changed.

---

## 0. TL;DR / Where the code actually lives

NetShield is **NOT implemented in protoncore_android**. That repo only contains:
- marketing strings ("Built-in ad-blocker (NetShield)") in `plan/presentation/src/main/res/values*/strings.xml` (e.g. `plan_id_vpnplus_adblock`),
- a **generic DoH resolver used to bootstrap the app's own API connections** (`me.proton.core.network.data.doh.*`) — unrelated to NetShield (see §6).

The real NetShield engine lives in **`protonvpn-android-app/app/src/main/java/com/protonvpn/android/`**. Most hits are under the `netshield/` package and `vpn/` package.

**The core finding:** on Android, NetShield is **not a client-side DNS filter at all**. There is no domain blocklist, no bundled hosts file, no DNS packet interception, no DoH/DoT custom resolver in the app. The client merely:
1. Pushes a **level integer (0/1/2/3)** to the VPN server over the LocalAgent control channel as feature `netshield-level`,
2. Guarantees all DNS goes over the tunnel to the server's internal resolver `10.2.0.1`,
3. Reads back **stats** (ads/trackers/saved bytes/malware) from the server via LocalAgent status updates.

All blocking decisions and the blocklist itself are **server-side** (server's DNS resolver), keyed off the `netshield-level` it received.

---

## 1. The core mechanism (class-by-class)

### 1.1 The level → server feature mapping
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
`protocolString` (`+f1/+f2/+f3`) is the legacy OpenVPN feature-string form; nothing in the repo consumes it anymore (single usage is inside the file itself).

### 1.2 Sending the level to the server — LocalAgent
`app/src/main/java/com/protonvpn/android/vpn/VpnBackend.kt` — base class for **all** backends (OpenVPN via `OpenVpnBackend`, WireGuard via `WireguardBackend`, ProTun via `ProTunBackend`):

- `VpnBackend.kt:427-444`:
```kotlin
private fun getFeatures(settings: LocalUserSettings) = Features().apply {
    setInt(FEATURES_NETSHIELD, settings.netShield.ordinal.toLong())
    setBool(FEATURES_RANDOMIZED_NAT, settings.randomizedNat)
    setBool(FEATURES_SPLIT_TCP, settings.vpnAccelerator)
    ...
}
```
- `VpnBackend.kt:638`: `private const val FEATURES_NETSHIELD = "netshield-level"`
- Features are transmitted over the authenticated LocalAgent TLS channel to the server: `VpnBackend.kt:584-597` `connectToLocalAgent()` → `createAgentConnection(certInfo, hostname, createNativeClient(), features)`; re-sent live on settings change via `initFeatures()` (`VpnBackend.kt:427-434`).
- The agent itself is a **Go binary** from gopenpgp: `import com.proton.gopenpgp.localAgent.*` (`VpnBackend.kt:24-29`). Its wire keys (`features` / `stats*` constants) live in that Go code, not in this repo.

### 1.3 Reading back blocking stats (proof the filtering is remote)
`app/src/main/java/com/protonvpn/android/vpn/VpnBackend.kt:211-223` — `onStatusUpdate()` parses `status.featuresStatistics` and emits:

```kotlin
NetShieldStats(adsBlocked = stats.getAds(), trackersBlocked = stats.getTracking(), savedBytes = stats.getBandwidth())
```

`app/src/main/java/com/protonvpn/android/vpn/StatsGroup.kt:49-74` maps LocalAgent stat keys:
```kotlin
fun getNetShieldStats() = groups[LocalAgent.constants().statsNetshieldLevelKey]
companion object {
    const val MALWARE = "malware"; const val ADS = "ads"
    const val TRACKERS = "tracking"; const val SAVED_BYTES = "savedBytes"
}
```
So the server reports e.g. `mac/0/adblock: {ads: N, tracking: M, savedBytes: B, malware: K}` per connection; the app just renders them. Surfaced in UI via `netShieldStatsFlow` (`VpnBackend.kt:282`) and `VpnConnectionManager.kt:189-190`.

### 1.4 DNS transport — always the server's internal resolver
`app/src/main/java/com/protonvpn/android/models/vpn/ConnectionParamsWireguard.kt:108-125` (WireGuard tunnel config):
```kotlin
val (addresses, dns) = ... "$VPN_CLIENT_IP/32" to VPN_SERVER_IP
val dnsServers: String = buildList {
    addAll(userSettings.customDns.effectiveDnsList)   // custom DNS (if any) takes first slot
    add(dns)                                          // 10.2.0.1 always appended
}.joinToString(",")
```
Constants — `app/src/main/java/com/protonvpn/android/utils/Constants.kt:172-182`:
```kotlin
const val VPN_CLIENT_IP    = "10.2.0.2"
const val VPN_SERVER_IP    = "10.2.0.1"
const val VPN_CLIENT_IP_V6 = "2a07:b944::2:2"
const val VPN_SERVER_IP_V6 = "2a07:b944::2:1"
const val PROTON_DNS_LOCAL_IP   = VPN_SERVER_IP
const val LOCAL_AGENT_IP   = VPN_SERVER_IP
const val LOCAL_AGENT_ADDRESS  = "$LOCAL_AGENT_IP:65432"
```

**DNS server, NetShield on vs off:** identical — every connection sends the device to `10.2.0.1` (IPv6: `2a07:b944::2:1`). There is **no DoH/DoT** and no different IP for "on". The server's resolver behaves differently purely based on the received `netshield-level` feature. That's why NetShield changes apply **live without reconnecting** (`profiles/ui/ShouldAskForProfileReconnection.kt:35-36` — "Make netshield the same as it doesn't require reconnection").

The app's own HTTP client (OkHttp) plugs DNS through `app/src/main/java/com/protonvpn/android/vpn/VpnDns.kt:83-116` — races system resolver vs `protonResolver` against `PROTON_DNS_LOCAL_IP` (10.2.0.1) via dnsjava `SimpleResolver` (`VpnDns.kt:132-145`), with hardcoded IP fallbacks for `dns.google`/`dns.quad9.net` (`VpnDns.kt:102-114`). This is API-connectivity bootstrap, not blocking.

---

## 2. How blocking is decided; where the blocklist is

- **Model:** blocklist, not default-deny allowlist. Unknown domains resolve normally; known-bad domains get a sinkhole response.
- **Location:** **server-side only.** The client repo contains **zero** domain lists — no bundled resource (`rg -i blocklist|blacklist|hosts.txt` → no hits), no remote-update fetcher, no hardcoded domains. The list is maintained by Proton on the VPN server's DNS resolver and applied per `netshield-level`.
- Client-side gating is only **plan/entitlement logic**, not list logic:
  - `netshield/NetShieldAvailability.kt:29-31` — free users → `UPGRADE_VPN_PLUS`; VPN Plus/Business → `AVAILABLE`.
  - Billing detail from server API: `auth/data/VpnUser.kt:76` `hasNetShieldLevelThreeAvailable`.
  - Enforcement/downgrade: `settings/data/EffectiveCurrentUserSettings.kt:116-124` clamps `ENABLED_EXTENDED_ADULT_CONTENT` → next level when user lacks level-3; `vpn/UpdateSettingsOnVpnUserChange.kt:79-89` resets to `DISABLED` on free downgrade, `ENABLED_EXTENDED` after upgrade.

---

## 3. Mode ↔ rule-category mapping

| Mode (enum) | `netshield-level` (ordinal → int) | Rule categories applied (server-side) |
|---|---|---|
| `DISABLED` | 0 | none (plain resolution) |
| `ENABLED` `+f1` | 1 | deprecated; effectively ads+trackers (legacy, TV) |
| `ENABLED_EXTENDED` `+f2` | 2 | ads + trackers + malware |
| `ENABLED_EXTENDED_ADULT_CONTENT` `+f3` | 3 | ads + trackers + malware + adult-content blocking |

Evidence:
- The ordinal is what's sent: `setInt(FEATURES_NETSHIELD, settings.netShield.ordinal.toLong())` (`VpnBackend.kt:437`).
- Malware is a distinct server-reported stat category: `StatsGroup.kt:62,70` (`"malware"`, `statsMalwareKey`).
- Level-3 is "adult content block tied to extended netshield", toggled independently: `settings/data/CurrentUserLocalSettingsManager.kt:91-101` `toggleNetShieldAdultContentBlock()` (EXTENDED ⇄ EXTENDED_ADULT_CONTENT).

---

## 4. DNS server setup — constants recap

- All tunnels: DNS = `10.2.0.1` (`VPN_SERVER_IP`, `Constants.kt:173`); IPv6 `2a07:b944::2:1` (`Constants.kt:176`). Tunnel client IP `10.2.0.2` (`Constants.kt:172`).
- LocalAgent control plane lives at `10.2.0.1:65432` (`Constants.kt:181-182`) — this is where `netshield-level` and stats travel.
- No DoH endpoint is ever used for NetShield. (The DoH machinery that DOES exist in protoncore — §6 — resolves Proton's API hosts when connectivity is broken, and routes through the same `dns11.quad9.net`-style third parties.)

---

## 5. NetShield + user custom DNS / Private DNS interplay

`app/src/main/java/com/protonvpn/android/vpn/DnsOverrideFlow.kt:34-78`:
```kotlin
enum class DnsOverride { None, CustomDns, SystemPrivateDns }
fun getDnsOverride(isPrivateDnsActive: Boolean, effectiveSettings: LocalUserSettings) = when {
    isPrivateDnsActive -> DnsOverride.SystemPrivateDns
    isCustomDnsActive  -> DnsOverride.CustomDns
    else               -> DnsOverride.None
}
```
- When custom DNS or Android Private DNS is active, blocking would be bypassed, so **NetShield is presented as `Unavailable`** — `netshield/NetShieldViewState.kt:28-39` (`R.string.netshield_state_unavailable`), `profiles/ui/ProfileCreateModals.kt:652`.
- Conflict is detected and surfaced, not silently resolved in favor of one:
  - Adding a custom DNS while NetShield on → `CustomDnsViewModel.kt:144-153`: `netShieldConflict = netShield != DISABLED && customDns.effectiveDnsList.isEmpty()` → shows `NetShieldConflictDialog` (`profiles/ui/ProfileCreateModals.kt:681-691, 705-736`; strings `custom_dns_conflict_banner_netshield_*`, `private_dns_conflict_banner_netshield_*`).
  - Per Profile-edit flow: never both on — `CreateEditProfileViewModel.kt:855-856`.
- Practically, per Proton's documented behavior: enabling custom DNS turns NetShield off (and vice versa); Private DNS is detected via `ConnectivityMonitor.kt:204` (`linkProperties.isPrivateDnsActive`).

---

## 6. The protoncore_android DoH module (for completeness — NOT NetShield)

Purpose: **API connectivity bootstrap** (used when the app can't reach `protonmail.com` API endpoints directly, e.g. country-level blocks). It resolves `d<base32-hostname>.protonpro.xyz` TXT/A records via third-party public DoH servers to discover alternative API base URLs.

- `network/data/src/main/kotlin/me/proton/core/network/data/doh/DnsOverHttpsProviderRFC8484.kt:44-49` — `class DnsOverHttpsProviderRFC8484(...) : DohService`; builds RFC 8484 `GET /dns-query?dns=<base64url>` queries (`DnsOverHttpsRetrofitApi.kt:27-31`, `@Query("dns") base64DnsMessage`, `Accept: application/dns-message`). Base32-encoded hostname question: `"${sessionPrefix}d${base32domain}.protonpro.xyz"` (`DnsOverHttpsProviderRFC8484.kt:90-106`; test assert `DohProviderTests.kt:115`).
- Endpoint constants — `network/data/src/main/kotlin/me/proton/core/network/data/di/Constants.kt:48-104`: primary DoH servers `arrayOf("https://dns11.quad9.net/dns-query/", "https://dns.google/dns-query/")`, plus ~40 alternates (`anycast.dns.nextdns.io`, `1.1.1.2`, `dns.mullvad.net`, `adblock.dns.mullvad.net`, ...). Randomly picked for extra resolvers.
- Wired in `ApiManagerFactory.kt:136-142, 229`.
- **IRL relevance for a NetShield clone:** this is the pattern to copy if you want *client-side* connectivity resilience — not the blocking engine.

---

## Mechanism narrative (one paragraph)

When the user picks NetShield level 2 (`+f2`), the app stores `NetShieldProtocol.ENABLED_EXTENDED` in `LocalUserSettings` and, on connection, tells the server through the encrypted LocalAgent control channel (inside the tunnel, at `10.2.0.1:65432`, authenticated with the user certificate) to set feature `netshield-level = 2`. Device DNS for all protocols is pinned to the tunnel's internal resolver at `10.2.0.1` (custom DNS, if configured, is simply prepended as an additional server for bigger sites — hence the conflict rules in §5). Every DNS query therefore traverses the VPN to the server, where Proton's resolver consults its (server-maintained) rule lists per level — level 2: ads + trackers + malware; level 3: plus adult content — and sinkholes matches. The app never sees DNS packets, holds no blocklist, and cannot fail-open/fail-closed by itself; it just renders the stats the server streams back (`StatsGroup`: `ads`/`tracking`/`savedBytes`/`malware` per `NetworkShieldLevel` group) for the home-screen counters. NetShield is effectively a **server-configured DNS-profile toggle**, not a local engine.

## Key file index

| File | Lines | Role |
|---|---|---|
| `protonvpn-android-app/.../netshield/NetShieldProtocol.kt` | 21-27 | The 4 levels (+f1/+f2/+f3) |
| `protonvpn-android-app/.../vpn/VpnBackend.kt` | 427-444, 638, 584-597, 211-223, 282 | Level → LocalAgent feature; stats out |
| `protonvpn-android-app/.../vpn/StatsGroup.kt` | 49-74 | Server stat keys decode |
| `protonvpn-android-app/.../models/vpn/ConnectionParamsWireguard.kt` | 108-125 | WG DNS = custom DNS + 10.2.0.1 |
| `protonvpn-android-app/.../utils/Constants.kt` | 172-182 | 10.2.0.1/10.2.0.2, LocalAgent addr |
| `protonvpn-android-app/.../vpn/DnsOverrideFlow.kt` | 34-78 | Custom/Private DNS conflict model |
| `protonvpn-android-app/.../netshield/NetShieldAvailability.kt` | 29-31 | Plus-plan gate |
| `protonvpn-android-app/.../vpn/VpnDns.kt` | 83-145 | In-app OkHttp resolver via 10.2.0.1 |
| `protoncore_android/.../network/data/doh/DnsOverHttpsProviderRFC8484.kt` | 44-109 | DoH (API bootstrap only) |
| `protoncore_android/.../network/data/di/Constants.kt` | 48-104 | DoH endpoint list |

**(Implication for KiloProxy:** to replicate NetShield you need either a server-side filtering resolver (as Proton does) with the client only teleporting DNS + a level flag, or a genuine client-side engine — intercepting DNS in the VpnService and consulting a syncable blocklist; the Proton repos contain no reference implementation of the latter on Android.)