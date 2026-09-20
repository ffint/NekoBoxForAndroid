# NekoBox Enhanced Changelog

## v0.1.0-preview

Build commit: `3e73a80953e617308d2b3e1e796613502c62cb48`

### Added

- Independent Enhanced application identity so it can coexist with official NekoBox.
- Smart Groups with proxy-path RTT, jitter, bounded throughput, reliability scoring, hysteresis, cooldown, and failure failover.
- Background Smart health checks and lightweight RTT-only re-evaluation after Wi-Fi/cellular upstream-interface changes.
- Strict privacy mode for VPN-captured apps with proxy-or-fail routing, proxied remote DNS, IPv4/IPv6 capture, and LAN-bypass suppression.
- Optional geosite, geoip, and remote `.srs` blocking rule sets.
- Room schema v7 for Smart Group configuration and node metrics.

### Compatibility

- Keeps Matsuri sing-box `1.12.19-neko-1` for v0.1.
- sing-box 1.14.x is deferred until NekoBox's generated DNS configuration is migrated away from the legacy option format removed in 1.14.

### QA

- Clean-environment native and Android build passes.
- PreviewRelease and PreviewDebug assembly pass.
- Smart Group unit tests pass.
- arm64-v8a QA APK SHA-256: `99e097bb69be3b9baa86ad234edefed5dc91ded60e8f1a4955196fab3f075ba1`.
- Current QA artifact uses Android Debug signing; production release signing remains intentionally separate.
