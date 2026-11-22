# ERA v0.8.0-dev — Upgrade Audit and Implementation Plan

Audit date: 2026-11-22
Project: `projects/ERA-test/ERA-Android-v0.8.0-dev`
Device environment: Android 15 / API 35 / Termux / arm64
Baseline version: `0.8.0-dev` (versionCode 11)

## Workspace preservation

The project was already a heavily modified working tree before this upgrade began. Those changes were preserved. A pre-upgrade archive was created at:

`~/ERA-v0.8.0-dev-pre-upgrade-20261122.tar.gz`

Do not reset or clean the parent repository to make the worktree appear clean. New changes are additive and should be reviewed against the user's existing edits.

## What is already implemented

- A common `CapabilityRegistry` and six registered Android/browser/visual/search adapters.
- A local-first planning route with Gemini and OpenAI-compatible remote planner configuration.
- API-key encryption using AES-GCM with a key held in Android Keystore.
- A task DAG, execution coordinator, durable task ledger, state machine, execution journal, idempotency checks and recovery-required states.
- Exact, short-lived, one-time approval receipts in `ActionApprovalStore`, bound to the step, request parameters and fresh observation.
- Accessibility tree capture, screen-state fingerprinting and one-shot overlay actions.
- A native WebView browser adapter and an external Chrome Accessibility adapter.
- Bounded normalized observations and a baseline memory/privacy filter.
- Optional Shizuku integration with fixed, read-only diagnostic operations.
- Foreground speech recognition and text-to-speech.

## Confirmed integration gaps

### P0 — End-to-end action dispatch

The planner can produce a task plan and the capability adapters are registered, but the natural-language task UI still does not create a trusted `StructuredActionRequest` and pass it through `TaskExecutionCoordinator.executeStep()`. The plan-review approval shown in the UI is not an execution approval. Arbitrary planner prose must not be treated as executable shell or OS instructions.

**Implementation direction:** create a local, allow-listed action compiler that maps a reviewed step plus a fresh observation to one exact typed adapter/action/parameter set. Reject ambiguous, unsupported or unbound requests.

### P0 — Voice approval lifecycle

The generic task coordinator already has exact-action approval receipts, but there was no shared voice-command approval gate wired into the app's main command path.

**Implemented in this upgrade:** `approval/VoiceApprovalEngine.kt` and its tests. The engine keeps one process-local pending request, binds it to a SHA-256 fingerprint of the exact action context, expires it, consumes it once, and disarms on unrelated speech or a changed binding. MainActivity now uses it in the contact-to-dialer confirmation workflow. This opens only the Android dialer; ERA does not place calls.

**Still open:** connect the same gate to typed task dispatch, selected browser actions and other user-visible consequential workflows. Each caller must supply its own exact action binding and freshly checked target. Do not use one general approval to authorize a batch of unrelated actions.

### P0 — Credentials

The current API-provider key is stored using Android Keystore-backed encryption. There is no integrated Android Credential Manager/password-manager broker for website credentials. Accessibility snapshots intentionally omit password values and editable values. Preserve that boundary: use the system credential provider/autofill flow to fill credentials without exporting raw secrets to a model, logs, screenshots or task history.

### P0 — Local AI model

The current `LOCAL_ONLY` planner path is a deterministic local planner/preview path, not a fully integrated on-device LLM inference runtime. Gemini and OpenAI-compatible remote planning are configured separately. A genuine local model backend still needs a provider adapter, model lifecycle management, resource budgets and explicit routing policy.

### P1 — Phone-wide capability coverage

The current registered adapters cover device controls, browser interaction, Accessibility UI, visual inspection and search/recognition. A complete file-management engine, general notification actions, broad app-specific workflows and a Credential Manager integration are not yet present. File access should use Storage Access Framework document/tree grants and scoped URI access rather than broad storage permissions.

### P1 — Remote connectivity

The existing Shizuku bridge is an optional local system bridge with fixed read-only diagnostic operations. It is not a remote VPN or persistent remote-control transport. A separate private-network remote gateway is still required (device identity, authenticated encrypted channel, reconnect/session recovery and a typed command protocol). Do not expose ADB or a generic shell port to the public internet.

### P1 — Voice availability

Speech recognition currently runs in a user-started foreground session and is stopped when ERA leaves the foreground. There is no verified always-on wake-word service or guaranteed offline speech recognition. Keep microphone activation visible and user initiated until a separately reviewed foreground-service/wake-word design is implemented.

### P1 — Browser scope

Native WebView DOM access and external Chrome Accessibility access are separate implementations. External Chrome access is limited to exposed Accessibility nodes; it is not Chrome CDP, browser-cookie access or unrestricted cross-origin DOM access. The unified browser abstraction remains a prototype without a production consumer.

## Implementation order

1. **Action compiler and coordinator bridge:** deterministic allow-listed mapping, current observation binding, adapter/action schema validation, exact approval receipt, cancellation and post-action verification.
2. **Voice approval integration:** generalize the new engine to the typed coordinator and supported browser/device actions; preserve per-action binding, expiry and one-time consumption.
3. **Credential broker:** integrate Android Credential Manager/autofill with user-selected credentials and local secure fill; never send raw secrets to a cloud planner.
4. **File and notification capabilities:** SAF URI grants, explicit folder selection, least-privilege notification actions and verified adapter contracts.
5. **Local AI backend:** add a proper on-device inference provider behind the existing provider interface, with model/resource discovery and fallback routing.
6. **Remote Connectivity V3:** private VPN/overlay network, device pairing, authenticated gateway, foreground service, heartbeat, reconnect and audit logging; no public ADB listener.
7. **Reliability and release validation:** Android instrumentation/UI tests, network-loss tests, permission-revocation tests, APK assembly/signature verification and user-approved install/runtime validation.

## Verification performed during this audit

- Pre-change source smoke harness: 102 assertions, 8 permission-flow checks and 4 Android XML parse checks passed.
- Updated portable P0 unit suite after implementation: 59 tests passed, 0 failed.
- Updated Android Gradle unit suite: 159 tests, 0 failures, 0 errors, 0 skipped.
- `git diff --check`: passed.
- `:app:assembleDebug`: BUILD SUCCESSFUL. The 12 MB debug APK is at `app/build/outputs/apk/debug/app-debug.apk` and was copied to `~/storage/downloads/ERA-0.8.0-dev-voice-approval-debug.apk`.
- APK SHA-256: `5f74044de53cfaeaa7aaff980d8a28af922b23e5302ce3b7930c8f6eaa8f10e7`.
- `apksigner verify --verbose --print-certs`: signature verification passed using APK Signature Scheme v2; package name from Termux-native `aapt2`: `com.eraassistant.mobile.debug`.
- Full Android UI execution, APK installation, voice-recognition interaction and live remote-network testing have not been performed as part of this audit.

## Acceptance criteria for the full upgrade

- Planner output is never directly executed as shell or arbitrary OS code.
- Every execution request is typed, allow-listed, schema-validated and bound to a fresh observation.
- Low-risk actions do not repeatedly ask for permission after the required Android system grant exists.
- Consequential actions ask for a specific, spoken or in-app confirmation; an approval is scoped to one exact action and expires.
- Voice approval cannot bypass Android's runtime permission or system-settings screens.
- Credentials are filled through a secure credential provider and are never included in AI prompts or persistent logs.
- A stale target, changed action, expired approval, denied permission, cancellation or uncertain process restart prevents replay.
- The action outcome is independently verified or reported as unverified.
- Remote connectivity is private, authenticated, encrypted and resilient to network changes without exposing ADB publicly.
