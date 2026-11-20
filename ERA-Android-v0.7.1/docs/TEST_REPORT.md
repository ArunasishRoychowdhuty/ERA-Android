# Test report — ERA v0.7.0

## Offline checks executed

| Check | Result | Notes |
|---|---|---|
| Core Kotlin smoke test | PASS | 36 assertions across Bangla/English routing, compound commands, volume, plan DAG, retry/persistence, semantic UI validation and snapshot-fingerprint identity |
| Bounded runtime Kotlin smoke test | PASS | 29 assertions covering exact-action approval fingerprints, observation binding, timestamp validity, one-time replay rejection, stale UI, manual-only effects, completion freshness, text origin, cross-app checks and URL/package/range validation |
| Pending Accessibility approval store smoke test | PASS | 25 assertions; compiled against an in-memory SharedPreferences test double, covering snapshot binding, selector/payload validation, consume/replay behavior and cleanup on cancellation |
| Pure Kotlin source compilation | PASS | Core, runtime and pending-action smoke scripts compile their targeted Kotlin sources with installed `kotlinc` and execute standalone test jars |
| XML validation | PASS | Android manifest, Accessibility service metadata and string resources parse successfully |
| Full Gradle compile | NOT RUN | Gradle executable/wrapper and Android SDK are not installed in this workspace |
| Debug APK build | NOT RUN | Requires Android SDK platform 35, Android Gradle Plugin dependencies and Gradle 8.9 |
| Device testing | NOT RUN | No physical target Android 15 device connected |

## Reproduce local smoke checks

```bash
bash tools/run-core-smoke.sh
bash tools/run-agent-runtime-smoke.sh
bash tools/run-pending-action-smoke.sh
```

Expected output:

```text
PASS: 36 smoke assertions covering routing, compound actions, contacts, volume, planning DAG, retries, semantic UI validation and snapshot-bound approval identity
PASS: 29 agent-runtime assertions (freshness, exact one-time approvals, manual-only effects, password-field rule, origin checks, URL policy)
PASS: 25 pending-action assertions (snapshot binding, selector/payload validation, one-shot consumption and cancellation cleanup)
```

## Release status

This is a source package, not a compiled APK. v0.7 strengthens the pre-existing one-shot Accessibility approval path. The separate portable `BoundedAgentRuntime` still lacks live Android observer/approval/executor adapters. Gemini, Chrome CDP and wake-word implementations, Android Gradle build and physical-device tests remain outstanding.


## v0.7.1 permission setup validation

- Static source review: PASS — microphone and camera grant state comes from `checkSelfPermission()` at startup/resume; no persisted permission-grant boolean is used.
- Static source review: PASS — request launchers are invoked only by explicit feature/setup flows when the current system grant is missing.
- Static source review: PASS — Accessibility state is read from Android's enabled-service list and can only be enabled through user navigation to system settings after disclosure consent.
- Android Gradle compilation and Android 15 device tests: NOT RUN in this workspace. The runtime permission dialogs, `Only this time` expiry and OEM settings behaviour require the physical-device checklist.

- Permission-flow static test: PASS — 8 source assertions; Android XML resources parse. Run `python3 tools/check-permission-flow.py`.
