# ERA v1.0.0 — Autonomous OS Assistant Architecture

Date: 2026-11-23
Release: v1.0.0 / versionCode 17 / debug versionName 1.0.0-debug
Baseline: v0.9.4 source tree on main

## 1. Release intent

v1.0.0 consolidates the planning-side architecture for an autonomous Android OS assistant. It adds explicit domain classification, two-pass plan guarding, exact registered-tool selection, a central execution authorization policy, and consent-gated user/home domain memory.

The release is an architecture and orchestration foundation. It is not a claim that every Android API, household system, application workflow, or AI-generated plan can already be executed end-to-end.

## 2. Runtime request lifecycle

```text
Voice / Text Request
        |
        v
DomainIntentClassifier
  - domains / target hints
  - risk classification
  - explicit constraints
  - unresolved inputs
        |
        v
Planner Provider Router
  - local deterministic planner
  - configured local or remote planner (when enabled)
        |
        v
TwoPassPlanningEngine.enforcePlan
  - block explicit contradictions
  - elevate consequential steps
  - disable retries for consequential steps
  - require exact user approval
        |
        v
TaskPlanDAG + CapabilityRegistry
  - structural graph validation
  - exact capability/action mapping
  - live adapter availability
        |
        v
AutonomyToolSelector
  - registered exact action bindings only
  - readiness and missing permission status
  - offline and domain preference score
  - data sensitivity ceiling
        |
        v
Task Monitor / Existing TaskExecutionCoordinator
  - existing ledger and journal
  - existing approval and observation binding
  - existing adapter + verification rules
        |
        v
AutonomyExecutionPolicy
  - permission / tool availability
  - fresh observation
  - exact fresh action-bound approval
  - risk-based allow / wait / user handoff
        |
        v
Observation -> Verification -> bounded Feedback/Replan
        |
        v
Explicitly consented domain memory (selected fields only)
```

## 3. Domain routing

Implemented domains:
- ANDROID_OS
- PERSONAL_PROFILE
- HOME_MANAGEMENT
- COMMUNICATION
- PRODUCTIVITY
- FILES
- WEB
- INFORMATION
- UNKNOWN

The classifier is deterministic, multilingual keyword/rule-based routing. Its confidence is a routing hint, not a calibrated probability. Ambiguous requests should be clarified or kept in preview/review mode; the classifier is not a general semantic understanding model.

Personal Profile and Home Management intents carry explicit memory/scope constraints. A domain label does not itself authorize access to contacts, files, notifications, location or third-party accounts.

## 4. Two-pass planning and constraint enforcement

Pass 1 creates an intent frame: domain, coarse risk, targets, explicit prohibitions and unresolved inputs.

Pass 2 uses the selected planner to create a TaskPlan, then locally enforces:
- Draft-only / no-send blocks matching send steps.
- No-publish blocks matching publish/post steps.
- No-delete blocks matching delete/remove steps.
- Explanation-only blocks launch/open/execute steps.
- Consequential action steps become non-retryable, non-reversible and user-approval-gated.

A blocked step receives a BLOCKED title, loses executable capability requirements and is surfaced in plan preview. This is a conservative string/rule policy; it is not a full natural-language constraint solver. Model-generated plan output remains untrusted until locally validated.

## 5. Tool selection

AutonomyToolSelector consumes TaskCapabilityActionPolicy mappings and CapabilityRegistry. It never uses fuzzy matching of display names. It considers:
- exact registered capability ID and action name
- live adapter availability and reported missing permissions
- offline support
- domain and explicitly preferred capability IDs
- data-sensitivity ceilings for the built-in adapters

A result can be SELECTED, PERMISSION_REQUIRED, UNAVAILABLE, UNSUPPORTED or PRIVACY_BLOCKED. Selection is advisory and visible in complex-task preview. It does not request permission, instantiate an approval receipt, dispatch an action or bypass the existing TaskExecutionCoordinator.

## 6. Execution authorization

AutonomyExecutionPolicy is a pure decision gate:
- missing permission or unavailable tool -> blocked
- stale required observation -> blocked
- expired or mismatched approval -> blocked
- low-risk reversible ready action -> may be allowed
- high-risk or irreversible action -> wait for exact approval bound to the exact request and observation; a valid approval may proceed through the existing durable coordinator path
- critical action -> user handoff without adapter invocation

The existing TaskExecutionCoordinator, ActionApprovalStore, execution journal, state machine, action compiler and verification engine remain the authoritative execution path. The v1.0.0 coordinator now invokes AutonomyExecutionPolicy after exact adapter/schema/live-permission validation and before journal claim or adapter side effects. ApprovalStore.matches performs a non-consuming exact receipt/step/request/observation/freshness check at the gate; the existing atomic consume remains required after durable journal checkpoint and immediately before dispatch. Critical-risk actions are handed back to the user without adapter invocation.

## 7. Domain memory and privacy

New persistent categories:
- USER_PROFILE
- HOME_MANAGEMENT
- HOUSEHOLD_ROUTINES
- PEOPLE_CONTEXT

DomainMemoryService requires explicit user consent, enforces per-domain field allowlists, bounds retention to 1–365 days, expires entries on read, and provides delete/clear operations.

The initial allowlist is intentionally narrow (for example: preferred name, language, communication style, household task, routine and shopping preference). It rejects direct contact details, email, precise address/location, passwords, tokens, OTP/PIN, identity-number-like values, bank fields and medical fields.

Backing storage is the existing app-private PersistentAgentMemory SharedPreferences implementation. Values are scrubbed and bounded but the store is not encrypted. This API must not be presented as a secure vault. Room/DataStore migration, asynchronous IO, memory provenance UI, per-record consent revocation and audit UI remain future work.

## 8. Not implemented in this release

- Full end-to-end arbitrary DAG execution for every planner-produced step.
- General semantic parser, confidence calibration, slot-filling conversation manager or robust multilingual negation solver.
- Dedicated Home Management integrations: alarms/calendar provider, home IoT protocols, appliance vendors, inventory scanning, utility accounts or bill-payment workflows.
- Notification-listener capability or background message actions.
- Broad SAF write/create/rename/delete APIs.
- Full Credential Manager integration or credential-value storage.
- Arbitrary shell, root, hidden API or Android permission bypass.
- Automatic call placement, message send, publish, purchases, financial transfers or destructive actions.
- Full ViewModel/foreground-service lifecycle migration and Room-backed asynchronous execution journal.
- Device/emulator instrumentation execution in this build environment.

## 9. Verification

Final v1.0.0 Gradle run:
- 230/230 unit tests passed across 43 XML result suites; 0 failures, 0 errors, 0 skipped.
- `:app:assembleDebug` and `:app:assembleDebugAndroidTest` both succeeded (71 actionable tasks; 9 executed, 62 up-to-date).
- Standalone source smoke suite passed: Core 36 assertions, Agent Runtime 41, Pending UI 25, permission-flow 8/8, Android XML 4/4.
- `git diff --check` passed.
- Debug APK: 11 MB, package `com.eraassistant.mobile.debug`, versionCode 17, versionName `1.0.0-debug`, minSdk 26, targetSdk 35. APK Signature Scheme V2 verified.
- Release APK SHA-256: `c3f436fd5cf536d83364c4fa50793e266d063d87b2c78df1ca8832955bbb8761`.

The instrumentation test APK builds (411 KB) but instrumentation tests were not executed because `adb devices -l` showed no connected device or emulator. Android Lint was not verified in this release run.

## 10. Next architecture increments

1. Move domain memory into encrypted, IO-backed repository with provenance, consent versioning and UI controls.
2. Add a slot-filling dialog manager and structured intent schema instead of extending keyword rules.
3. Bind tool selection results to concrete action schemas, live observation IDs and typed ActionRequest construction.
4. Extend the central gate to every remaining direct/simple device-action, browser, voice and compound-sequence dispatch path; keep one shared authorization policy across all execution surfaces.
5. Add a tested scheduler and Android Calendar/Alarm integration behind user-visible permissions.
6. Add Home Management integrations one provider at a time with scoped credentials, read-only discovery first and user-approved writes.
7. Move task state, browser and voice lifecycle ownership into ViewModel/foreground services where Android lifecycle requires it.
