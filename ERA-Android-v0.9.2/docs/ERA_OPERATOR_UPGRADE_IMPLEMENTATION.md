# ERA Personal Digital Operator — Upgrade Implementation Notes

**Scope:** developer source changes made during the operator architecture upgrade.
**Target device baseline:** Android 15 (API 35).
**Release status:** development changes only; no version bump, signing, release or Git push performed.

## Implemented in this source batch

### 1. Sensitive input hard block
- The in-app WebView browser policy now shares field detection and payload screening with `SensitiveInputPolicy`.
- Password, passcode, OTP, verification, card/payment, PIN, API key/token and similar fields are not fillable by ERA.
- A confirmation cannot override the block.
- The browser client rejects credential-like payloads before dispatch.
- The WebView JavaScript action layer independently rejects sensitive `fill`, `type` and `focus` actions.
- The browser UI now tells the user to use the site's own sign-in flow or Android Autofill.

### 2. Android system keyboard bridge
- Added `NativeImeController`, which requests the currently selected Android IME for an attached and focused view.
- It reports request acceptance separately from verified IME visibility.
- The in-app WebView can focus one fresh, ordinary editable field and request the system keyboard.
- Added capability action `browser.webview.focus_and_show_keyboard` and connected it to the browser capability adapter, planner capability mapping, and browser UI.
- The bridge does not capture, read or store keyboard keystrokes.

### 3. Integration routing policy
- Added an explicit transport preference: official API, AppFunctions, Android platform API, Android Intent, Deep Link, browser DOM, Accessibility tree, then screenshot gesture fallback.
- AppFunctions is gated to API 36+, or API 34+ only when App Function Extension support is confirmed at runtime. This project has not yet added an AppFunctions provider/probe, so the extension flag defaults to false.
- Connected transport selection to `AutonomyToolSelector` and supplied the current SDK level and screen-inspection consent from MainActivity.
- Existing Android device actions and browser/accessibility adapters are mapped by concrete action type.

### 4. Screenshot-grounded target validation
- Every captured RAM visual frame now receives a unique frame ID.
- Visual inspection metadata exposes that ID.
- Added `VisualTargetResolver` for normalized screenshot regions. It validates frame ID, source package, capture age (2.5 seconds), display dimensions, rotation, frame/display aspect ratio, region bounds, confidence (at least 0.90), and explicit approval before converting a normalized region to display coordinates.
- Updated the UI-agent prompt rules so a model may propose a normalized region tied to one exact screenshot, but it may not invent freehand pixels. Semantic Accessibility targeting remains preferred.
- This resolver validates a target proposal; it does not itself call a vision model or dispatch a gesture.

### 5. Regression coverage added or updated
- Password and OTP hard-block expectations.
- Credential-like payload rejection on an otherwise ordinary editable field.
- Integration transport precedence, API 36 gating, and Accessibility/visual consent conditions.
- Visual target package, frame, staleness, confidence, geometry, region and approval validation.

## Existing infrastructure reused rather than duplicated
The inspected project already contains a task DAG, capability registry, approval receipts, execution journal, verification engine, Room, WorkManager, voice input/output, reminders, and persistent memory foundations. This batch extends or reuses those systems instead of creating competing copies.

## Not implemented by this batch

These remain separate engineering tasks and must not be represented as finished:
- A general multimodal screenshot-understanding provider connected to the resolver (local-first model routing plus separately consented cloud fallback).
- A real Accessibility `dispatchGesture` fallback wired to the resolver, including the service gesture declaration, display/inset calibration, and device tests.
- App-specific official API / SDK and Deep Link adapters beyond existing Android intents and browser adapters.
- AppFunctions implementation/provider integrations. The current API 36 guard is only a compatibility gate.
- Full account-onboarding, website-form, professional-profile, lead-discovery, proposal-draft and client-reply workflow definitions wired end-to-end through the coordinator.
- A dedicated professional CRM data model and user-facing CRM screens.
- Full voice VAD / barge-in / streaming conversation improvements.
- A verified local multimodal model implementation for this device.
- Physical-device end-to-end tests across Android 15 and additional OEMs.

## Operational and privacy rules
- Screen capture remains user-triggered and held in process RAM; revoke/clear behavior from the existing project remains authoritative.
- Semantic UI and official app integrations take priority over visual automation.
- The screenshot resolver returns only a grounded proposal. A separate exact approval and post-action verification are still required.
- Never automate entry or storage of passwords, OTPs, PINs, CVV, card/bank credentials, API keys or access tokens.
- Send, publish, delete, submit, purchase and payment remain consequential actions requiring a separate exact user approval.
- No screenshots are uploaded by the new resolver.

## Validation completed in this batch
- ` :app:testDebugUnitTest `: **265 tests, 0 failures, 0 errors, 0 skipped**.
- ` :app:assembleDebug `: **BUILD SUCCESSFUL**.
- `git diff --check`: **PASS**.
- Debug APK: `app/build/outputs/apk/debug/app-debug.apk` (11,512,698 bytes; SHA-256 `8b764ca27ef5d9899fc248ecf4938926ba3411d6d6528eea70a46a053ae863da`).

## Release gate
The debug build is not a release build. Do not bump the app version or treat this as a release until device keyboard behavior, Android 15 screenshot geometry, app-switch behavior, permission revocation, and end-to-end workflow regression pass on the target phone. No install, signing, Git commit or push was performed in this batch.


## Extended personal-operator implementation (2026-11-23)

### Screenshot understanding and grounded visual interaction
- Added a bounded multimodal screen-understanding provider for explicitly selected Gemini/OpenAI-compatible vision configurations. The user sees a dedicated one-time upload consent before the full screenshot is sent.
- Added a strict JSON parser that accepts only normalized 0..1 regions for visible, non-sensitive control roles; it filters credential field labels and rejects malformed or out-of-bounds regions.
- Captured screenshots are bound to the exact Accessibility tree fingerprint and source package. If the tree changes during capture, the frame is discarded.
- Added a one-shot visual gesture fallback. It requires screen consent, an unlocked phone, same frame ID, same app package, the same current UI fingerprint, matching display geometry/rotation, confidence >=0.90, and a separate exact tap approval. Password/OTP/payment and consequential controls are rejected; no automatic retry occurs. The service declares canPerformGestures.
- Visual tap callback causes a fresh semantic screen observation. This remains a bounded fallback, not a general-purpose screen-driving agent.

### Reusable workflow catalogue
- Added deterministic workflow templates for account onboarding, official website forms, professional-profile optimization, lead discovery and client-reply preparation.
- Templates produce sequential TaskPlans and are selected before generic planner fallback when a phrase match is unambiguous.
- Authentication, password/passkey/OTP, final account creation, form submission, publishing and sending remain user-controlled. The catalog prepares/reviews tasks; it does not claim unsupported website-specific actions are already executable.

### Encrypted professional CRM storage foundation
- Added a Room v1-to-v2 non-destructive migration and client_crm_records table. Client/project labels, summaries and source URL are encrypted with the existing Android Keystore AES-GCM cipher; status and timestamps are indexed metadata.
- Added repository APIs for explicit-consent save, status update and delete. HTTPS source URLs, field bounds and credential-like payload rejection are validated. Authentication secrets and personal contact coordinates are not CRM fields.
- A full user-facing CRM screen, opportunity discovery adapters and automated proposal delivery remain separate work.

### New regression cases
- Added screen-control JSON parsing bounds and role validation.
- Added workflow matching, dependency chain, consequential manual-only stage and ambiguity tests.
- Added CRM credential/URL/status policy tests.
- Added screenshot-to-screen fingerprint mismatch test.

## Runtime gaps that still require a target-phone test
- Confirm selected keyboard presentation and field focus across OEM keyboards and non-WebView apps.
- Verify the screenshot normalization mapping (full display versus screenshot buffer crop/insets) on the target Android 15 device before enabling real visual taps broadly. A fingerprint match does not prove every OEM's screenshot pixel coordinate system maps identically.
- Exercise Accessibility gesture cancellation, rotation and overlay/window changes on-device.
- Complete the CRM user interface, lead source adapters and complete workflow execution compilers for generic forms/profile/client replies.
- Local multimodal vision is not implemented; remote vision requires explicit upload consent and a configured provider.
- No install/live-device interaction, release signing, commit or push is performed until the corresponding verification steps are completed.


## Validation completed on 2026-11-23
- Gradle task :app:testDebugUnitTest: **273 tests, 0 failures, 0 errors, 0 skipped** (52 test suites).
- Gradle task :app:assembleDebug: **BUILD SUCCESSFUL**.
- git diff --check: **PASS**.
- Debug APK: app/build/outputs/apk/debug/app-debug.apk (11,522,253 bytes).
- SHA-256: 7898214f084d56da5d3183c84a505f27e54264f3a742b9c2d58d821eb84c050b.

## Release and live-device limitation
This is a debug development build. It has not been installed or exercised on the target phone; screenshot/display coordinate mapping, OEM IME behavior, gesture cancellation, real website workflows and CRM UI still require a physical-device QA cycle. No production signing, version bump or release tag is included.
