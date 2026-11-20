# ERA Android Assistant — v0.8.0-dev

Native Android assistant for Kotlin/Jetpack Compose. Current source revision includes semantic-first screen context, explicit visual capture, safer call preparation, Instagram task previews, optional AI provider routing and structured task/history views.

## v0.8.0-dev upgrades

### Screen understanding
- AI-facing UI export uses bounded `era.ui-compact.v1`: flattened semantic elements without pixel coordinates, node paths, password nodes or editable current values.
- Full local `era.ui-tree.v1` remains available for snapshot-bound one-shot action approval.
- Visual fallback is user-mediated. Sparse/truncated semantic data asks the user before using visual mode.
- A long-press on the visible ERA Accessibility overlay captures one Android screenshot frame on Android 11+. The frame is held in process RAM, downscaled to a 1440px longest side and cleared/recycled on replacement, consent revocation or service teardown. It is not saved as an image file or automatically uploaded.

### Calling and social preparation
- Contact matcher supports exact/partial names, aliases and Bengali/Arabic-Indic phone digits. Ambiguous matches are not silently resolved. Runtime selection uses Android's system contact picker.
- Call requests display the selected contact and phone number for confirmation. Confirm opens Android's dialer; ERA never places the call.
- Instagram task preview prepares account/media/caption/hashtags/review steps. The final Share action stays manual.

### AI provider and planner
- Local-only planning remains the default.
- Optional Gemini and OpenAI-compatible provider settings validate HTTPS endpoints/model IDs, support manual connection tests and store keys encrypted using Android Keystore AES-GCM.
- Remote fallback is opt-in and local-first. Remote plans are parsed/validated into local previews; consequential actions require user approval and are not executed by the provider adapter.

### Task monitor, history and UI
- Material 3 dashboard header and live permission/voice/screen status chips.
- Task monitor summarizes plan/ledger state and verified/review/failed progress.
- Command history uses versioned JSON, timestamps, outcome categories, legacy migration and masking for phone/email/API-token patterns.

## Verification — current source

- `bash tools/run-all-smoke.sh`: **98 source assertions passed**, including 3 Android XML parse checks.
- Gradle `testDebugUnitTest`: **68 passed, 0 failures, 0 errors, 0 skipped**.
- Main and test Kotlin source compilation passed during the Gradle test task.
- APK assembly was intentionally **not run** for this revision. No updated APK was generated or copied.
- Updated-source install/launch, Accessibility screenshot callback and real provider API tests have not been run.

## v0.7.2 test-pipeline hardening

- Added `tools/run-all-smoke.sh` as the single local/Termux entry point for all source smoke checks.
- Updated GitHub Actions to install the matching Kotlin CLI compiler and run all four checks, including the permission-flow/XML validator, before Gradle tests and APK build.
- Removed an unnecessary non-null assertion in the task-ledger restore path; behaviour is unchanged.
- Bumped app version to 0.7.2 / versionCode 10.


Native Android assistant project (Kotlin/Compose), continuing from v0.6.1.

## v0.7.1 one-time permission setup

- Added a Permission Center for microphone, flashlight camera access and the optional Accessibility service.
- Runtime permissions are checked against Android every time the screen resumes; ERA does not store a stale `granted=true` flag.
- System permission dialogs launch only when the user starts a feature that needs an ungranted permission, or explicitly taps its setup action. Once granted, the normal flow does not request it again.
- Microphone setup explains that Android's **Allow** choice should be used for a persistent grant; **Only this time** is temporary and may require a later grant.
- Accessibility is a special system setting, not a normal runtime permission. The user must accept ERA's disclosure and enable the service in Android Settings. The app reports whether it is currently enabled and does not try to toggle it silently.
- Permission revocation, Android permission auto-reset, app reinstall or temporary one-time grants can legitimately require setup again. ERA cannot override Android's permission controls.

## v0.7 Accessibility approval hardening

- A one-shot UI approval is now bound to a SHA-256 identity of the full Accessibility tree that was reviewed in ERA, including target package, node count and truncation state.
- When the user taps the ERA overlay, the service captures the active screen again and compares its identity with the reviewed snapshot. Any observable screen change cancels the pending action; ERA does not attempt to guess whether the old target is still safe.
- The target is then semantically resolved again on the fresh tree. Existing enabled/visible checks, package check, password-field typing rejection and consequential-control manual handling remain active.
- Pending action selector/text payload and snapshot approval data are cleared before dispatch and on cancellation/expiry. Only the short result message remains.

## v0.6 bounded execution-runtime core

- Added a platform-neutral `BoundedAgentRuntime` implementing **observe → decide → authorize → execute → verify → re-observe**.
- Enforces one dispatched action per observation, fresh pre-dispatch capture, post-action verification, a 20-action cap and a maximum of 3 replans.
- Adds deterministic typed action policy; it does not trust a model's self-declared risk.
- Reversible local actions are allow-listed and range-checked. Generic UI clicks, HTTPS navigation and user-origin text entry require exact-action approval.
- Approval receipts are bound to a SHA-256 proposal fingerprint, observation ID, timestamp and unique one-time approval ID. The portable runtime rejects expired, mismatched, future-dated or replayed receipts within its process lifetime.
- Sending, publishing, submitting, purchasing, payments, deletion, sharing, calls, permission grants, authentication and software installation are **manual-only**.

## Existing features retained

- Offline deterministic command router and compound local commands.
- Accessibility UI-tree capture, semantic selector validation, stale-element checks, one-shot user-approved UI actions and task ledger.
- Foreground-only speech recognition. There is still no always-on wake-word microphone service.

## Remaining limitations

- The portable `BoundedAgentRuntime` is still not connected end-to-end to the Android Accessibility service and local device-control adapters. The app retains the separate one-shot, user-approved semantic UI action path.
- Gemini and OpenAI-compatible HTTP planner adapters now exist, but live provider requests have not been verified with a user-owned API key. Local-only remains the default.
- Chrome DevTools Protocol transport is still a contract/stub. ERA does not access Chrome's private DOM or silently enable browser debugging.
- The wake-word model/runtime and always-on background microphone are not implemented.
- Visual product recognition/price lookup is not integrated. Captured frames are not sent anywhere automatically.
- Contact matching is tested as a pure component; normal runtime contact selection remains through Android's system picker. Instagram remains a preview/preparation flow, not an automatic publisher.
- The task monitor reads a plan/ledger snapshot; it is not a live autonomous task runner.
- The Agent Browser ZIP was not supplied for inspection/adaptation.

## Tests

From the project root:

```bash
bash tools/run-all-smoke.sh
gradle --console=plain testDebugUnitTest --no-daemon --max-workers=2
```

Current source result: **98 source smoke assertions PASS**, 3 XML files parse, and **68 JUnit tests PASS** with 0 failures/errors/skips. These checks do not replace install/launch, instrumentation or real-service runtime testing.

APK assembly was intentionally not run for this source revision. The earlier APK in Downloads is from an older source revision and does not contain these upgrades. See `docs/UPGRADE_ROADMAP_V0.8.md` and `docs/TEST_REPORT.md` for exact status.
