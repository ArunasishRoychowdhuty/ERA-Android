# ERA v0.7.0 weakness and readiness audit

| Area | Status | Remaining weakness |
|---|---|---|
| One-shot Accessibility approval | Snapshot-bound + smoke-tested fingerprint helper | Requires device validation of the Activity-to-service serialized JSON fingerprint round trip; benign dynamic content can cause safe false cancellation |
| Semantic target resolution | Implemented in existing Accessibility service | Accessibility metadata can be incomplete, ambiguous or misleading; some apps expose little usable UI structure |
| Consequential controls | Existing manual-only label checks retained | Label matching is defense in depth, not a complete semantic side-effect classifier |
| Password-field typing | Existing block retained | Accessibility password metadata can be absent or incorrect; no broader OTP/PIN/token masking was added |
| Pending approval lifecycle | One-shot payload cleared on consume/cancel/expiry | SharedPreferences is local app storage, not a hardware-backed cryptographic transaction log |
| Agent execution core | Implemented portable runtime | Not wired end-to-end to Android Accessibility or device controllers |
| Post-action verification | Portable interface-driven step | Android verifier is not connected; UI tree change alone may not prove intended task success |
| Local device actions | Existing separate Android path | Torch/volume/app-launch adapters not bridged into `BoundedAgentRuntime` |
| Gemini | Stub only | Live transport not implemented; API-key security management remains excluded |
| Chrome DOM | Stub only | No CDP WebSocket/target connection or live DOM snapshot; user-owned setup required |
| Wake word | Interface only | No model, foreground service, microphone lifecycle or battery tests |
| Existing voice | Foreground-only | No screen-off wake-word detection |
| Android build | Not verified | Gradle/Android SDK unavailable in this workspace |
| Device compatibility | Not tested | No target Android 15 physical-device test |
| Privacy | Unchanged per agreed scope | Existing non-password visible text may be present in local snapshots; no extra redaction was added |
| Distribution | Not reviewed for this revision | Re-check current Android/Google Play Accessibility and background-microphone policies before public distribution |

## Validation performed

- `bash tools/run-core-smoke.sh`: 36 assertions passed.
- `bash tools/run-agent-runtime-smoke.sh`: 29 assertions passed.
- `bash tools/run-pending-action-smoke.sh`: 25 assertions passed using an in-memory Android SharedPreferences test double.
- Pure Kotlin helper/runtime modules compile with installed `kotlinc`.
- XML and ZIP integrity checks are run before packaging.
- No claim is made that an Android APK was built or installed.

## Next implementation order

1. Build and instrument the Android project in an Android SDK-equipped environment.
2. On Android 15, test snapshot identity equality between the Activity's approved capture and the Accessibility service's fresh capture, including app switching, overlay taps, dynamic UI changes and screen locks.
3. Connect the portable `BoundedAgentRuntime` to real Android observer, human approval and action-executor adapters.
4. Bridge only safe local Android actions (app launch, torch, volume, settings) into the typed runtime.
5. Design/approve a separate API-key security workstream before Gemini transport.
6. Implement Chrome CDP only through a user-configured, user-owned debugging session; start read-only.
7. Implement a visible foreground wake-word service only after permissions, lifecycle, stop control and battery impact are tested.
