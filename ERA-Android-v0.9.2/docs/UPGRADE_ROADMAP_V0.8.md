# ERA Android v0.8 — Upgrade Roadmap and Architecture Status

Last updated: 2026-11-21
Working folder: ERA-Android-v0.8.0-dev
Current version: v0.8.0-dev / versionCode 11

Verification: **152 Gradle tests / 28 suites; 52 P0 JVM tests; 102 smoke assertions; 8 permission checks; 4 XML parses; 38 Phone Browser Agent tests**.

## P0 — Core reliability

| Component | Status | Verified implementation / remaining condition |
|---|---|---|
| Capability contract and registry | **Implemented** | Six production adapters are registered. Each has an action-specific parameter schema; registry validates required/undeclared fields, types, bounds and bounded adapter outputs. |
| Plan risk/capability enforcement | **Implemented in typed coordinator path** | Risk, required capabilities, reversibility and approval metadata are carried from plan to ledger; exact adapter/action mappings are checked before dispatch. |
| Consequential-action approval | **Implemented in typed coordinator path** | Ephemeral receipt binds exact request, step and fresh observation; 60-second expiry, atomic consumption, no restart replay. |
| Task/action state machine | **Implemented with durable history** | SharedPreferences lifecycle history commits before transitions. Unfinished process-restored work becomes RECOVERY_REQUIRED. |
| Execution journal | **Implemented primitives** | Idempotency, fingerprints, durable checkpoints and retry counters exist. Automatic delayed retry/backoff orchestration remains incomplete. |
| Task execution coordinator | **Partially integrated** | Typed dispatch path validates capability, risk, freshness, schema, approval, cancellation and output. Natural-language plan UI has no trusted production StructuredActionRequest builder/call-site yet; plan approval is review-only. |
| Independent verification | **Implemented foundation** | Typed verifier consumes normalized post-observations; stronger adapter-specific expected-state validators are still needed. |

## P1 — Intelligence and orchestration

| Component | Status | Verified implementation / remaining condition |
|---|---|---|
| Task Plan DAG | **Integrated for plan preview** | Validates dependencies, exact registered adapter/action mappings and current availability. |
| Planner feedback loop | **Wired, review-gated** | Bounded replan loop is passed to coordinator; proposed plans are displayed for manual review and never auto-applied. Actual feedback is limited until production action dispatch exists. |
| Task execution monitor | **Integrated** | Separates plan-review approval from execution; active capability is surfaced during typed dispatch. |
| Normalized observations | **Implemented with bounds** | Up to 500 elements, 64 KB aggregate character budget, bounded metadata and per-field sizes; live normalizers trim overflow and mark snapshots truncated. |
| Native WebView / External Chrome | **Adapters integrated separately** | Native WebView has its in-app DOM route; external Chrome is foreground Accessibility-only. UnifiedBrowserArchitecture is a prototype without a production consumer. |

## P2 — Advanced agent foundations

| Component | Status | Verified implementation / remaining condition |
|---|---|---|
| Skill Engine | **Registry/validation only** | Skill metadata is validated and surfaced as a preview hint; no production skill execution loop is connected. |
| Persistent Agent Memory | **Partially integrated** | Bounded store, key/value sanitization and legacy-data migration; learned-workflow retrieval is a hint, not automatic reuse. |
| Privacy Filter | **Implemented baseline** | Sensitive-key blocking, provider key/bearer/long-token/email and contextual OTP/PIN scrubbing; not a universal arbitrary-secret classifier. |
| Workflow Learning | **Callback-wired, dispatch-gated** | Learns from verified coordinator completions; ordinary task UI will not create those completions until typed production dispatch is connected. |
| Offline Operation Manager | **Preview-integrated** | Offline capability awareness and deterministic plan fallback are used during task preview. |

## Test verification
- Gradle: **152/152**, 28 suites.
- Standalone P0 JVM: **52/52**.
- Core / Agent Runtime / Pending UI smoke: **36 / 41 / 25 assertions**.
- Permission flow: **8 checks**; XML: **4 files**.
- Phone Browser Agent: **38/38**, syntax check passed.
- git diff --check: passed.

## Remaining integration work
1. Build a trusted typed-request generator from an explicit user-selected target and a fresh UI observation. Do not translate arbitrary planner prose into OS commands.
2. Add UI that displays exact action, target, parameters and risk; require fresh confirmation for consequential actions and then call bindExplicitUserApproval() / executeStep().
3. Tie WAITING_USER adapter-overlay completion back to the coordinator and verification callback.
4. Add adapter-specific independent postcondition verifiers and actual timeout/backoff scheduling.
5. Either migrate the legacy BoundedAgentRuntime to the common capability registry or keep it isolated as a compatibility/policy-test runtime.
6. Add Android instrumentation/device-flow tests and a separately requested APK build/install validation.

No APK assembly or Android UI end-to-end test was run. Existing working-tree changes were preserved; git diff --check passing does not mean the working tree is clean.
