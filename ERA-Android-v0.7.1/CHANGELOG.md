# Changelog

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
