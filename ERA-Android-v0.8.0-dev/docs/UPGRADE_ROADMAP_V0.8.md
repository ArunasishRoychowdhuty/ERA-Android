# ERA Android v0.8 — Upgrade Roadmap and Status

Last updated: 2026-11-20
Working folder: `ERA-Android-v0.8.0-dev`
Baseline: `ERA-Android-v0.7.2`
App version: `0.8.0-dev` / versionCode 11

## Status legend

- DONE: implemented and covered by current tests.
- PARTIAL: functional core exists, but a runtime integration or external verification remains.
- PENDING: not implemented or blocked by a missing dependency.

## P0 — Semantic-first screen intelligence

| Item | Status | Implementation / remaining limit |
|---|---|---|
| Preserve full Accessibility tree for local action validation | DONE | Full tree and snapshot fingerprint remain in the one-shot approval path. |
| Compact AI-facing UI JSON | DONE | `era.ui-compact.v1`; flattened and bounded; omits bounds, node paths, password nodes and editable current values. |
| Semantic-vs-visual decision | PARTIAL | `UiVisualFallbackPolicy` is connected to the AI context builder. Sparse semantic data asks for user choice; no automatic screenshot is triggered. |
| Explicit visual capture | DONE | Long-press the visible ERA overlay; Android 11+ screenshot API; one frame kept in process RAM only. |
| Frame lifecycle and memory use | PARTIAL | Replaces/recycles previous frames, clears on consent revoke/service teardown, downscales to a 1440px longest side. Region cropping is not implemented. |
| Agent Browser skill audit/adaptation | PENDING | No ZIP/source bundle was supplied in this working session; no third-party browser code was guessed or copied. |

## P1 — User workflows and product experience

| Item | Status | Implementation / remaining limit |
|---|---|---|
| Contact exact/partial/alias/digit matching | PARTIAL | Pure matcher supports exact name, partial name, aliases and Bengali/Arabic digit suffix matching; ambiguity returns multiple candidates. Runtime calling continues to use Android's system contact picker instead of requesting broad Contacts access. |
| Call confirmation | DONE | Picker returns selected name/number; ERA shows a confirmation card; confirmation opens the system dialer only. ERA does not place a call. Confirmation expires after two minutes. |
| Instagram preparation | PARTIAL | Dedicated five-step preview covers account, media, caption/hashtags and final review. The final Share tap remains manual; no unattended Instagram posting is implemented. |
| Material 3 UI redesign | PARTIAL | Added Material 3 dashboard header/status chips, provider settings, call confirmation, task monitor and history cards. A live voice waveform and a full navigation redesign are not included. |
| AI provider configuration | PARTIAL | Gemini and OpenAI-compatible settings, model/HTTPS endpoint validation, manual connection test and Android Keystore AES-GCM key storage are implemented. Live provider requests were not verified with a real API key. |

## P2 — Agent reliability and advanced features

| Item | Status | Implementation / remaining limit |
|---|---|---|
| AI fallback routing | PARTIAL | Local plan runs first. A configured remote planner is called only for low-confidence generic plans when the user enables fallback. Transient failures retain the local plan; remote JSON is validated and consequential actions are forced to manual approval. Live API behaviour remains unverified. |
| Task execution monitor | PARTIAL | Projects task-ledger states into a UI summary with verified/waiting/failed counts. Current UI shows a plan/ledger snapshot; it is not yet wired to a continuous cross-app execution loop. |
| Structured command history | DONE | Versioned JSON schema, migration from the prior string-array format, timestamps, outcome classification, 50-item cap and phone/email/API-token redaction. |
| Visual product recognition and price search | PENDING | No image-to-product/search service is wired. Visual frames are not uploaded automatically. |
| Chrome/Agent Browser transport | PENDING | Existing browser bridge remains a contract/stub; the requested Agent Browser ZIP was not available to audit/adapt. |

## Privacy and safety constraints retained

- Visual capture is explicit: long-press the ERA overlay. Frames are process-memory only; no screenshot file or automatic image upload is created.
- The frame store keeps one downscaled frame and recycles replaced/cleared bitmaps.
- Remote planning sends task goal/capability text only; screen frames are not included in provider requests.
- Local-only planning remains the default. Remote requests require a configured HTTPS provider, a saved key and the explicit fallback setting.
- Provider secrets are encrypted with AES/GCM using an Android Keystore key; provider switching clears the prior provider key.
- Calls, Share/Publish, Send, Pay, Delete and Submit remain user-controlled final actions.

## Verification for this source revision

- `tools/run-all-smoke.sh`: 98 source assertions passed; 3 Android XML files parsed successfully.
- Gradle 8.9 `testDebugUnitTest`: 68 tests passed, 0 failures, 0 errors, 0 skipped.
- Main Kotlin sources and unit-test Kotlin sources compiled as part of the test task.
- APK assembly was intentionally **not run** per the current instruction. No updated APK was generated or copied.
- No install/launch, Accessibility screenshot callback, real provider request or Instagram runtime test was performed against this source revision.

## Remaining before release

1. Supply the Agent Browser ZIP if that integration is still required.
2. Add a permission-safe, user-triggered product image search path or keep product recognition out of scope.
3. Complete live provider testing with a user-owned API key, without sending screenshots.
4. Wire the task monitor to actual verified execution events only if a compliant executor is added.
5. Build/install separately and run the manual Android 15 device checklist before relying on the updated source.
