# Changelog

## v0.8.0-dev — workflow, visual-memory and reliability upgrade

### P0 — Screen observation
- Connected semantic-first versus visual-fallback decisions to the AI context builder. Sparse or truncated Accessibility data now asks for user choice; no screenshot is taken automatically.
- Added explicit Android screenshot capture from a long-press on the ERA Accessibility overlay. One frame stays in process RAM, is downscaled to a 1440px longest side, and is cleared/recycled on replacement, consent revocation or service teardown. No image file or automatic upload is created.
- Retained the full local Accessibility tree for snapshot-bound action approval while keeping the AI-facing compact JSON free of coordinates, node paths, password nodes and editable current values.

### P1 — User workflows and UI
- Added deterministic contact exact/partial/alias/phone-digit matching with Bengali and Arabic-Indic digit normalization. Ambiguous results are returned as multiple matches rather than silently selecting the first.
- Added a selected-contact call confirmation card. Confirm opens the Android dialer only; ERA never places the call automatically.
- Added a dedicated Instagram preparation plan for account/media/caption/hashtags/review. The user performs the final Share action manually.
- Expanded the Material 3 dashboard with status chips, provider controls, call confirmation, visual-frame preview, task monitor and command history.
- Added optional Gemini and OpenAI-compatible provider configuration, HTTPS/model validation, manual connection testing and Android Keystore AES-GCM key storage.

### P2 — Planner and history reliability
- Added local-first planner routing with optional remote fallback for low-confidence plans only. Transient failures preserve the local plan; provider output is validated and consequential steps are forced to manual approval.
- Added a task monitor projection over plan/ledger state with verified, review, failed and cancelled summaries.
- Replaced display-string-only history with versioned structured JSON, timestamps, outcome classification, legacy migration and masking for phone/email/API-token patterns.

### Verification for this source revision
- Source smoke suite: 98 assertions passed; three Android XML files parsed.
- Gradle `testDebugUnitTest`: 68 passed, 0 failures, 0 errors, 0 skipped.
- No APK assemble/build was run in this revision, as requested. No updated APK was generated or copied.
- Live provider API, screenshot callback, Instagram runtime, install/launch and manual device interaction tests remain unverified.
- Agent Browser/Chrome CDP transport and visual product/price search remain pending; the Agent Browser ZIP was not available for inspection.

## v0.7.2 remote build/test results
- Moto G64 / Android 15: all 98 source smoke checks passed.
- Android Gradle Kotlin compile and debug APK assembly succeeded using Gradle 8.9, JDK 17, Android SDK 35 and the native Termux AAPT2 override.
- All 33 JUnit tests passed with zero failures, errors or skipped tests.
- Debug APK: 9,717,845 bytes; SHA-256 `1d28802239805fb2302ff61ac529d06c6d12762ef75ff3039993ac8d5ac66113`.
- APK ZIP integrity, V2 signature and 4-byte alignment of 67 uncompressed entries verified. The APK was copied to shared Downloads as `ERA-Android-v0.7.2-debug.apk`.
- Install/launch and Android runtime interaction tests remain pending Android Package Installer approval. See `docs/REMOTE_DESKTOP_AUDIT.md` and `docs/TEST_REPORT.md` for exact environment and limitations.

## v0.7.2 — source test pipeline hardening
- Added one `tools/run-all-smoke.sh` command for Core, Bounded Agent Runtime, Pending UI Action and Permission-flow/XML checks.
- Updated GitHub Actions to install Kotlin CLI compiler 2.0.21 and run the complete four-part smoke suite before Android unit tests and APK assembly.
- Removed one redundant Kotlin non-null assertion found during Moto Termux compilation; behaviour is unchanged.
- Removed `material-icons-extended`; ERA only used one microphone glyph. Replaced it with a lightweight text glyph to avoid an oversized unused icon dependency.
- Added a Gradle CPU limit for lower-memory Android Termux build environments.
- Bumped app version to 0.7.2 / versionCode 10.


## v0.7.1 — one-time permission setup
- Added an in-app Permission Center for Microphone, Camera/flashlight and Accessibility access.
- Permission status is derived from Android's live permission state on app resume; no persisted grant flag can become stale.
- After a permission is granted, feature entry points re-check the grant and skip the runtime dialog. Setup requests are only launched by an explicit user action when the permission is missing.
- Added explicit guidance to choose Android's normal `Allow` option rather than `Only this time` when a persistent microphone grant is intended.
- Accessibility enablement is shown separately because it is a special system setting requiring explicit user action after the in-app disclosure.
- Documented system-driven exceptions: revocation, unused-app auto-reset, reinstall and temporary one-time grants.
- Added 8 static permission-flow assertions and XML parse checks.
- Bumped app version to 0.7.1 / versionCode 9. Android build and physical-device verification are still required.

## v0.7.0 — snapshot-bound Accessibility approvals
- Bound every one-shot Android UI approval to a SHA-256 identity of the exact serialized Accessibility tree, target package, node count and truncation state reviewed in ERA.
- Re-capture the active app screen when the ERA overlay is tapped; if any observable UI state changed after approval, cancel without dispatch and require a fresh review.
- Kept semantic re-resolution, enabled/visible checks, consequential-control rejection, password-field typing rejection and one-action consumption before dispatch.
- Changed pending-action consumption/cancellation to atomically clear the stored selector, text payload, app package, observation ID and creation timestamp while retaining only the result message.
- Added snapshot-fingerprint smoke assertions and an in-memory Android SharedPreferences test harness; 36 core, 29 bounded-runtime and 25 pending-action assertions pass.
- Updated project version to 0.7.0 / versionCode 8.
- The portable `BoundedAgentRuntime` is still not connected end-to-end to an AI planner or the Android service. Gemini, Chrome CDP, wake word, Gradle APK build and physical-device validation remain outstanding.

## v0.6.1 — exact-action approval hardening
- Replaced boolean approval with a one-time `AgentApprovalReceipt` bound to the full action fingerprint, observation ID and approval timestamp.
- Reject missing, mismatched, expired, future-dated, malformed and replayed approval receipts.
- Added SHA-256 action fingerprinting with length-prefixed canonical fields to avoid delimiter/null ambiguity.
- Added 8 additional runtime assertions; all 29 runtime and 30 core smoke assertions passed at that revision.
- Kept the existing password-field rule unchanged; no extra OTP/PIN/token or personal-data masking was added, matching the previously agreed project scope.
- Android adapters, Gemini transport, Chrome CDP, wake-word engine, Gradle build and device testing remained outstanding.

## v0.6.0 — bounded execution runtime core
- Implemented a platform-neutral observe/decide/authorize/execute/verify runtime with injected planner, observer, executor, approval and verifier adapters.
- Enforced one action per fresh observation, re-capture after approval, pre-dispatch snapshot identity checks and post-action verification.
- Added deterministic typed action policy with allow-listed local actions, bounded volume values and explicit HTTPS validation.
- Added manual-only handling for send, publish, submit, purchase, payment, deletion, sharing, calling, permission grants, authentication and installation.
- Blocked password-field input, model-generated text insertion, stale completion claims, cross-app targets and unknown actions.

## v0.5.0 scaffold
- Selected Gemini planner interface (transport intentionally not wired because secure key management was excluded).
- Chrome DevTools bridge interface for user-configured Chrome debugging (implementation stub).
- Wake-word engine interface for foreground visible session (implementation stub).
- Initial bounded agent-loop policy and weakness audit.
