# Changelog

## v0.9.2 — Remote Connectivity V3 Gateway and Client Hardening (2026-11-22)

### P1 — Deployable private gateway
- Added a standard-library Python/SQLite gateway with loopback-only binding, Tailscale Serve deployment guidance and a locked-down systemd unit.
- Added 128-bit one-time pairing codes, short expiry, atomic single-use consumption, per-source rate limits, and HMAC digests for pairing codes and device bearer tokens.
- Added strict `presence:write` scope enforcement, heartbeat nonce replay protection, device expiry/revocation, admin-only device listing and server-side token revocation.
- Added integration tests for pairing, concurrent redemption, token hashing, heartbeat, replay rejection, expiry/revocation, invalid scope, admin auth, rate limiting and loopback-only configuration.
- Added the complete V3 deployment guide. Live deployment to a user-owned tailnet and real phone pairing remain pending.

### P1 — Android client hardening
- Centralized heartbeat URL construction and validated the device ID before use.
- Explicitly reject an expired persisted session and stop using a token outside its valid lifetime.
- The client accepts only protocol V3 and the exact `presence:write` scope. No command execution channel or remote-control API was introduced.

### Release version and verification
- App versionCode `14`, versionName `0.9.2`; debug variant appends `-debug`.
- Android Gradle suite: **182/182 pass across 32 suites**; 0 failures, errors or skipped tests.
- Standalone P0 suite: **72/72 pass**; source smoke: **102/102 assertions pass**; permission flow: **8/8 checks pass**; Android XML: **4/4 parse pass**.
- Private gateway unittest suite: **13/13 pass**, including admin device-list protocol/auth validation.
- `git diff --check`: pass. APK build: **BUILD SUCCESSFUL**.
- APK: `~/storage/downloads/ERA-0.9.2-debug.apk`, 9.9 MB, package `com.eraassistant.mobile.debug`, versionCode `14`, versionName `0.9.2-debug`, minSdk `26`, targetSdk `35`.
- APK Signature Scheme v2 verified. SHA-256: `2f5fb74760ecde559ebd3498837a96c14155e3e93c955adfc5474849c5883754`.
- Gateway bundle is staged at `~/storage/downloads/ERA-Remote-Gateway-V3.tar.gz` (19 KB), SHA-256 `ed00617dcc0fcf90cb0cdaddfd3b0505ab86c04abdfc83ddb3a20572596bb17d`.
- The APK is staged but not installed. The private gateway is implemented and locally tested but not yet deployed to the user's tailnet.

## v0.9.1 — Remote Connectivity V3 Client Foundation (2026-11-22)

### P1 — Private gateway presence client
- Added HTTPS-only V3 pairing-code exchange. The client requests only the `presence:write` scope and validates protocol version, token shape, expiry, device identity and heartbeat interval.
- Added Android Keystore AES-GCM protection for the remote gateway bearer token; the one-time pairing code is cleared after pairing and is not persisted.
- Added a user-started Android `connectedDevice` foreground session with a visible Stop notification, authenticated heartbeat, 401/403 stop behavior and bounded exponential reconnect backoff.
- Added an endpoint and pairing UI, local unpair flow, status reporting, and protocol documentation in `docs/REMOTE_CONNECTIVITY_V3.md`.
- No public ADB listener, shell, generic RPC, remote command queue, screen stream or remote-control action API was introduced.
- The private gateway/server is not deployed. End-to-end pairing and heartbeat remain unverified until a real private HTTPS gateway is configured.

### Android 15 foreground-service policy
- Uses `connectedDevice`, with `FOREGROUND_SERVICE_CONNECTED_DEVICE` and the `CHANGE_NETWORK_STATE` prerequisite, rather than a long-running `dataSync` service subject to Android 15's six-hour daily limit.
- Session start remains user initiated and notification-visible. Session restart after process death is manual.

## v0.9.0 — Structured Execution, Scoped Storage and Local Model Routing (2026-11-22)

### P0 — Typed action compiler and unified dispatch
- Added `ActionIntentCompiler`, a deterministic allow-list compiler for supported instant commands: flashlight, volume, app launch, settings, camera, dialer-open, SMS/WhatsApp draft, web search, map search and HTTPS website navigation.
- Supported direct commands now create a unique one-step task and dispatch through `CapabilityRegistry`, `ExecutionJournal`, `TaskExecutionCoordinator` and `VerificationEngine`; unsupported or ambiguous commands fail closed.
- Compound command sequences run each compiled step through the same durable coordinator. Cancellation now cancels the active coordinator as well as the sequence.
- Added exact capability mappings for device control, dialer preparation, message drafts and web navigation. Remote planner text is not executable code and is not passed to this command compiler.
- Adapter success is not treated as proof of user outcome. Actions without independent post-observation criteria remain `WAITING_USER` for user inspection and confirmation.

### P0 — Scoped file access
- Added a system folder picker and persisted read-only Storage Access Framework tree grant.
- Added bounded directory listing (maximum 100 items) and local-only preview for plain text, Markdown, CSV, JSON, XML and YAML files (64 KB input limit, 3500 character preview).
- Added a registered file-storage capability adapter. No broad storage permission, arbitrary path access, write, rename or delete operation is enabled.
- Added a UI to choose/revoke folder access, browse folders and preview supported files.

### P0 — Native credential handoff
- ERA's in-app WebView is marked Autofill-capable and can request Android's configured Autofill/password-manager UI.
- ERA does not read, store or forward the credential value from the system Autofill service. This is an Android Autofill bridge, not a direct AndroidX Credential Manager API integration.

### P1 — On-device model endpoint
- Added an optional Ollama planner provider that accepts only an HTTP loopback endpoint at `localhost`.
- Added a user-triggered `/api/tags` model-availability check and a strict JSON planner request/response boundary.
- No model weights or Ollama runtime are bundled in the APK; the user must install/start Ollama locally and download the selected model.
- Local Ollama planner inputs exclude screen images and credentials. It produces a task plan only; it does not execute actions. The source is reported as `ON_DEVICE`.

### Verification
- Android Gradle unit suite: **174 tests across 31 suites — 174 PASS, 0 FAIL, 0 ERRORS, 0 SKIPPED**.
- Standalone P0 JVM suite: **72 PASS**.
- Source smoke: **102 assertions PASS** (Core 36, Agent Runtime 41, Pending UI 25); permission-flow **8 PASS**; Android XML **4 PASS**.
- `git diff --check`: **PASS**.
- `:app:assembleDebug`: **BUILD SUCCESSFUL**. APK metadata: package `com.eraassistant.mobile.debug`, versionCode `12`, versionName `0.9.0-debug`, minSdk `26`, targetSdk `35`.
- APK size: **10 MB**; SHA-256: `491efc20f3778849bdfc58ab166436fcc840d22249dfbda1b8960f1d016c076a`.
- APK Signature Scheme v2 verified with the Android Debug certificate. Staged at `~/storage/downloads/ERA-0.9.0-debug.apk`; not installed.
- Remote Connectivity V3 still requires a separately deployed authenticated transport/gateway. The Shizuku bridge is local-only and is not represented as remote access.

## v0.8.0-dev — Shizuku System Bridge (2026-11-22)

- Added official Shizuku API/provider integration and a user-approved connection flow.
- Added lifecycle-aware binder/service state, reconnect handling and duplicate-bind protection.
- Added a fixed, read-only UserService diagnostic allowlist for device identity, device details, battery, installed package names, display, storage/RAM and validated package paths.
- Arbitrary shell strings, install/uninstall, permission mutation, settings writes and consequential device actions are not exposed through this bridge.
- Added a Shizuku control/diagnostics card to ERA's UI and documented first-run setup and security boundaries in `docs/SHIZUKU_INTEGRATION.md`.
- Verification: Kotlin compile PASS; 152/152 unit tests PASS; debug APK assembly PASS; APK signature matches the currently installed ERA debug package. The APK was staged in Downloads but not installed; runtime Shizuku approval remains a user action.


## v0.8.0-dev — Reliability Foundations and Review-Gated Integration (2026-11-21)

### P0 Core Reliability Architecture
- **Unified Capability Interface:** Built strongly typed capability contract (`CapabilityContract.kt`), structured action requests (`StructuredActionRequest`), structured results (`StructuredActionResult`), and categorized execution errors (`ExecutionErrorCategory`).
- **Capability Registry (`CapabilityRegistry.kt`):** Dynamic registry for capability discovery, precondition validation, availability checking, and adapter resolution. Rejects duplicate action name registrations.
- **6 Unified Capability Adapters:**
  1. `AndroidDeviceCapabilityAdapter`: Intent-based app launching, URL browsing, dialer prep, volume adjustment.
  2. `NativeWebViewBrowserCapabilityAdapter`: ERA in-app multi-tab WebView automation (DOM, Shadow DOM, same-origin frames).
  3. `ExternalChromeAccessibilityCapabilityAdapter`: Standalone Chrome app inspection via foreground Android Accessibility.
  4. `AccessibilityUiCapabilityAdapter`: Android screen hierarchy capture and one-shot snapshot-bound action approvals.
  5. `VisualInspectionCapabilityAdapter`: Manual overlay-triggered screenshot frame inspection.
  6. `SearchAndRecognitionCapabilityAdapter`: Local contact search, product shopping queries, and visual product recognition.
- **Strict Execution State Machine (`TaskStateMachine.kt`):**
  Implemented formal 7-stage lifecycle (`CREATED → VALIDATING → READY → RUNNING → OBSERVING → VERIFIED → SUCCEEDED`) plus pause and failure states (`WAITING_USER`, `RETRYABLE_FAILURE`, `FAILED`, `CANCELLED`, `RECOVERY_REQUIRED`). Validates all state transitions, rejects invalid transitions with typed `TransitionOutcome.Rejected`, and maintains a full audit log.
- **Independent Verification Engine (`VerificationEngine.kt`):**
  Validates preconditions, collects normalized post-action observations, and compares observed state against expected UI outcomes before declaring success. Emits typed results: `VERIFIED`, `NOT_VERIFIED`, `RETRY_REQUIRED`, `REPLAN_REQUIRED`, or `VERIFICATION_UNAVAILABLE`.
- **Durable Recovery & Retry Engine (`ExecutionJournal.kt`):**
  Guarantees idempotency via unique action IDs, idempotency keys, and SHA-256 action fingerprints. Prevents duplicate execution, tracks checkpoints, enforces bounded retries with exponential backoff, and ensures consequential actions are never replayed automatically without user confirmation. Reconciles interrupted tasks to `RECOVERY_REQUIRED` on process restart.
- **Execution Coordinator (`TaskExecutionCoordinator.kt`):**
  Upgraded to resolve capabilities dynamically, validate availability, dispatch structured actions, pass observations to the verification engine, and maintain durable state journals.

### P1 Intelligence and Orchestration
- **Planner Feedback Loop (`PlannerFeedbackLoop.kt`):**
  Controlled feedback loop feeding action results, observations, and verification outcomes back to the planner. Enforces bounded replanning (max 3 replans) and prevents infinite replanning loops.
- **Structured Task Planning DAG (`TaskPlanDAG.kt`):**
  Validates multi-step plan dependency graphs, computes topological orderings, detects circular dependencies via Kahn's algorithm, and identifies ready parallel actions.
- **Unified Browser Architecture (`UnifiedBrowserArchitecture.kt`):**
  Common `UnifiedBrowserTarget` abstraction separating Native WebView from External Chrome while maintaining strict technical honesty: Chrome Accessibility automation operates strictly on foreground Android accessibility nodes with no claims of DOM/CDP, cookies, or hidden profile access.
- **Context & Observation Layer (`NormalizedObservation.kt`):**
  Normalized observation model for Android Accessibility, WebView DOM, Chrome Accessibility, and Visual frames with timestamping, staleness detection, and bounded sizes (500 elements max and 64 KB aggregate payload).
- **Task Execution Monitor (`TaskExecutionMonitor.kt`):**
  Mobile-first monitor projecting current task, current action, progress, execution state, retry count, active capability, verification status, and failure/recovery reasons.

### P2 Advanced Agent Architecture
- **Reusable Skill Engine (`SkillEngine.kt`):**
  Modular skill definitions with metadata, preconditions, required capabilities, and execution steps. Validates skills before registration and strictly rejects dangerous system commands (`rm -rf`).
- **Persistent Agent Memory (`AgentMemory.kt`):**
  Modular persistent storage for user preferences, task history, verified workflows, mistake records, and capability knowledge with 100-record category limits.
- **Automated Privacy Filter (`PrivacyFilter`):**
  Scans and scrubs Google API keys (`AIza...`), OpenAI API keys (`sk-...`), bearer tokens, passwords, OTPs, CVVs, credit cards, and email addresses from memory and learned workflows.
- **Workflow Learning Engine (`WorkflowLearningEngine`):**
  Extracts verified, reusable action sequences from succeeded tasks into generalized workflow templates for recurrent user goals.
- **Offline Operation Manager (`OfflineOperationManager.kt`):**
  Evaluates capability offline readiness and gracefully degrades to deterministic local planning when cloud AI providers or network access are unavailable.

### Verification Results
- **Android Gradle Unit Tests:** 152 tests executed across 28 test suites — **152 PASS, 0 FAIL, 0 ERRORS, 0 SKIPPED**.
- **ERA Core Smoke Harness:** 36 assertions — **PASS**.
- **Bounded Agent Runtime Smoke:** 41 assertions — **PASS**.
- **Pending UI Action Smoke:** 25 assertions — **PASS**.
- **Permission Flow & Manifest Validation:** 8 assertions, 4 XML files — **PASS**.
- **Phone Browser Agent Integration Suite:** 38 tests — **PASS**.
- **Git Hygiene:** `git diff --check` passed with 0 warnings.
- **Scope Commitment:** Standalone Phone Browser Agent at `~/phone-browser-agent` untouched; no APK build executed.


### Follow-up hardening after source integration audit
- Added exact plan capability/action mappings and preserved risk, reversibility and approval requirements in task-ledger persistence.
- Added typed per-action input schemas for all six registered adapters and bounded/identity-checked adapter outputs.
- Added one-time consequential-action receipts bound to step ID, exact request fingerprint and fresh observation. Receipts expire after 60 seconds, are consumed once, and are not restored after process death.
- Added cancellable active adapter execution, durable dispatch checkpoints and a SharedPreferences-backed task/action lifecycle history. Interrupted work restores as RECOVERY_REQUIRED, never auto-replayed.
- Added field and aggregate payload limits to normalized observations; Accessibility, Chrome and WebView normalizers trim overflow and mark snapshots truncated.
- Extracted pure Kotlin privacy and lifecycle interfaces so standalone JVM harnesses can compile them; updated memory sanitization and legacy memory migration.
- Wired DAG capability/availability checks, bounded planner feedback, offline preview fallback, and skill/workflow hints. Replan proposals are displayed for manual review only.
- Marked the old BoundedAgentRuntime/AgentCapabilityRegistry as legacy compatibility/test code. UnifiedBrowserArchitecture remains a prototype without a production consumer.
- **Remaining gate:** the natural-language task UI still lacks a trusted production StructuredActionRequest builder/call-site. Plan approval is review-only and does not dispatch an action. Automatic timed retry/backoff orchestration is also not yet wired.
- No APK packaging or Android UI/instrumented end-to-end test was performed.

### Updated verification
- Gradle unit tests: **152/152 across 28 suites**.
- Standalone P0 JVM harness: **52/52**.
- Core / Agent Runtime / Pending UI smoke: **36 + 41 + 25** assertions.
- Permission-flow checks: **8**; XML parse checks: **4**.
- Integrated Phone Browser Agent: **38/38** tests; syntax check passed.
- git diff --check passed; the pre-existing working-tree changes were preserved.


## v0.8.0-dev — standalone in-app Browser Agent runtime (2026-11-21)

- Replaced the earlier Termux localhost bridge and token-pairing UI with an ERA-owned Android WebView browser. The APK runtime no longer needs Termux, ADB, Node.js, a localhost HTTP service or a bearer pairing token.
- Added an ERA-owned multi-tab WebView browser (up to five tabs) with per-tab current-snapshot-bound refs, tab switching/creation/closure, back/forward/reload/stop and renderer recovery.
- Expanded semantic snapshots to 400 bounded nodes / depth 10, with open Shadow DOM and same-origin frame traversal, accessible names/descriptions, parent/depth relationships and common form/control state. This remains DOM-derived rather than Chrome's native Accessibility domain tree.
- Added ref-bound read/click/fill/type/press/check/uncheck/select/scroll actions. Input state is verified without echoing the entered value; native and basic custom dropdown selection and checkbox/radio/switch changes are supported.
- Sensitive/private reads, sensitive fills, keyboard presses and consequential actions use explicit one-time confirmation. Query/hash, private values and DOM attributes remain excluded by default. URL userinfo is rejected; HTTPS-only navigation, HTTP upgrade, popup denial, permission-request denial, file/content blocking and mixed-content blocking remain enabled.
- Kept the Original standalone Phone Browser Agent separate. ERA retains an independent JavaScript/Puppeteer/CDP source copy and Node regression suite under integrations/phone-browser-agent; this source is development/reference material, not an APK dependency.
- Scope boundary: the in-app WebView controls only pages opened inside ERA, not separate Chrome app tabs. Cross-origin iframe internals, Chrome native AX, isolated CDP contexts and resident CDP daemon/queue operations remain outside the no-Termux APK runtime.
- Verification: manual Kotlin+Compose compiler pass of all app/src/main/java sources succeeded using cached Android SDK 35/AndroidX dependencies; ERA source smoke suite passed 98 assertions and 4 XML parses; copied Node source suite passed 38/38; git diff --check passed.
- Browser Agent policy unit tests: 12 passed with the cached JUnit 4.13.2 runner. Official Gradle/JUnit tasks, WebView instrumentation, physical-device browser navigation/actions and APK assembly remain unverified. No APK was built or installed.

## v0.8.0-dev — non-security reliability fixes (2026-11-21)

### Task planning and progress
- Changed task persistence namespace to a fingerprint of all execution-relevant plan fields so an edited plan cannot reuse stale progress.
- Rejected unsupported remote planner tool names and made every accepted tool map to an explicit local capability; known consequential tool aliases map to USER_CONFIRMATION.
- Replaced ambiguous EXECUTION_REPORTED / VERIFIED task semantics with USER_REPORTED / USER_CONFIRMED. The UI and monitor now distinguish user attestations from independent machine verification.
- Added migration for existing persisted task state names and expanded monitor counts/phases for pending, rejected, replan, failed and cancelled work.
- Strengthened task-plan validation for blank goals/versions, invalid IDs, empty titles/descriptions and duplicate dependencies.

### Performance and reliability
- Added bounded streaming reads for planner, provider-connection and visual-recognition responses.
- Added a short-lived contact candidate cache, cancellation/generation checks against stale searches, and invalidation on app resume.
- Updated task engine tests, planner adapter tests, bounded-reader tests and the Core smoke harness.

### Current-source checks
- Full source smoke suite: 98 assertions passed; 4 Android XML files parsed.
- Targeted Kotlin compile/smoke for task engine, planner tool validation and bounded response reader passed; 22 changed test methods passed under a lightweight local JUnit-compatible shim (not the official Gradle/JUnit runner).
- Phone Browser Agent: syntax checks passed; 8 tests passed.
- Full Android Gradle/JUnit tests remain unrun because Gradle and the project wrapper are unavailable in the current Termux/proot environment.
- APK assembly intentionally deferred; no updated APK was generated or installed.


## v0.8.0-dev — follow-up implementation completed in source (2026-11-21)

### Contact and call workflow
- Connected exact/partial/alias/phone-digit matching to name-directed call commands.
- Contacts database lookup is user-triggered and gated by Android READ_CONTACTS permission. If permission is declined or there is no match, ERA falls back to Android's system contact picker.
- Multiple matches require explicit selection. A selected contact opens a confirmation card; confirmation opens the system dialer only and never places a call.

### Instagram draft handoff
- Added Android Photo Picker media selection and caption/hashtag composer.
- Hashtags are normalized and deduplicated. ERA sends the chosen media and draft text to Instagram for review; the final Share action remains manual.

### Visual Product Recognition and Shopping
- Added an in-memory visual frame content provider for user-selected visual search handoff; no image file is created.
- Added optional Gemini/OpenAI-compatible multimodal recognition. A separate one-time consent dialog precedes HTTPS image upload; the captured frame is JPEG-compressed in memory and recycled after the request.
- Product candidates are JSON-parsed with bounded fields and confidence; the suggested search query is placed in the external Shopping search field. The model result is not guaranteed and current prices are shown externally, not fetched inside ERA.

### Task monitor, history and UI
- Added per-step manual review/approval/report/verification/replan/cancel controls over the task ledger. The monitor is a manual evidence tracker, not an autonomous cross-app executor.
- Added command-history filtering.
- Added UI controls for contacts, Instagram preparation, visual product recognition and price search.

### Current-source verification
- Gradle Android unit tests: 84 passed, 0 failures, 0 errors, 0 skipped.
- Source smoke tests: 98 assertions passed; 4 Android XML files parsed.
- Phone Browser Agent: npm run check passed; npm test: 8 passed, 0 failed.
- git diff --check passed.
- APK assembly intentionally not run. Live device, live provider and live Chrome CDP tests remain outstanding.

## v0.8.0-dev — workflow, visual-memory and reliability upgrade

### P0 — Screen observation
- Connected semantic-first versus visual-fallback decisions to the AI context builder. Sparse or truncated Accessibility data now asks for user choice; no screenshot is taken automatically.
- Added explicit Android screenshot capture from a long-press on the ERA Accessibility overlay. One frame stays in process RAM, is downscaled to a 1440px longest side, and is cleared/recycled on replacement, consent revocation or service teardown. No image file or automatic upload is created.
- Retained the full local Accessibility tree for snapshot-bound action approval while keeping the AI-facing compact JSON free of coordinates, node paths, password nodes and editable current values.

### P1 — User workflows and UI
- Added deterministic contact exact/partial/alias/phone-digit matching with Bengali and Arabic-Indic digit normalization. Ambiguous results are returned as multiple matches rather than silently selecting the first.
- Added a selected-contact call confirmation card. Confirm opens the Android dialer only; ERA never places the call automatically.
- Added a dedicated Instagram preparation plan for account/media/caption/hashtags/review. The user performs the final Share action manually.
- Expanded the Material 3 dashboard with status chips, provider controls, call confirmation, visual-frame preview, task monitor and command history.
- Added optional Gemini and OpenAI-compatible provider configuration, HTTPS/model validation, manual connection testing and Android Keystore AES-GCM key storage.

### P2 — Planner and history reliability
- Added local-first planner routing with optional remote fallback for low-confidence plans only. Transient failures preserve the local plan; provider output is validated and consequential steps are forced to manual approval.
- Added a task monitor projection over plan/ledger state with verified, review, failed and cancelled summaries.
- Replaced display-string-only history with versioned structured JSON, timestamps, outcome classification, legacy migration and masking for phone/email/API-token patterns.

### Phone Browser Agent / Chrome integration
- Audited the Termux `~/phone-browser-agent` folder and copied only selected runtime files into `integrations/phone-browser-agent/runtime/`. The original source folder was not modified.
- Added a loopback-only Node bridge on `localhost:18765`, random bearer-token pairing, Android Keystore token storage, Chrome stable-target sessions and ref-based semantic DOM/AX snapshots.
- Added default snapshot privacy filtering with per-request consent: editable values, raw attributes, CSS/Shadow DOM locators and full URL query/hash details are shared only after the user selects a one-time private-details grant.
- Replaced Phone Browser Agent sensitive-field and consequential-action hard-blocks with one-time, action-scoped confirmations. Password/OTP/PIN/API-token/payment field fill and consequential Send/Submit/Publish/Pay/Delete clicks require confirmation; non-web navigation schemes remain blocked.
- Added ERA UI for pairing, tab selection, snapshot capture and explicit Read / Approve & Click / Approve & Fill actions.
- Added 8 Node tests for default redaction, opt-in private details, one-time action confirmation, non-web link blocking, session URL consent and loopback authentication.
- Added a localhost health/auth check. Live Chrome CDP attach and real page action/verification remain untested.
- Copied `phone-ui.sh` as a manual-only Termux fallback; its state/screenshot path is isolated under `~/.cache/era-phone-browser-agent`, and coordinate actions are not exposed to ERA's AI bridge.

### Verification for this source revision
- Source smoke suite: 98 assertions passed; four Android XML files parsed.
- Gradle `testDebugUnitTest`: 84 passed, 0 failures, 0 errors, 0 skipped.
- Node integration `npm run check` (JavaScript + shell syntax) passed; `npm test`: 8 passed, 0 failed.
- Bridge health returned READY and unauthenticated access returned HTTP 401.
- The original `~/phone-browser-agent` working tree had pre-existing modified/untracked files and was not reset or reconciled by this task.
- No APK assemble/build was run in this revision. No updated APK was generated or copied.
- Live Chrome CDP attach, real browser tab actions, provider API, screenshot callback, Instagram runtime and install/launch remain unverified.
- Visual product recognition and external Shopping handoff are source-implemented but live API/pricing are not verified; a separate Agent Browser ZIP was not supplied.

## v0.7.2 remote build/test results
- Moto G64 / Android 15: all 98 source smoke checks passed.
- Android Gradle Kotlin compile and debug APK assembly succeeded using Gradle 8.9, JDK 17, Android SDK 35 and the native Termux AAPT2 override.
- All 33 JUnit tests passed with zero failures, errors or skipped tests.
- Debug APK: 9,717,845 bytes; SHA-256 `1d28802239805fb2302ff61ac529d06c6d12762ef75ff3039993ac8d5ac66113`.
- APK ZIP integrity, V2 signature and 4-byte alignment of 67 uncompressed entries verified. The APK was copied to shared Downloads as `ERA-Android-v0.7.2-debug.apk`.
- Install/launch and Android runtime interaction tests remain pending Android Package Installer approval. See `docs/REMOTE_DESKTOP_AUDIT.md` and `docs/TEST_REPORT.md` for exact environment and limitations.

## v0.7.2 — source test pipeline hardening
- Added one `tools/run-all-smoke.sh` command for Core, Bounded Agent Runtime, Pending UI Action and Permission-flow/XML checks.
- Updated GitHub Actions to install Kotlin CLI compiler 2.0.21 and run the complete four-part smoke suite before Android unit tests and APK assembly.
- Removed one redundant Kotlin non-null assertion found during Moto Termux compilation; behaviour is unchanged.
- Removed `material-icons-extended`; ERA only used one microphone glyph. Replaced it with a lightweight text glyph to avoid an oversized unused icon dependency.
- Added a Gradle CPU limit for lower-memory Android Termux build environments.
- Bumped app version to 0.7.2 / versionCode 10.


## v0.7.1 — one-time permission setup
- Added an in-app Permission Center for Microphone, Camera/flashlight and Accessibility access.
- Permission status is derived from Android's live permission state on app resume; no persisted grant flag can become stale.
- After a permission is granted, feature entry points re-check the grant and skip the runtime dialog. Setup requests are only launched by an explicit user action when the permission is missing.
- Added explicit guidance to choose Android's normal `Allow` option rather than `Only this time` when a persistent microphone grant is intended.
- Accessibility enablement is shown separately because it is a special system setting requiring explicit user action after the in-app disclosure.
- Documented system-driven exceptions: revocation, unused-app auto-reset, reinstall and temporary one-time grants.
- Added 8 static permission-flow assertions and XML parse checks.
- Bumped app version to 0.7.1 / versionCode 9. Android build and physical-device verification are still required.

## v0.7.0 — snapshot-bound Accessibility approvals
- Bound every one-shot Android UI approval to a SHA-256 identity of the exact serialized Accessibility tree, target package, node count and truncation state reviewed in ERA.
- Re-capture the active app screen when the ERA overlay is tapped; if any observable UI state changed after approval, cancel without dispatch and require a fresh review.
- Kept semantic re-resolution, enabled/visible checks, consequential-control rejection, password-field typing rejection and one-action consumption before dispatch.
- Changed pending-action consumption/cancellation to atomically clear the stored selector, text payload, app package, observation ID and creation timestamp while retaining only the result message.
- Added snapshot-fingerprint smoke assertions and an in-memory Android SharedPreferences test harness; 36 core, 29 bounded-runtime and 25 pending-action assertions pass.
- Updated project version to 0.7.0 / versionCode 8.
- The portable `BoundedAgentRuntime` is still not connected end-to-end to an AI planner or the Android service. Gemini, Chrome CDP, wake word, Gradle APK build and physical-device validation remain outstanding.

## v0.6.1 — exact-action approval hardening
- Replaced boolean approval with a one-time `AgentApprovalReceipt` bound to the full action fingerprint, observation ID and approval timestamp.
- Reject missing, mismatched, expired, future-dated, malformed and replayed approval receipts.
- Added SHA-256 action fingerprinting with length-prefixed canonical fields to avoid delimiter/null ambiguity.
- Added 8 additional runtime assertions; all 29 runtime and 30 core smoke assertions passed at that revision.
- Kept the existing password-field rule unchanged; no extra OTP/PIN/token or personal-data masking was added, matching the previously agreed project scope.
- Android adapters, Gemini transport, Chrome CDP, wake-word engine, Gradle build and device testing remained outstanding.

## v0.6.0 — bounded execution runtime core
- Implemented a platform-neutral observe/decide/authorize/execute/verify runtime with injected planner, observer, executor, approval and verifier adapters.
- Enforced one action per fresh observation, re-capture after approval, pre-dispatch snapshot identity checks and post-action verification.
- Added deterministic typed action policy with allow-listed local actions, bounded volume values and explicit HTTPS validation.
- Added manual-only handling for send, publish, submit, purchase, payment, deletion, sharing, calling, permission grants, authentication and installation.
- Blocked password-field input, model-generated text insertion, stale completion claims, cross-app targets and unknown actions.

## v0.5.0 scaffold
- Selected Gemini planner interface (transport intentionally not wired because secure key management was excluded).
- Chrome DevTools bridge interface for user-configured Chrome debugging (implementation stub).
- Wake-word engine interface for foreground visible session (implementation stub).
- Initial bounded agent-loop policy and weakness audit.


## v0.8.0-dev — separate External Chrome Accessibility target (2026-11-21)
This update supersedes the earlier same-version localhost Chrome bridge / CDP notes below; the active APK runtime is a native WebView plus this optional AccessibilityService-based foreground Chrome target.


- Added an optional External Chrome target to the existing ERA Screen Inspector; kept the native in-app multi-tab WebView Browser Agent separate and unchanged.
- Added Stable, Beta and Dev Chrome package recognition, foreground-package verification and an explicit Start/Disable target control. The target is entered only after screen-inspection consent and after the user enables ERA Screen Inspector in Android Settings.
- Chrome capture reads its currently exposed visible Accessibility tree through the existing service. Returning to ERA reloads the tree into the existing snapshot-bound action-review path.
- Reused the exact-snapshot, target-package, one-shot approval and overlay-tap dispatch path for click/type/scroll. Wrong app, stale tree, expired approval, ambiguous selector and consequential click protections remain active.
- Added Chrome-only value filtering: editable values and address-bar content are omitted; URL query strings/fragments are stripped from displayed visible URL text. Password values remain omitted. Existing general-app snapshot behavior is preserved.
- Revoking screen-inspection consent automatically disables the External Chrome target and clears its status.
- Added four ExternalChromeAgentPolicy JUnit tests covering supported packages, editable/address-bar redaction, URL query/fragment stripping and non-Chrome pass-through.
- Limitations: this is foreground Accessibility UI automation, not Chrome DOM/CDP; no cookies, browser profile, hidden page state, cross-origin iframe internals or reliable tab/session control. Live Chrome device interaction is still unverified.
- APK build intentionally remains deferred. No APK was generated or installed.


## v0.8.0-dev — P0 reliability foundation (2026-11-21)

- Added a typed `AgentCapabilityRegistry` with strict one-capability ownership for each registered action kind, validated dispatch requests and a bounded-runtime executor adapter.
- Added typed dispatch failure categories, action-fingerprint binding and deterministic per-run idempotency request IDs.
- Added `SharedPreferencesAgentDispatchLedger`: hashes dispatch keys, durably commits IN_PROGRESS before adapter dispatch, records terminal outcomes and fails closed at its fixed record cap. It stores no action text or screen content.
- Interrupted IN_PROGRESS actions now require target-app inspection; duplicate dispatches, changed-action key collisions and journal errors stop the bounded runtime rather than trigger automatic re-execution.
- Adapter cancellation intentionally leaves an IN_PROGRESS record because the external side effect may be partial.
- Task steps previously under observation, review, approval or user-reported execution are invalidated to NEEDS_REPLAN after process recreation. Fresh screen observation and review are required.
- These are reusable P0 reliability components only. Production Android capability/observer/approval/verifier wiring is still pending. No APK was built.
