# ERA Android v0.8.0-dev — Test Report

Last updated: 2026-11-21
Environment: Android 15 / Termux aarch64, OpenJDK 21.0.13.1, Gradle 8.9, Node.js 26.13.1.

## Verification summary

| Test suite | Command | Verified result |
|---|---|---:|
| Android Gradle unit tests | Gradle :app:testDebugUnitTest | **152 tests / 28 suites; 152 passed, 0 failed, 0 errors, 0 skipped** |
| Standalone P0 JVM tests | bash tools/run-p0-unit-tests.sh | **52 tests passed** |
| Core smoke | tools/run-core-smoke.sh | **36 assertions passed** |
| Bounded Agent Runtime smoke | tools/run-agent-runtime-smoke.sh | **41 assertions passed** |
| Pending UI Action smoke | tools/run-pending-action-smoke.sh | **25 assertions passed** |
| Permission-flow checks | tools/check-permission-flow.py | **8 source checks passed** |
| XML validation | tools/run-all-smoke.sh | **4 XML files parsed** |
| Integrated Phone Browser Agent | npm run check && npm test | **syntax check passed; 38/38 tests passed** |
| Diff hygiene | git diff --check | **passed** |

The three JVM smoke suites total 102 assertions (36 + 41 + 25), in addition to eight permission-flow checks and four XML parse checks.

## Regression areas covered
- Plan-declared risk and exact capability/action enforcement.
- One-time approvals bound to a step, exact request and fresh observation; changed, expired, replayed and cross-step receipts are rejected.
- Active coroutine cancellation and recovery-state handling.
- Durable task/action history, restart reconciliation and failed-write behavior.
- Typed capability input schemas and bounded adapter result validation.
- Observation field limits, 64 KB aggregate payload trimming and truncation signaling.
- Memory-key/value sanitization, contextual OTP/PIN redaction and workflow persistence.
- Existing Android routing, pending UI action, permissions, browser policies and Phone Browser Agent semantics.

## Environment notes
Termux emitted a Jansi native-library warning because libc.so.6 is not available in the Android namespace during some JVM shell runs. Gradle unit tests, the P0 harness and the full smoke script nevertheless completed successfully. Android SDK tooling also emitted an SDK XML version warning without failing the test tasks.

## Explicitly not verified
- No APK was assembled or installed.
- No Android instrumentation test, emulator-driven UI journey or physical-screen end-to-end action flow was run.
- The natural-language task preview/monitor remains review-only. A trusted production request builder/call-site must still create a StructuredActionRequest before a plan step can be dispatched through the coordinator.
- Unattended timed retry/backoff is not implemented by the coordinator; retry policy/backoff primitives and bounded counters are present.
- UnifiedBrowserArchitecture has no production consumer; Native WebView and External Chrome are registered as separate adapters.
- Skill matches and learned workflows are review hints, not automatic execution/replay.

The working tree already contained changes before this follow-up. git diff --check verifies whitespace only; this report does not claim that the Git working tree is clean.
