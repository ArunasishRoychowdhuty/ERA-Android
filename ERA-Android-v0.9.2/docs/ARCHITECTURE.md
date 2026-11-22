# ERA Android Assistant Architecture — v0.8.0-dev

## Architectural Overview

ERA (Execution Reliability Architecture) is a native Android AI assistant built in Kotlin and Jetpack Compose. Version 0.8.0-dev introduces a complete reliability overhaul, separating planning, capability routing, durable execution, observation normalization, independent outcome verification, and persistent agent memory.

```text
                                User Request (Voice / Text)
                                            │
                                            ▼
                           ┌───────────────────────────────────┐
                           │    CommandRouter (Deterministic)  │
                           └─────────────────┬─────────────────┘
                                             │
                       ┌─────────────────────┴─────────────────────┐
                       ▼                                           ▼
             Direct Instant Command                      Task Planning (DAG)
         (Volume, Dial, Search, App Launch)          (TaskPlanDAG / Topological Sort)
                       │                                           │
                       │                                           ▼
                       │                               ┌───────────────────────┐
                       │                               │ Planner Feedback Loop │◄───┐
                       │                               │ (Bounded Replanning)  │    │
                       │                               └───────────┬───────────┘    │
                       │                                           │                │
                       ▼                                           ▼                │
         ┌─────────────────────────┐               ┌──────────────────────────────┐ │
         │   Capability Registry   │               │   TaskExecutionCoordinator   │ │
         │   (Discovery/Validation)│               └───────────────┬──────────────┘ │
         └─────────────┬───────────┘                               │                │
                       │                                           ▼                │
                       │                             ┌───────────────────────────┐  │
                       │                             │   Execution State Machine │  │
                       │                             │ (CREATED..VERIFIED..SUCC) │  │
                       │                             └─────────────┬─────────────┘  │
                       │                                           │                │
                       ▼                                           ▼                │
       ┌─────────────────────────────────┐           ┌───────────────────────────┐  │
       │    Unified Capability Adapters  │           │ Durable Execution Journal │  │
       │  - AndroidDeviceAdapter         │           │ (Idempotency / Recovery)  │  │
       │  - NativeWebViewBrowserAdapter  │           └─────────────┬─────────────┘  │
       │  - ExternalChromeAdapter        │                         │                │
       │  - AccessibilityUiAdapter       │                         │                │
       │  - VisualInspectionAdapter      │                         │                │
       │  - SearchRecognitionAdapter     │                         │                │
       └───────────────┬─────────────────┘                         │                │
                       │                                           │                │
                       ▼                                           │                │
             Capability Execution ─────────────────────────────────┤                │
                       │                                           │                │
                       ▼                                           │                │
         ┌───────────────────────────┐                             │                │
         │   Normalized Observation  │                             │                │
         │ (Android/WebView/Chrome)  │                             │                │
         └─────────────┬─────────────┘                             │                │
                       │                                           │                │
                       ▼                                           │                │
         ┌───────────────────────────┐                             │                │
         │    Verification Engine    │─────────────────────────────┴────────────────┘
         │ (VERIFIED / NOT_VERIFIED /│ (Feeds verification outcomes back to
         │  RETRY / REPLAN / N/A)    │  State Machine, Journal, and Planner)
         └───────────────────────────┘
```

---

## 1. P0 — Core Reliability Architecture

### A. Unified Capability Interface
- **Contract (`CapabilityContract.kt`):** All execution engines implement `CapabilityAdapter`. Each capability publishes a typed `CapabilityDescriptor` defining its ID, supported action names, required Android permissions, execution constraints, and offline readiness.
- **Requests & Results:** Actions are dispatched using strongly typed `StructuredActionRequest` (action ID, parameters, timeout, confirmation flags) and return `StructuredActionResult` (success, failure, waiting-user with elapsed duration and observation data).
- **Registry (`CapabilityRegistry.kt`):** Central authority for capability registration, action-to-adapter resolution, dynamic availability checking, and discovery. Rejects duplicate action name registrations.
- **Unified Adapters:**
  1. `AndroidDeviceCapabilityAdapter`: Intent-based app launching, URL browsing, dialer prep, volume adjustment.
  2. `NativeWebViewBrowserCapabilityAdapter`: ERA in-app multi-tab WebView automation (DOM, Shadow DOM, same-origin frames).
  3. `ExternalChromeAccessibilityCapabilityAdapter`: Standalone Chrome app inspection via foreground Android Accessibility.
  4. `AccessibilityUiCapabilityAdapter`: Android screen hierarchy capture and one-shot snapshot-bound action approvals.
  5. `VisualInspectionCapabilityAdapter`: Manual overlay-triggered screenshot frame inspection.
  6. `SearchAndRecognitionCapabilityAdapter`: Local contact search, product shopping queries, and visual product recognition.

### B. Execution State Machine (`TaskStateMachine.kt`)
Enforces a strict, inspectable lifecycle:
```text
CREATED → VALIDATING → READY → RUNNING → OBSERVING → VERIFIED → SUCCEEDED
                                   │           │           ▲
                                   ▼           ▼           │
                              WAITING_USER  RETRYABLE_FAILURE
                                   │           │
                                   ▼           ▼
                           RECOVERY_REQUIRED  FAILED / CANCELLED
```
- **Validation:** Every transition is validated against `VALID_TASK_TRANSITIONS` and `VALID_ACTION_TRANSITIONS`. Invalid transitions are rejected with typed `TransitionOutcome.Rejected`.
- **Granular Tracking:** Maintains task-level and action-level execution progress, retry counts, timestamps, and audit history.
- **Crash Recovery:** `reconcileAfterRestart()` transitions interrupted active states (`RUNNING`, `OBSERVING`, `WAITING_USER`) to `RECOVERY_REQUIRED` on process startup to prevent unsafe automatic re-execution.

### C. Verification Engine (`VerificationEngine.kt`)
- Decouples action execution from goal verification. An adapter returning `SUCCESS` indicates only that the instruction dispatched, not that the desired system state was achieved.
- Compares post-execution normalized observations against expected UI elements, state conditions, and acceptance criteria.
- Returns typed results: `VERIFIED`, `NOT_VERIFIED`, `RETRY_REQUIRED`, `REPLAN_REQUIRED`, or `VERIFICATION_UNAVAILABLE`.

### D. Durable Execution Journal & Recovery Engine (`ExecutionJournal.kt`)
- **Idempotency:** Assigns unique action IDs, idempotency keys, and SHA-256 action fingerprints.
- **Duplicate Prevention:** Rejects duplicate action claims and detects conflict if an existing key is reused for an altered payload.
- **Bounded Retries:** Enforces `RetryPolicy` with exponential backoff and maximum retry budgets.
- **Crash Reconciliation:** Marks in-progress journal entries as `INTERRUPTED_RECOVERY_REQUIRED`. Automatic replay of consequential actions without fresh user consent is strictly blocked.

---

## 2. P1 — Intelligence and Orchestration

### A. Planner Feedback Loop (`PlannerFeedbackLoop.kt`)
- Controlled feedback mechanism feeding execution outcomes, observations, and verification results back to the planner.
- Bounded replanning enforces a maximum of 3 replan iterations to prevent infinite replanning cycles.

### B. Structured Task Planning & DAG Validation (`TaskPlanDAG.kt`)
- Models multi-step tasks as a Directed Acyclic Graph (DAG).
- Validates graph integrity using Kahn's algorithm: detects cycles, computes topological execution orders, and identifies ready parallel actions whose prerequisites have completed.

### C. Unified Browser Architecture (`UnifiedBrowserArchitecture.kt`)
- Abstraction: `UnifiedBrowserTarget` isolates ERA Native WebView and External Google Chrome.
- **Native WebView:** Multi-tab sandbox, DOM semantic snapshotting (400 nodes / depth 10), open Shadow DOM, same-origin iframe traversal, ref-bound actions.
- **External Chrome Target:** Foregrounds Android Accessibility tree automation only. Transparently enforces boundary limitations: no DOM access, no CDP, no cookie/profile access, and no address-bar value exposure.

### D. Normalized Observation Model (`NormalizedObservation.kt`)
- Common model normalizing observations across Android Accessibility, WebView DOM, External Chrome, and Visual Inspection.
- Enforces size bounds (up to 500 elements and a 64 KB aggregate payload), staleness detection (configurable max age), and privacy scrubbing.

---

## 3. P2 — Advanced Agent Architecture

### A. Reusable Skill Engine (`SkillEngine.kt`)
- Structured skill definitions containing metadata, goal patterns, preconditions, capability requirements, execution steps, and verification rules.
- Pre-registration validation strictly rejects dangerous commands (`rm -rf`, raw system manipulation).

### B. Persistent Agent Memory & Privacy Filter (`AgentMemory.kt`)
- Modular storage for user preferences, verified workflows, mistake patterns, and configuration with 100-record category limits.
- `PrivacyFilter` automatically scrubs Google API keys, OpenAI API keys, bearer tokens, passwords, OTPs, CVVs, credit cards, and email addresses.

### C. Workflow Learning Engine (`WorkflowLearningEngine`)
- Extracts verified, repeatable action sequences from succeeded tasks and stores generalized templates for recurrent goals.

### D. Offline Operation Manager (`OfflineOperationManager.kt`)
- Tracks capability offline support and gracefully degrades to local deterministic planning when offline.


## 4. Production dispatch boundary (verified 2026-11-21)

The coordinator accepts structured requests only after plan-declared capability/action mappings, plan-level risk, request schemas, fresh observation binding and (where consequential) an exact one-time approval receipt are satisfied. Its active coroutine is cancellable, dispatch checkpoints are journaled, and task/action lifecycle history is persisted before transition.

The current natural-language task preview/monitor is still a **review workflow**, not a complete autonomous dispatcher. It has no trusted production builder/call-site that converts a plan step plus a fresh user-selected target into StructuredActionRequest. A plan-step review approval therefore does not authorize or dispatch an OS/browser action. This boundary remains until exact action details can be displayed and confirmed.

Skill and learned-workflow matches are surfaced as hints only. Planner replan proposals are surfaced for manual review. BoundedAgentRuntime / AgentCapabilityRegistry remains a legacy compatibility/test path, and UnifiedBrowserArchitecture has no production consumer.


## 5. Optional Shizuku system bridge (2026-11-22)

`ShizukuBridgeManager` separates server availability, ERA permission, and UserService connection state. It asks for Shizuku permission only after a user taps Connect. The non-daemon `EraShizukuUserService` exposes fixed read-only diagnostics via a small Binder contract; it never accepts arbitrary command text. Its allowlist, bounded output, operation descriptions and explicit non-goals are documented in `docs/SHIZUKU_INTEGRATION.md`. Shizuku runs with the Android shell identity (UID 2000 on this device), not root. A server restart invalidates the live bridge and requires reconnecting.
