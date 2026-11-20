# ERA Android Assistant — v0.7.2

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

## Not integrated / not verified yet

- The portable `BoundedAgentRuntime` is not yet connected end-to-end to the Android Accessibility service, an AI planner, or the local device-control adapters. v0.7 strengthens the separate existing one-shot Accessibility action path.
- Gemini API transport remains a stub; API-key management remains outside the selected scope.
- Chrome DevTools Protocol transport remains a stub; no private Chrome DOM access or silent debugging enablement.
- Wake-word model/runtime and foreground microphone service remain unimplemented.
- Android Gradle compilation, 33 JUnit tests and debug APK assembly now pass on the Moto G64 / Android 15. The APK has been signed, aligned and copied to Downloads; install, first launch and runtime permission/Accessibility tests are still pending.

## Tests

Run from the project root:

```bash
bash tools/run-all-smoke.sh
```

The runner executes **36 core + 29 runtime-safety + 25 pending-approval assertions**, then 8 permission-flow source checks and XML parsing. These are source/smoke checks, not a substitute for Android Gradle compilation, instrumentation or device tests.

See `docs/V0.7.1_PERMISSION_SETUP.md`, `docs/V0.7_ACCESSIBILITY_APPROVALS.md`, `docs/V0.6_RUNTIME.md`, `docs/WEAKNESS_AUDIT.md` and `docs/TEST_REPORT.md` before production use.
