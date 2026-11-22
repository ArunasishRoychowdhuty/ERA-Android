# ERA v0.8.0-dev — Architecture Weakness Audit & Resolution Status

Last updated: 2026-11-21
Scope: Architecture reliability, execution verification, state machine lifecycle, crash recovery, memory, and browser abstraction.

---

## Status of Previously Identified Architectural Weaknesses

| Finding / Weakness | Prior Status | Current Status | Resolution Details & Implementation References |
|---|---|---|---|
| **Disconnected execution engines & lack of unified capability interface** | OPEN | **IMPLEMENTED; task dispatch remains gated** | Six adapters are registered in the common `CapabilityRegistry`, with per-action parameter schemas and output validation. The separate old `AgentCapabilityRegistry` remains as legacy compatibility/test code. The natural-language task UI does not yet create trusted structured requests. |
| **Lack of strict execution state machine and lifecycle validation** | OPEN | **RESOLVED at source/test level** | `TaskStateMachine.kt` validates transitions, persists bounded task/action histories before state changes, and restores unfinished work as `RECOVERY_REQUIRED` after restart. |
| **Absence of independent action verification engine** | OPEN | **IMPLEMENTED FOUNDATION; validators incomplete** | `VerificationEngine.kt` requires post-observation and emits typed outcomes, but adapter-specific, domain-aware expected-state validators still need work. |
| **No durable execution journal or idempotency protection against duplicate actions** | OPEN | **IMPLEMENTED PRIMITIVES** | Idempotency, SHA-256 fingerprints, checkpoints and retry counters are persisted; interrupted work is not auto-replayed. The coordinator does not yet orchestrate timed automatic retry/backoff. |
| **Unbounded replanning and potential infinite feedback loops** | OPEN | **PARTIALLY INTEGRATED** | DAG/capability availability checks run in plan preview and bounded replans are wired to the coordinator. Replan proposals are displayed for manual review, not auto-applied; effective feedback awaits production task dispatch. |
| **Misrepresented browser boundaries between WebView and Chrome Accessibility** | OPEN | **PARTIALLY INTEGRATED** | Native WebView and External Chrome adapters are registered separately; Chrome remains foreground Accessibility-only. `UnifiedBrowserArchitecture.kt` is a prototype abstraction without a production consumer. |
| **Lack of unified multi-modal observation model** | OPEN | **RESOLVED** | Created `NormalizedObservation.kt` normalizing Android Accessibility hierarchies, Native WebView DOM snapshots, Chrome Accessibility nodes, and downscaled visual frames into a unified structure with timestamping, staleness detection, and bounded size limits (up to 500 elements and a 64 KB aggregate payload, with truncation signalling). |
| **Uncontrolled skill execution and risk of arbitrary command execution** | OPEN | **REGISTRY/VALIDATION ONLY** | Skill metadata is validated and surfaced as a task-preview hint; no production skill execution loop is connected. |
| **Lack of persistent agent memory and privacy protection** | OPEN | **PARTIALLY INTEGRATED** | Persistent memory has bounded storage, key/value sanitization and legacy-data migration. Privacy filtering covers sensitive keys and specific token/email/contextual code patterns; it is not a universal secret detector. |
| **No workflow learning from successful executions** | OPEN | **CALLBACK-WIRED, DISPATCH-GATED** | Verified coordinator completions can produce workflow hints, but ordinary task UI will not create these completions until typed production dispatch is connected; learned workflows are not auto-replayed. |
| **Lack of offline capability awareness and graceful degradation** | OPEN | **PREVIEW-INTEGRATED** | Offline capability readiness and deterministic planning fallback are used in task preview; this does not establish fully offline execution for every adapter. |
| **Gradle test execution unverified in Termux environment** | OPEN | **RESOLVED / UPDATED** | Final follow-up run: **152 tests across 28 suites; 152 passed, 0 failed, 0 errors, 0 skipped**. |

---

## Verification Audit Summary

- **Gradle Unit Tests:** 152 PASS across 28 suites (0 Failures, 0 Errors, 0 Skipped).
- **Smoke Assertions:** 110 PASS (36 Core + 41 Agent Runtime + 25 Pending UI Action + 8 Permission Flow).
- **Android XML Manifest & Config Checks:** 4 PASS.
- **Phone Browser Agent Regression Tests:** 38 PASS.
- **Diff Hygiene:** `git diff --check` PASS (no whitespace errors; working tree remains modified).


## Follow-up verification and corrected status — 2026-11-21

The following items were changed and re-tested after the earlier audit. Any older 128 tests / 24 suites entry is superseded: the latest Gradle report is **152 tests across 28 suites**, all passing.

| Area | Updated status | Current facts |
|---|---|---|
| Task/action lifecycle persistence | **RESOLVED at source/test level** | SharedPreferences task-state history persists before transitions; unfinished process-restored work moves to RECOVERY_REQUIRED. |
| Plan risk/capability enforcement | **RESOLVED in typed coordinator path** | Risk and required capabilities are carried from plan to ledger and checked against exact adapter/action mappings. |
| Consequential approval binding | **RESOLVED in typed coordinator path** | Ephemeral one-time receipt binds request fingerprint, task step and fresh observation; changed/replayed/expired receipts are rejected. |
| Capability parameter/output validation | **RESOLVED for six registered adapters** | Typed per-action schemas and bounded result identity/payload checks are active in the common registry. |
| Active execution cancellation | **RESOLVED in coordinator path** | Cancels active adapter coroutine and enters recovery-safe state. |
| Observation payload bounds | **RESOLVED at normalizer/model level** | 500-element ceiling, 64 KB aggregate budget and truncation signalling. |
| P0/smoke harness coverage | **RESOLVED** | Standalone source lists include new pure Kotlin files; P0 52 tests and all smoke stages pass. |
| Natural-language task to typed action dispatch | **OPEN / MANUAL REVIEW** | No trusted production StructuredActionRequest builder/call-site yet. Plan approval is review-only and does not dispatch. |
| Adapter overlay completion to coordinator callback | **OPEN** | User-mediated pending-action completion is not fully connected to the common verification lifecycle. |
| Timed automatic retry/backoff | **OPEN** | Policy helpers and retry counters exist; automatic timed retry scheduling is not wired. |
| Unified browser abstraction consumer | **OPEN / PROTOTYPE** | Production uses the two browser adapters directly; UnifiedBrowserArchitecture has no production consumer. |
| Skill execution / automatic workflow reuse | **OPEN / HINT ONLY** | Registry and learning foundations exist; no automatic skill or learned-workflow replay. |
| Android UI/instrumented end-to-end coverage | **NOT RUN** | No APK was built or installed and no full screen journey was executed during this follow-up. |

Do not interpret passing unit tests or git diff --check as proof of complete in-app autonomous execution or a clean Git working tree.
