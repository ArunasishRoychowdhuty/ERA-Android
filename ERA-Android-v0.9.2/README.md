# ERA Android Assistant — v1.0.2

Native Android assistant built with Kotlin and Jetpack Compose. Current source release: v1.0.2, versionCode 19.

## v1.0.2 — Supervised personal assistant, reminders and encrypted memory

- Added an explicit Personal workspace for reminders, Android Calendar export, encrypted managed memory and deletion controls for existing ERA agent memory.
- Added Room persistence for reminders and managed memory. User-entered reminder and memory titles/content are AES-256-GCM encrypted with a non-exportable Android Keystore key; legacy ERA memory is also encrypted at rest and migrated from its prior plaintext preference format on successful Keystore access.
- Added AlarmManager exact scheduling when Android grants exact-alarm access, with unique WorkManager one-time fallback when it does not. Pending reminders are rescheduled after reboot, app replacement, time/timezone changes and exact-alarm access changes.
- Added notification permission flow and private reminder notifications. Denying notifications does not discard the saved reminder.
- Added explicit, per-reminder Calendar export through a visible writable Android calendar. Calendar access is requested only when the user starts an export; ERA does not read or modify calendars in the background.
- Added a separate final voice approval for consequential Send, Submit, Publish, Delete, Payment, Transfer and Purchase controls. The spoken OK is bound to the exact app, selector, action and reviewed screen fingerprint, expires after 30 seconds, and is consumed once. ERA returns to the target app and rechecks the live screen before dispatch; a changed screen, expired approval or restart cancels it.
- Payment/transfer/purchase confirmation requires one unambiguous visible currency amount. Ambiguous or missing amounts fail closed; editable values are not read to construct the confirmation. The summary includes the target action and visible amount.
- Extended sensitive entry blocking to credential-like payloads, raw card-number-like sequences and common UPI virtual-address suffixes. Password, OTP, PIN, CVV, card, bank-account, UPI and API/access-token fields remain blocked from ERA text entry and are not saved as pending text.
- Added JVM regression cases for exact OK, consequential-action gating, payment-summary ambiguity and credential/payment payload protection; added Android instrumentation coverage for encryption/Room persistence and live Accessibility capture.
- Boundaries: this is supervised UI automation, not unrestricted control. Android may hide UI labels or impose platform restrictions; unsupported or ambiguous actions fail closed. Users complete secure authentication themselves. ADB physical-device tests are only valid when a target is actually connected.

Verification: **252/252 JVM tests pass** across 47 suites; Debug APK and Android instrumentation APK both assemble successfully. The Debug APK is copied to ~/storage/downloads/ERA-Android-v1.0.2-personal-assistant.apk (V2 signature verified). SHA-256: 4fb8e0fef33e2d2767e12046f9ef358ca5e96b4c689b7811658ed852c3e37423.

The Android live Accessibility test requires ERA's Accessibility service and screen-inspection consent on a connected device. ADB currently reports no attached device/emulator; instrumentation was built but not executed. See the v1.0.2 section in CHANGELOG for full verification details.

Architecture details: [v1.0.2 supervised personal assistant](docs/ERA_V1.0.2_PERSONAL_ASSISTANT.md), [v1.0.1 Home Management and UI-action policy](docs/ERA_V1.0.1_HOME_AUTONOMY.md), [v1.0.0 Autonomous OS Assistant Architecture](docs/ERA_V1.0.0_AUTONOMY_ARCHITECTURE.md).

## v1.0.1 — Household and profile workflows, final UI-action policy

- Added a local Home Management service for consent-gated household task CRUD, due-time metadata, task status transitions, inventory upsert and routine slots.
- Household task and inventory collections are bounded; due times are limited to one year ahead; writes require explicit consent and use existing domain-memory retention. Structured timestamps use base36 storage encoding to survive privacy scrubbing.
- Added a UserProfileService for explicit-consent profile bundle save/read/update/delete, covering non-sensitive display/preferred name, language, timezone, communication style, interests and accessibility preference.
- Routed Accessibility overlay actions through the central AutonomyExecutionPolicy after fresh screen, exact package/action and one-shot approval checks. Consequential clicks are handed back to the user.
- Added last-mile sensitive-field protection for text entry: password, passcode, OTP, PIN, CVV/CVC, card/payment, bank-account, UPI and API/access-token fields plus credential-like payloads are rejected before payload persistence or UI dispatch.
- Profile and Home services are local domain APIs; no dedicated management/profile screens are connected yet. No alarm/notification scheduler, bill/payment integration, Calendar sync or smart-home/IoT adapter is included.
- Verification: Gradle unit tests **244/244 pass** across 45 suites; `:app:assembleDebug` and `:app:assembleDebugAndroidTest` succeed. Standalone source smoke suite passes Core **36**, Agent Runtime **41**, Pending UI **25**, permission-flow **8/8**, Android XML **4/4**; `git diff --check` passes.
- Debug APK: `~/storage/downloads/ERA-Android-v1.0.1-home-management.apk` (10 MB), package `com.eraassistant.mobile.debug`, versionCode **18**, versionName `1.0.1-debug`, minSdk **26**, targetSdk **35**. APK V2 signature verified.
- SHA-256: `25073e0f94420fe815db986c6ca67fb1a4b1345f1e70c5328a93a9c5692d6f33`.
- Android instrumentation APK builds (411 KB) but tests were not run because no ADB device/emulator is connected. Android Lint is not verified in this release run.
- Architecture details: [v1.0.1 Home Management and UI-action policy](docs/ERA_V1.0.1_HOME_AUTONOMY.md), [v1.0.0 Autonomous OS Assistant Architecture](docs/ERA_V1.0.0_AUTONOMY_ARCHITECTURE.md).

## v1.0.0 — Autonomous OS Assistant Core

- Added a domain-aware intent layer for Android OS control, Personal Profile, Home Management, Communication, Productivity, Files and Web workflows.
- Complex-task preview now applies a two-pass policy: intent/domain/risk/constraint extraction first, then plan enforcement against explicit prohibitions and consequential-action rules.
- Added exact registered capability/action tool selection, with readiness, missing-permission, offline preference and data-sensitivity ranking. Tool selection never grants permissions or executes an action.
- Wired the central autonomy execution gate into TaskExecutionCoordinator for fresh observations, live tool/permission readiness, exact one-time action-bound approvals and critical-risk user handoff.
- Added consent-gated Profile/Home/Routine/People memory domains with field allowlists, expiry and delete/clear APIs. Credentials, identity numbers, precise location and sensitive/secret data are excluded.
- Complex-task preview now displays domain classification, confidence, risk, unresolved input, policy warnings/blocked steps and tool-selection decisions.
- Added regression suites for domain routing, two-pass policy, tool selection, privacy/permission readiness, autonomy gates and expiring domain memory.
- This release advances the architecture and planning foundation. It does not claim unrestricted Android control, arbitrary-shell/root access, full household IoT integration, automatic billing/payment, or unrestricted execution of AI-generated plans.
- Verification: Gradle unit tests **230/230 pass** (43 XML suites); `:app:assembleDebug` and `:app:assembleDebugAndroidTest` succeed; source smoke suite passes Core **36**, Agent Runtime **41**, Pending UI **25**, permission-flow **8/8**, Android XML **4/4**; `git diff --check` passes. APK V2 signature verified.
- Debug APK: `~/storage/downloads/ERA-Android-v1.0.0-autonomy-core.apk` (11 MB), package `com.eraassistant.mobile.debug`, versionCode **17**, versionName `1.0.0-debug`, minSdk **26**, targetSdk **35**.
- SHA-256: `c3f436fd5cf536d83364c4fa50793e266d063d87b2c78df1ca8832955bbb8761`.
- Android instrumentation APK builds (411 KB) but was not run because no ADB device/emulator was connected. Android Lint is not verified in this release run.
- Architecture and lifecycle details: [ERA v1.0.0 Autonomous OS Assistant Architecture](docs/ERA_V1.0.0_AUTONOMY_ARCHITECTURE.md).

## v0.9.4 reliability upgrade

- Added typed Android device-operation results and structured errors; negative localized messages no longer become success-shaped adapter results.
- Added action-specific acceptance criteria and independent torch/audio-volume verification. App/intent handoffs that Android cannot independently verify remain user-confirmed.
- Replaced compound-sequence message substring checks with typed per-step outcomes and partial-progress summaries.
- Added unique execution run IDs separate from plan fingerprints, explicit run-ID resume, workflow timestamp migration, target-specific app discovery and negative-intent/draft-only planner handling.
- Added a strict plan-step compiler for reversible known-app launch steps only; unsupported complex steps remain manual review.
- Added JVM regression coverage and Android instrumentation smoke tests (launch, four-tab navigation, Activity recreation). Instrumentation test APK compiles; execution requires a connected emulator/device.
- Verification: Gradle unit tests **212/212 pass**; standalone P0 **73 pass**; Core **36**, Agent Runtime **41**, Pending UI **25** assertions pass; permission flow **8/8** and Android XML **4/4** pass; debug APK and instrumentation APK assemble successfully; V2 signature verified; `git diff --check` passes.
- Debug APK: `~/storage/downloads/ERA-Android-v0.9.4-reliability-upgrade.apk` (10 MB), package `com.eraassistant.mobile.debug`, versionCode `16`, versionName `0.9.4-debug`, minSdk `26`, targetSdk `35`.
- SHA-256: `28888471aadf5be54e0e5129c92a005bf0bc1edcd6c00a44e40a0936cbd30fb3`.
- Android Lint did not complete in the Termux environment: `:app:lintAnalyzeDebug` stalled without producing a report and was terminated. No lint PASS is claimed. No connected ADB target was available, so instrumentation/device tests were not executed.
- See [v0.9.4 reliability audit](docs/ERA_V0.9.4_RELIABILITY_AUDIT.md).

## v0.9.3 UI refinement

- Reworked the dashboard into four focused areas: Home, Agents, Screen and Settings, retaining the existing feature flows.
- Added a restrained midnight palette, gradient header and Home hero, consistent rounded Material surfaces, and clearer status capsules.
- Added animated page transitions and an animated custom bottom navigation for smoother tab changes.
- Moved contact search into Agents so the Home command centre leads with the primary voice/text controls.
- Corrected the in-app version labels to v0.9.3.

## v0.9.2 patch highlights

- Completed the Remote Connectivity V3 pairing/heartbeat protocol on both sides: Android HTTPS client and a deployable Python/SQLite private gateway.
- Gateway pairing codes are single-use, rate-limited and HMAC-hashed; device access tokens are stored as keyed HMAC digests; heartbeat nonce replay, expiry, revocation and unexpected scopes are rejected.
- Added admin-only pairing-code issuance, device listing and server-side token revocation.
- Added loopback-only Linux/systemd deployment and private Tailscale Serve instructions. The gateway has no shell, ADB, remote command, screen or file-transfer API.
- Android still requires user-started `connectedDevice` foreground session; bearer tokens remain encrypted by Android Keystore.
- Deployment to an actual tailnet and physical phone pairing are still external validation steps; local gateway integration tests do not imply a live remote session.

## v0.9.0 highlights

- Explicit instant commands are compiled into allow-listed typed capability requests and dispatched through the durable task coordinator and execution journal.
- A read-only, user-selected Storage Access Framework file browser supports bounded text previews without broad storage permissions.
- The in-app WebView can hand credential selection to Android's configured Autofill/password-manager service without exposing credential values to ERA.
- Optional same-device Ollama planner support is available through a localhost-only endpoint. Ollama and model weights are not bundled in the APK.
- Unsupported or ambiguous commands fail closed; adapter success without independent outcome evidence remains WAITING_USER.

See [v0.9.0 architecture and security notes](docs/ERA_V0.9.0_ARCHITECTURE.md), [Remote Connectivity V3 protocol](docs/REMOTE_CONNECTIVITY_V3.md) and the [changelog](CHANGELOG.md).

## Shizuku system bridge

Optional native Shizuku integration is available in this build. ERA can request its own Shizuku permission, bind a dedicated UserService, and run a small fixed allowlist of read-only Android diagnostics. It does not expose arbitrary shell execution or system mutation. See [Shizuku setup and security boundaries](docs/SHIZUKU_INTEGRATION.md) for first-run instructions. Runtime use requires the user to approve ERA in the Shizuku app.

---

## Reliability & Integration Upgrade — Review-Gated Execution

### P0 — Core Reliability Architecture
- **Typed Capability Contracts and Registry:** seven production adapters are registered, including scoped SAF storage. Each has an action-specific parameter schema; the registry checks required/undeclared fields, value types, bounds and adapter result identity/payload size.
- **Task/Action State Machine:** strict lifecycle transitions with bounded durable history. SharedPreferences state is committed before transitions; interrupted work is restored as RECOVERY_REQUIRED and never silently resumed.
- **Independent Verification Foundation:** typed verification outcomes use normalized post-action observations. Adapter-specific expected-state checks still need stronger per-action validators.
- **Durable Execution Journal:** idempotency keys, SHA-256 action fingerprints, dispatch checkpoints, retry counters and restart recovery are present. Retry policy/backoff helpers exist; unattended timed retry scheduling is not wired.
- **Execution Coordinator:** typed-request path checks plan-declared capability/action, plan-level risk, fresh observation, schema, exact one-time approval, cancellation and adapter output. v0.9.0 adds a local allow-list compiler for explicit instant commands; arbitrary planner text and unsupported complex steps remain review-only.

### P1 — Intelligence and Orchestration
- **Planner Feedback Loop:** bounded replanning (maximum three) is wired into the coordinator; proposed plans are shown for manual review and are not auto-applied.
- **Task Planning DAG:** checks dependency graph, registered adapter/action mappings and current adapter availability.
- **Browser Boundaries:** Native WebView and External Chrome adapters are registered separately. Chrome remains foreground Accessibility-only. UnifiedBrowserArchitecture is a prototype abstraction with no production consumer.
- **Observation Layer:** up to 500 elements and a 64 KB aggregate character budget, with bounded metadata and truncation signalling.
- **Execution Monitor:** differentiates plan-review approval from actual dispatch and displays active capability during typed execution.

### P2 — Advanced Agent Foundations
- **Skill Engine:** validates/registers skill metadata and exposes skill matches as review hints; no production skill execution loop is connected.
- **Persistent Agent Memory:** bounded storage, sanitized keys/values and legacy-data migration. Learned workflows are suggestions only, not automatically replayed.
- **Privacy Filter:** sensitive-key blocking plus email, bearer/API-key, long-token and contextual OTP/PIN redaction. It is not a universal arbitrary-secret classifier.
- **Offline Manager:** offline capability awareness and deterministic preview fallback are used in task preview.

## Security, Privacy, and Permission Boundaries

- **No Root / Bypass Requirements:** All automations operate within documented Android APIs and permissions.
- **One-Shot Snapshot-Bound Action Approvals:** UI automation requires explicit user approval against an exact SHA-256 snapshot fingerprint and manual floating overlay dispatch in the target app.
- **Browser Privacy:** Address-bar values and editable input fields in Chrome are omitted; URL query strings and fragments are stripped; password values are never exported.
- **Sensitive Action Gating:** Calls, media sharing, and financial/destructive actions remain behind manual user confirmation.
- **Credential Protection:** AI provider keys are encrypted with Android Keystore AES-GCM. Website credentials are handed to Android's configured Autofill/password-manager service; ERA does not read or persist their values.

---

## Test Verification Summary

| Suite | Runner / Harness | Verified result |
|---|---|---:|
| Android Gradle unit tests | Gradle 8.9, :app:testDebugUnitTest | **182 tests / 32 suites — 182 PASS, 0 FAIL, 0 ERRORS, 0 SKIPPED** |
| Standalone P0 JVM tests | tools/run-p0-unit-tests.sh | **72 PASS** |
| ERA Core Smoke | tools/run-core-smoke.sh | **36 assertions PASS** |
| Bounded Agent Runtime Smoke | tools/run-agent-runtime-smoke.sh | **41 assertions PASS** |
| Pending UI Action Smoke | tools/run-pending-action-smoke.sh | **25 assertions PASS** |
| Permission-flow and XML checks | run-all-smoke.sh | **8 checks + 4 XML files PASS** |
| Git diff hygiene | git diff --check | **PASS; pre-existing changes preserved** |
| Private gateway integration | Python stdlib unittest | **13 protocol tests PASS** (pairing, concurrent redemption, token hashing, heartbeat, replay, expiry, revoke, admin listing auth, scopes, rate limits) |
| Debug APK assembly/signature | Gradle 8.9 + apksigner | **BUILD SUCCESSFUL; APK Signature Scheme v2 PASS** |

## v0.9.3 UI build artifact

- VersionCode 15, versionName 0.9.3 (debug variant: 0.9.3-debug).
- The UI change is limited to ERAScreen.kt; existing runtime features and callbacks are retained.
- Gradle unit tests: 182/182 passed; debug APK assembly successful; APK Signature Scheme v2 verified.
- Debug APK: `~/storage/downloads/ERA-Android-v0.9.3-smooth-ui.apk` (9.9 MB), package `com.eraassistant.mobile.debug`, versionCode `15`, versionName `0.9.3-debug`.
- SHA-256: `8f933eb98e3fd83dfe09f6f8015aa1e879765265b049553b0074882ea7478288`.
- The APK is staged in Downloads, not installed. Live visual review of the new UI remains pending.

## Previous v0.9.2 Release Artifact and Remaining Integration Scope


- **Debug APK:** `~/storage/downloads/ERA-0.9.2-debug.apk` (**9.9 MB**).
- **Package/version:** `com.eraassistant.mobile.debug`, versionCode `14`, versionName `0.9.2-debug`, minSdk `26`, targetSdk `35`.
- **SHA-256:** `2f5fb74760ecde559ebd3498837a96c14155e3e93c955adfc5474849c5883754` (source APK and Downloads copy match).
- **Gateway deployment bundle:** `~/storage/downloads/ERA-Remote-Gateway-V3.tar.gz` (19 KB); SHA-256 `ed00617dcc0fcf90cb0cdaddfd3b0505ab86c04abdfc83ddb3a20572596bb17d`.
- **Signature:** APK Signature Scheme v2 verified; one Android Debug signer, certificate SHA-256 `acb8afa549827dfbe4f47614cd104d4de34804d4176a45f4123e60cf870fe194`.
- **Installation:** APK is staged in Downloads but not installed. Runtime voice, Autofill, SAF, browser and foreground-session flows still require manual phone validation.
- **Remote Connectivity V3:** Android client and Python/SQLite gateway are included; 13 local gateway tests pass. The gateway is not deployed to the user's tailnet and the phone has not been paired to a live server. Shizuku remains a separate local-only bridge.
- **Complex planner steps:** unsupported free-form plan steps remain manual-review only; the new compiler dispatches the explicitly allow-listed instant-command set.
- **Standalone Phone Browser Agent:** the separate original project was not modified during this upgrade.


## Personal Digital Operator upgrade (development branch)

The feature/era-personal-digital-operator-2026 branch extends ERA with a consent-gated screenshot-understanding provider, screenshot/Accessibility-fingerprint-bound visual target validation, one-shot gesture fallback, Android system keyboard bridge, reusable account/form/profile/lead/client-reply workflow templates, and an encrypted Room-backed CRM repository foundation.

These additions are development work, not a release claim. Passwords, passkeys, OTPs, PINs, CVV and API credentials stay in the target app or Android Autofill; final submit/send/publish/payment/delete actions remain user-controlled. Screen screenshots are only sent to a configured remote vision provider after a separate one-time upload consent.

See docs/ERA_OPERATOR_UPGRADE_IMPLEMENTATION.md for implemented scope, verification and remaining device/runtime gates. The CRM UI and comprehensive site-specific workflow execution are not yet complete.
