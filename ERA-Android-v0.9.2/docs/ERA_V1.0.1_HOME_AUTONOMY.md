# ERA v1.0.1 — Household Workflow and Final UI-Action Policy

Date: 2026-11-23
Release: v1.0.1 / versionCode 18 / debug versionName 1.0.1-debug
Baseline: v1.0.0 autonomous OS assistant core

## 1. Release scope

v1.0.1 extends the autonomous Android assistant foundation with:
- last-mile authorization for Accessibility overlay UI actions
- text-entry privacy protection at proposal, approval-storage and final-dispatch boundaries
- a local Home Management service for household tasks, inventory and routine notes
- bounded structured storage that reuses the consent-gated domain memory repository

It does not enable unrestricted Android internals, root, arbitrary app automation, or unreviewed third-party account actions.

## 2. Accessibility UI-action authorization

The Accessibility overlay now evaluates the shared AutonomyExecutionPolicy after it has:
1. verified screen-inspection consent and unlocked device state
2. captured a current accessible UI snapshot
3. checked the expected foreground package
4. compared the current snapshot fingerprint with the reviewed snapshot
5. resolved one unique target and validated the requested action against its live node metadata
6. checked the pending approval age, package, exact action kind and reviewed observation binding

Risk mapping:
- scroll forward/back: low risk, reversible, still requires one-shot approval and exact observation binding
- click/type text: high risk and treated as non-reversible, requires a fresh exact one-shot approval
- consequential controls (send/publish/submit/pay/purchase/delete/transfer/permission grant/install/call and recognized Bengali variants): critical, returned to manual user completion without invoking the accessibility action

The pending approval is consumed before the accessibility side effect. If target lookup, policy gate or live-node validation fails, no action is dispatched. A new observation and new approval are required for another attempt.

## 3. Sensitive text-entry boundary

SensitiveInputPolicy is applied at three layers:
- UiActionProposalValidator rejects secret/payment/credential input fields before review.
- PendingUiActionStore rejects known sensitive selectors/resource IDs and credential-like payloads before saving a pending payload.
- EraAccessibilityService checks the final live target metadata and payload again immediately before the central autonomy gate.

The denylist includes password/passcode, OTP/verification/security code, PIN, CVV/CVC, card number, credit/debit card, bank account, UPI, payment/wallet, API key, access/refresh/bearer token, authorization and secret/recovery key labels, including common Bengali labels.

Safe non-sensitive input remains reviewable; this policy does not auto-fill any field merely because it is non-sensitive.

## 4. Home Management service

HomeManagementService provides a local API:
- household task create/list/status update/delete
- task due time validation (future-only and no more than 365 days ahead)
- inventory item upsert by case-insensitive name with bounded quantity and unit
- independent routine slots: morning, evening, cleaning, shopping and reminder preference

All mutations require an explicit consent flag. Task and inventory collections are limited to 12 records each. Task titles, inventory names/units and routine descriptions are length-bounded. Existing DomainMemoryService enforces allowlisted fields, consent, sensitive-value rejection and 180-day default retention.

Structured timestamps use base36 encoding and generated IDs are deliberately bounded to avoid generic long-token redaction in the current memory sanitizer. DomainMemoryService's collection payload bound is 3,500 characters, below the underlying 4,000-character memory record ceiling.

This repository is a local persistence/workflow foundation, not a complete household management product:
- no dedicated Home Management screen is connected yet
- no AlarmManager/WorkManager reminder schedule has been added
- no notification permission flow for household reminders
- no Google Calendar sync, smart-home/IoT, appliance vendor or utility/bill integrations
- no automatic payment or purchasing workflow

## 5. User Profile service

UserProfileService stores one structured profile bundle under the USER_PROFILE domain:
- display name and preferred name
- language and timezone
- communication style
- bounded interests list
- accessibility preference

Every save/update requires explicit consent. A profile bundle is limited to eight interests, each with bounded length; free-text fields are length-bounded and reject control characters. The service supports read and delete. It intentionally excludes phone/email, exact address/location, government identifiers, financial fields and credential values. This is a preference profile API, not a full identity profile or a secure vault.

## 6. Verification

Final v1.0.1 Gradle run:
- 244/244 unit tests passed across 45 XML suites; 0 failures, 0 errors, 0 skipped.
- `:app:assembleDebug` and `:app:assembleDebugAndroidTest` succeeded.
- Standalone source smoke suite passed: Core 36 assertions, Agent Runtime 41, Pending UI 25, permission-flow 8/8, Android XML 4/4.
- `git diff --check` passed.
- Debug APK: 10 MB, package `com.eraassistant.mobile.debug`, versionCode 18, versionName `1.0.1-debug`, minSdk 26, targetSdk 35. APK Signature Scheme V2 verified.
- SHA-256: `25073e0f94420fe815db986c6ca67fb1a4b1345f1e70c5328a93a9c5692d6f33`.

The instrumentation APK builds (411 KB) but instrumentation tests were not executed because `adb devices -l` reported no connected device or emulator. Android Lint was not verified in this release run. The APK was not installed and was not pushed to GitHub.
