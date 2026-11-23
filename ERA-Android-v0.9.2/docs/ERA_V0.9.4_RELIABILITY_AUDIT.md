# ERA v0.9.4 — Reliability Upgrade Audit

Date: 2026-11-23
Baseline: v0.9.3 / versionCode 15
Release: v0.9.4 / versionCode 16 / versionName 0.9.4-debug
Branch: main
Scope: source-level implementation changes and verification status.

## Implemented

### P1 — Device action outcomes and independent verification
- Added typed Android operation outcomes: SUCCESS, FAILURE, UNSUPPORTED, UNAVAILABLE and CANCELLED, with structured error codes for missing apps, missing hardware, unsupported operations, invalid input, permission/security denial, missing activities and platform errors.
- The Android device capability adapter now preserves the typed result. A negative localized message is no longer wrapped as a successful adapter result.
- Direct instant-action plans carry action-specific acceptance criteria instead of empty criteria.
- Torch state is observed through CameraManager TorchCallback; the adapter waits for the callback and passes the resulting state as verification metadata.
- Volume adjustment/set verification uses the post-action AudioManager stream index and maximum rather than the adapter message.
- App-launch and external-intent actions remain VERIFICATION_UNAVAILABLE / WAITING_USER where the granted Android API surface cannot independently establish the foreground outcome. An intent being accepted is not treated as proof that the target screen or draft is ready.

### P1 — Typed compound-command progression
- Removed localized response-string substring matching from sequence failure classification.
- Added typed step dispositions and sequence progress summaries. Independently verified and executed-but-unverified steps can continue to the next independent command. Failed, not-dispatched, cancelled and recovery-required steps stop and report partial completion.
- Verification uncertainty is not conflated with failure. It still remains visible as requiring user inspection.

### P1 — Guarded plan-to-action connection
- Added a local plan-step compiler for a narrow exact allowlist of reversible app-launch plan steps: WhatsApp, Instagram and Gmail (Gmail only where requested by the goal).
- Task Monitor approval for a supported step compiles a trusted typed request and routes it through TaskExecutionCoordinator, capability validation, journal and verification.
- High/critical, approval-required, irreversible, unknown-title, non-launch and multi-capability steps are not compiled. Unsupported steps remain manual-review only.
- This is a limited bridge, not a claim that arbitrary multi-step AI plans are executable. Text entry, recipient selection, media selection, forms, browser control, send/publish and other plan operations remain manual/review-only until their own trusted typed compilers and verifiers exist.

### P2 — Task identity, memory and readiness
- New TaskExecutionCoordinator runs receive a unique taskRunId separate from the plan fingerprint. The same plan launched twice gets independent persistent namespaces; explicitly reusing a run ID resumes that run. A legacy fingerprint resume option remains opt-in for compatibility.
- Workflow verification timestamps are now written as Unix epoch milliseconds. Schema-v1 values (seconds stored in the EpochMs field) are migrated once by multiplying by 1000.
- Target app readiness checks the specific requested package(s) instead of treating the existence of any launcher app as proof that a requested app is available.
- Planner rules cover common negative target preferences, “do not launch / only explain”, Instagram “do not publish” and message “draft only / do not send” cases. This remains deterministic keyword/negation handling, not a general semantic parser.
- The selected bottom-navigation tab is now saveable across Activity recreation.
- CameraManager TorchCallback is unregistered in DeviceController.close() on Activity destruction.

### Instrumentation source
- Added Android instrumentation smoke tests for MainActivity launch, the four navigation destinations, and restoration of the selected Settings tab after Activity recreation.
- The instrumentation source and test APK compile/package successfully. They were not executed because adb devices -l returned no connected emulator or device.

## Verification results

| Check | Result |
|---|---:|
| Gradle :app:compileDebugKotlin | PASS |
| Gradle :app:testDebugUnitTest | 212 tests, 0 failures, 0 ignored |
| Standalone P0 JVM suite | 73 PASS |
| Core smoke | 36 assertions PASS |
| Agent Runtime smoke | 41 assertions PASS |
| Pending UI Action smoke | 25 assertions PASS |
| Permission-flow source checks | 8 PASS |
| Android XML parsing | 4/4 PASS |
| tools/run-all-smoke.sh | PASS, exit code 0 |
| :app:assembleDebug | BUILD SUCCESSFUL |
| :app:assembleDebugAndroidTest | BUILD SUCCESSFUL |
| APK Signature Scheme v2 | PASS |
| git diff --check | PASS |
| :app:lintDebug | Incomplete: `:app:lintAnalyzeDebug` stalled without producing a report; terminated after repeated no-output polling |
| :app:connectedDebugAndroidTest | Not run: no ADB target connected |
| Physical-device feature validation | Not run in this build |

Debug APK: `~/storage/downloads/ERA-Android-v0.9.4-reliability-upgrade.apk` (10 MB)
Package: `com.eraassistant.mobile.debug`
Version: versionCode `16`, versionName `0.9.4-debug`, minSdk `26`, targetSdk `35`
SHA-256: `28888471aadf5be54e0e5129c92a005bf0bc1edcd6c00a44e40a0936cbd30fb3`
V2 signer certificate SHA-256: `acb8afa549827dfbe4f47614cd104d4de34804d4176a45f4123e60cf870fe194`

Instrumentation APK: `app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk` (411 KB)
SHA-256: `72c286bc087c96ea1fcd2bd90a61dc45c361ef7ef521368364246147d8e5dce4`

## Explicit remaining limitations

### Capability readiness
Target-specific package checks are wired for common names. A full per-action readiness matrix covering package visibility, hardware, API level, restricted profile, runtime permission and service state is not yet wired into all planner requirements and adapters.

### Persistence and main-thread I/O
Task ledger, task state, execution journal, agent dispatch ledger and agent memory still contain synchronous SharedPreferences.commit() operations. Moving serialization and writes to a serialized IO execution boundary or Room/DataStore while preserving durable-before-state-transition semantics is not complete. UI responsiveness under large histories and concurrent persistence writes therefore remains a required follow-up.

### Execution journal capacity
The journal remains fail-closed at 500 records rather than silently evicting unresolved work. Deterministic archival, retention policy, pagination/bounded loading and long-term recovery export are not implemented.

### Retry and recovery
Bounded retry policy helpers and retry counters exist; a fully scheduled exponential-backoff worker with cancellation, capability re-check and journaled retry attempts is not wired. Uncertain or non-idempotent external side effects must not be blindly retried and require user inspection.

### Lifecycle and architecture
MainActivity and ERAScreen remain large. Most screen/execution state has not been moved into ViewModels/SavedStateHandle. Sequence, browser and voice ownership still includes Activity lifecycle; onDestroy cancellation can stop an activity-owned sequence during recreation. Only selected navigation-tab restoration and DeviceController callback cleanup were addressed here. Full Activity/process recreation, foreground/background, active sequence and browser-tab restoration are not verified.

### Planner intent and full plan dispatch
Intent handling is improved for several known negative examples but still uses deterministic keyword rules and can misclassify mixed or ambiguous language. A low-confidence/conflicting intent workflow and robust entity/negation parser are not implemented.
The plan compiler only dispatches a few exact app-launch steps. Dependency-aware execution of full complex AI plans, typed browser actions, contacts, text entry, media selection, forms, state-bound approvals and per-step DAG conflict checks remain incomplete. Raw planner text is never executed.

### Feature boundaries
- Wake-word: contract/stub only; no detection model/runtime is bundled.
- SAF: user-authorized read/list/preview only; write/create/rename/delete are not implemented.
- Notifications: no notification-listener service or notification action capability is declared; unsupported.
- Credentials: native Autofill handoff exists; full AndroidX Credential Manager integration is not claimed.
- Workflow learning: saved verified workflow matches are suggestions; arbitrary validated workflow replay is not implemented.
- Remote Gateway V3: authenticated pairing/presence/heartbeat only. No remote shell, remote action execution, screen streaming or file transfer.
- UnifiedBrowserArchitecture: prototype abstraction with no production consumer. Existing WebView and External Chrome paths remain.
- Android app-launch foreground verification is unavailable without a separately approved and privacy-reviewed API; it remains user-confirmed.

## Safety invariants retained
- No raw model-generated shell command or arbitrary tool string is executed.
- High/critical, send/publish/submit/pay/delete, credential and consequential UI actions remain behind explicit review/confirmation.
- Adapter return values are not treated as independent verification.
- Unavailable verification remains user-visible and is not reported as machine success.
- Interrupted or uncertain external side effects require recovery inspection before retry.
