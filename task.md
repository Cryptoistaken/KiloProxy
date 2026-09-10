# Task: VPN Accelerator (experimental)

## Scope note (SOCKS5 client)
We keep the Proton-style name, but our meaning is different on purpose.
Proton's VPN Accelerator is server-side throughput tech (multi-process
OpenVPN load distribution, path splitting + BBR congestion control,
patched Linux fast-path forwarding, bare metal). KiloProxy is a SOCKS5
client (`tun2socks` + `pdnsd`) with no server fleet, so none of that
transfers. Our accelerator = faster subsequent CONNECTS only, no
throughput claim.

## Goal
Speed up subsequent (sequential) VPN connects to the same proxy without changing first-connect correctness.

Current connect path (`SocksVpnService` + `Utility.startVpn`):
tunnel bring-up (`establish` + `pdnsd` spawn + SOCKS hostname resolve + `tun2socks` spawn + `sendfd` poll, ~0.6-1.0s) then proxy verification (`Utility.checkPublicIp` 4-provider race through SOCKS + `SocksTester.probeProxy` fallback, ~0.5-1.5s). UI shows CONNECTED only when `running && tunnelUp && verified`.

Target: subsequent connects to the same `host:port:user` feel instant (tunnel-up ~0.4-0.8s, verified shown from cache, re-verified in background).

## Plan (experimental, behind a disabled-by-default toggle)
1. UI-only toggle first (this change): `Settings > VPN Accelerator` page with a single enable switch. Pref `vpn_accelerator`, default `false`. No engine behavior change yet.
2. Later, when enabled only:
   - DNS cache: keep `host -> resolved IP + timestamp` across `stopMe()` (TTL, e.g. 10 min); pre-resolve default profile on app start.
   - Optimistic verified: reuse last `IpInfo` for the same `host:port:user` to show CONNECTED at tunnel-up; keep the live `checkPublicIp` loop as background re-verification (overwrite + broadcast when fresh result lands).
   - Parallelize `makePdnsdConf` with DNS resolve; keep `sendfd` poll as-is.
   - Optional: single fastest IP provider first, rest as fallback; optional warm `:vpn` process (weigh battery/FGS cost).
3. Safety: toggle OFF = today's exact behavior. Toggle ON = cache/optimistic paths only. Stale geo must self-correct on first fresh `IpInfo`. Engine files (`SocksVpnService.kt`, `Utility.kt`, `ProfileManager.kt`) change only in that later step, with a `pre-accelerator` snapshot tag first.

## Status
- [x] `task.md` created (no old file existed), scoped to SOCKS5 connect-time.
- [ ] `PREF_VPN_ACCELERATOR` pref, Settings row, `vpn_accelerator` page (default OFF).
- [ ] Engine wiring behind the flag (not started).
