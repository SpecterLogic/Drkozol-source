# Client capability review — 2026-10-31

Scope: Android DrKozol source audit and official public competitor documentation. A documented feature does not prove equivalent behavior on every platform. This is a product planning document, not release copy.

## Existing capabilities to retain

DrKozol already includes protocol-link and QR import, multiple providers, usage/expiry metadata, scheduled subscription updates, Real Delay and TCP tests, latency sorting, best-server selection, provider-wide balancing, routing rules, provider-scoped Happ-compatible routing profiles, DNS/IP preferences, per-app routing, favorites, latency history, diagnostics and password-protected backups. Therefore these should not be presented as newly invented differentiators.

## Evidence-based gaps and opportunities

| Area | Evidence from other clients | DrKozol status / next step |
| --- | --- | --- |
| Provider integration documentation | INCY publishes deep-link, metadata and routing contracts | Add a clear DrKozol integration guide and an import-button contract; no impersonation |
| Explicit balancer membership | Explicit member sets keep unrelated peers out of a group | Available since 1.5.2. The 1.5.3 candidate adds a separate health-selected route for authentication/media domains; ordinary traffic retains round robin. This reduces per-request exit changes, but does not guarantee a fixed IP or acceptance by a service |
| Auto-updating routing profiles | INCY documents a distinct autorouting URL lifecycle | Implemented in the 1.5.3 candidate: HTTPS JSON sources, independent 24-hour refresh, bounded retries, native validation and preservation of previous valid rules on failure. No source revision ordering or user-selectable refresh interval is claimed |
| Obfuscated provider links | INCY documents crypt1 and explicitly calls it obfuscation | The 1.5.3 candidate implements the public crypt1 wire format with an official deterministic test vector. Shared public key material is not a secrecy boundary; this conceals plain text in sharing, not access from a capable recipient |
| Apple platforms | Happ publishes iOS/macOS installers | DrKozol has no signed Apple distribution; requires Apple build/signing infrastructure |
| Routing isolation | Happ documents separate provider control panels | Already implemented; add more provider-update and migration coverage rather than duplicate UI |
| Theming and accessibility | User-supplied screenshots show dark/light gallery mismatch and harsh selection edges | 1.5.2 contains illustrated pairs and gallery mode changes. The 1.5.3 candidate anchors artwork to the physical right edge and adds an explicit selected-server border/checkmark |

## Differentiation worth building next

1. **Connection clinic:** classify local-port, DNS, handshake, routing-file and reachability failures into actionable steps. Verify each recovery before reporting success; never ask users to clear data as routine repair.
2. **Measured stability:** show loss rate and latency variation over time, not just one low ping. Let users choose a stable server using sufficient recent samples.
3. **Safe provider changes:** show added/removed server counts, retain the last valid subscription when a panel returns an error page, and make rollback explicit.
4. **Application privacy mode:** hide provider names, server addresses and subscription links in the UI before sharing an image; redact diagnostic exports by default.
5. **Network-aware recovery:** distinguish Wi-Fi/cellular handover from server failure and preserve ongoing sessions where the protocol permits. Do not promise zero disconnections.

These are proposed acceptance criteria, not statements that the current release contains them.

## References

- [v2rayNG official project](https://github.com/2dust/v2rayNG)
- [Happ routing](https://www.happ.su/main/dev-docs/routing)
- [Happ platforms](https://www.happ.su/main)
- [INCY developer documentation](https://github.com/INCY-DEV/incy-docs)
- [INCY deep links](https://github.com/INCY-DEV/incy-docs/blob/main/ru/dev-docs/deep-links.en.md)
- [INCY subscription formats](https://github.com/INCY-DEV/incy-docs/blob/main/ru/dev-docs/subscription-format.en.md)
