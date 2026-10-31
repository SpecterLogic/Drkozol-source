# Android 1.5.3 candidate validation

Scope: selected-server visibility, right-aligned artwork, selected-member session routing, remote routing refresh, crypt1 import/sharing and consent-gated control links. Desktop packages and native libraries are unchanged.

## Automated results

- Playstore debug JVM tests, Kotlin compilation, debug assembly and lint passed.
- Release and instrumentation APK assembly passed. The candidate retains the original signing certificate, v2/v3 signatures, 16 KiB native alignment and complete bundled routing assets. All native library payloads match the preceding published core.
- Emulator routing recovery: invalid downloaded geodata was rejected by an isolated native validator; the previous active profile remained intact; retry with valid data committed the replacement and cleared its failed attempt.
- Emulator local-proxy balancer fixture: six protected-domain requests used the lower-latency healthy member; ordinary requests used both chosen members; after the first member stopped, a protected request succeeded through the second member. No external server was involved in this fixture.
- Existing startup/port-collision and selected-member persistence tests passed. Existing routing-assets and Connection tools tests passed, including persistence, touch and D-pad interactions.
- Consent tests passed: forged/replayed tickets were rejected; a crypt1 prompt hid the URL and retained its masked presentation after activity recreation.
- A private subscription fixture returned successful native outbound HTTP measurements. Its URL and raw output are excluded from this report.
- The routing builder passed three Node tests and Edge checks at 390/1280 px in both light/dark modes: routing/crypt1 form actions, no horizontal overflow and no JavaScript page errors.

## Limits and checks not run

**Not run:** physical-device long-duration video/authentication sessions, Wi-Fi/cellular handover, real 24-hour WorkManager scheduling, device reboot during an in-flight refresh, TalkBack spoken output for the new selected-server marker, all dynamic-color/large-font/landscape combinations and permission dialogs on every supported Android version. A local fixture proves routing behavior, not universal service acceptance or uninterrupted connectivity.

The session route uses health selection, not permanent per-user affinity. Probe results or server failures can still change the exit IP. A destination may reject a server's IP regardless of client routing. crypt1 uses public interoperability material and is obfuscation, not protection from a capable recipient.
