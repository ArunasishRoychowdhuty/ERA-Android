# ERA Android v0.9.2 — Architecture and Security Notes

Patch release: `versionCode 14`, `versionName 0.9.2`
Package: `com.eraassistant.mobile`
Android baseline: `minSdk 26`, `targetSdk 35`

## 1. Structured instant-command execution

Supported instant commands now pass through this pipeline:

`CommandRouter → ActionIntentCompiler → one-step TaskPlan → TaskExecutionCoordinator → CapabilityRegistry → ExecutionJournal → CapabilityAdapter → VerificationEngine`

The compiler is a local deterministic allow-list. It maps only explicit command objects to exact capability IDs, action names and bounded parameters. It does not parse planner prose, execute scripts, accept arbitrary selectors or expose a shell.

Supported mappings:
- Device control: flashlight and volume.
- App/system intents: launch app, open camera, open approved settings screen.
- Communication preparation: open the Android dialer, create an SMS draft and hand a WhatsApp draft to Android.
- Navigation: web search, map search and HTTPS-only website opening.

The dialer intent never places a call. SMS/WhatsApp actions only create a draft/share handoff. Send, publish, pay, purchase, transfer and delete actions are not part of the instant-command compiler.

A direct command is explicit user intent. The coordinator records a reviewed/approved one-step action, claims a durable journal entry and resolves the exact registered adapter. If independent post-action evidence is not available, the result remains `WAITING_USER`; adapter success is not presented as independent verification. Compound sequences stop when a prior action fails or awaits verification.

Unknown or unsupported commands fail closed. Read-only battery/date-time summaries and Help retain their existing local handlers.

## 2. Approval lifecycle

- Voice approvals are process-local, exact-request-bound, expire and are consumed once.
- The contact-to-dialer voice and UI confirmation paths now dispatch the typed `device.dialer.open` action through the same task coordinator.
- The final call remains a user action in Android's Dialer.
- Consequential browser actions retain their existing one-shot, exact-snapshot approval gates.
- Android runtime permission and system Settings screens remain user-controlled.

## 3. Scoped file access

The file browser uses Android's Storage Access Framework:
- Access begins only after the user selects a folder in Android's system picker.
- The persisted URI grant is read-only.
- Directory listing is capped at 100 entries.
- Text preview accepts a fixed allow-list of plain-text formats, reads at most 64 KB and displays at most 3500 characters.
- The user can revoke the folder grant from ERA.
- No broad storage permission, raw filesystem path traversal, file write, rename or delete operation is exposed.

The file capability adapter is registered under `android.storage.saf` and exposes only `storage.tree.list` and `storage.document.read`.

## 4. Credential handoff

The in-app WebView is marked Autofill-capable. The user can explicitly request Android's configured Autofill/password-manager UI. ERA does not read, store or forward the credential value returned by the system Autofill service.

This release uses the platform Autofill bridge; it does **not** claim a direct AndroidX Credential Manager API integration. A password-manager/Autofill service must already be configured in Android Settings.

## 5. Optional local Ollama planner

The optional local model provider connects only to `http://localhost:<port>` and is restricted to `localhost` by the configuration validator and network security policy. The default endpoint is `http://localhost:11434`.

- Availability is checked by a user-triggered `/api/tags` request.
- The selected model must already be installed in Ollama.
- Planner requests contain the user's task text and capability names; they do not include screenshots, password fields or raw credentials.
- Response size and step count are bounded. The model returns a structured planner response, not an executable command.
- Planner output is passed through the existing task-plan validator and exact capability mapping.
- There are no bundled model weights or Ollama runtime in the APK.

The provider is shown as `ON_DEVICE` when invoked. Visual product recognition remains disabled for Ollama; screen images are not sent to the local text planner.

## 6. Existing capabilities retained

The existing v0.8.0 foundations remain in place: the common capability registry, native WebView and external Chrome accessibility adapters, task DAG, state machine, execution journal, one-time action receipts, recovery-required state, bounded planner feedback, encrypted remote API keys, Shizuku diagnostics, accessibility screen observation, product search and foreground voice session.

## 7. Explicitly incomplete scope

The following are not claimed as complete in v0.9.2:
- **Remote Connectivity V3 deployment and live validation:** Android client plus Python/SQLite gateway are implemented. The gateway has local HTTP integration tests, but has not been deployed on the user's private Linux host/tailnet. The phone has not been paired to a live HTTPS MagicDNS endpoint. No remote command queue, remote command execution, screen stream or remote control exists in V3. Shizuku remains a separate local device bridge. Deployment is documented in `docs/REMOTE_CONNECTIVITY_V3.md`.
- **Embedded local model runtime:** no inference engine or model weights are bundled; Ollama must be installed and started separately on the same device.
- **General planner-step execution:** arbitrary complex planner steps still require manual review; only exact instant-command objects supported by `ActionIntentCompiler` are dispatched automatically through the coordinator.
- **Full file manager:** SAF access is read-only. Create/write/rename/delete/move and bulk operations are not enabled.
- **Notifications:** a notification listener/action subsystem is not added in this release.
- **Direct Credential Manager API:** this is native Android Autofill handoff, not a full provider-account Credential Manager integration.
- **Physical end-to-end UI validation:** the APK is built and signed, but installation, live microphone recognition, Autofill provider selection, SAF interaction with multiple document providers and device-level browser automation need manual device validation.

## 8. Release verification record

- Gradle unit tests: **182 tests / 32 suites**, 0 failures, 0 errors, 0 skipped.
- Standalone P0 JVM tests: **72 passed**.
- Existing source smoke: Core 36 + Agent Runtime 41 + Pending UI 25 = **102 assertions passed**.
- Permission-flow source checks: **8 passed**; Android XML parsing: **4 passed**.
- Gateway protocol integration: **13 passed**, including concurrent one-time redemption.
- `git diff --check`: passed; pre-existing project changes were preserved.
- Debug APK: `~/storage/downloads/ERA-0.9.2-debug.apk`, 9.9 MB; `com.eraassistant.mobile.debug`, versionCode `14`, versionName `0.9.2-debug`, minSdk `26`, targetSdk `35`.
- APK Signature Scheme v2 verified; SHA-256 `2f5fb74760ecde559ebd3498837a96c14155e3e93c955adfc5474849c5883754`.
- Gateway deployment archive: `~/storage/downloads/ERA-Remote-Gateway-V3.tar.gz`; SHA-256 `ed00617dcc0fcf90cb0cdaddfd3b0505ab86c04abdfc83ddb3a20572596bb17d`.
- APK is not installed. Device-level interaction tests and live tailnet pairing remain manual gates.
- Pre-remote-v3 source backup: `~/ERA-v0.9.0-pre-remote-v3-20261122.tar.gz`; gzip integrity verified.
