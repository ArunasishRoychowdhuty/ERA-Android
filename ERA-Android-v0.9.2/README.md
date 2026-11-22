# ERA Android Assistant — v0.9.2

Native Android assistant built with Kotlin and Jetpack Compose. Baseline: v0.7.2. Current app source version: 0.9.2, versionCode 14.

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

## Release Artifact and Remaining Integration Scope

- **Debug APK:** `~/storage/downloads/ERA-0.9.2-debug.apk` (**9.9 MB**).
- **Package/version:** `com.eraassistant.mobile.debug`, versionCode `14`, versionName `0.9.2-debug`, minSdk `26`, targetSdk `35`.
- **SHA-256:** `2f5fb74760ecde559ebd3498837a96c14155e3e93c955adfc5474849c5883754` (source APK and Downloads copy match).
- **Gateway deployment bundle:** `~/storage/downloads/ERA-Remote-Gateway-V3.tar.gz` (19 KB); SHA-256 `ed00617dcc0fcf90cb0cdaddfd3b0505ab86c04abdfc83ddb3a20572596bb17d`.
- **Signature:** APK Signature Scheme v2 verified; one Android Debug signer, certificate SHA-256 `acb8afa549827dfbe4f47614cd104d4de34804d4176a45f4123e60cf870fe194`.
- **Installation:** APK is staged in Downloads but not installed. Runtime voice, Autofill, SAF, browser and foreground-session flows still require manual phone validation.
- **Remote Connectivity V3:** Android client and Python/SQLite gateway are included; 13 local gateway tests pass. The gateway is not deployed to the user's tailnet and the phone has not been paired to a live server. Shizuku remains a separate local-only bridge.
- **Complex planner steps:** unsupported free-form plan steps remain manual-review only; the new compiler dispatches the explicitly allow-listed instant-command set.
- **Standalone Phone Browser Agent:** the separate original project was not modified during this upgrade.
