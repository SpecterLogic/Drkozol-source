# DrKozol 1.5.1 validation

Android: 256 JVM tests and four emulator instrumentation tests passed before the version bump. Authenticated encrypted backup restore, provider-isolated routing, favorites, quality history, network policy and basic diagnostics are covered.

Desktop: 179 shared tests passed. The selected-server offline probe regression passed 77 checks on Windows; close/reopen, second-instance activation and restart handoff also passed. Linux x64 compilation passed.

Not run: physical-phone coverage, complete accessibility/IME matrix, all supported API/ABI/locale/theme combinations, live WARP tunnel and trusted TLS-success diagnostics. A previous full live desktop balancer run did not reach readiness; the targeted probe test does not prove balancer or TUN stability. Release-stage validation results are recorded separately when available.

WARP import accepts an existing configuration and does not enroll accounts. Network conditions apply to automatic startup, not background network-change automation. Failed probes are not measured packet loss.
