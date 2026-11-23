# ERA v1.0.2 — Supervised Personal Assistant

## Scope

This upgrade adds a Personal workspace, encrypted productivity persistence, reminder delivery, explicit Calendar export, and a final voice-confirmation stage for consequential Accessibility actions.

It does not grant root access, bypass Android permissions, or turn arbitrary planner output into an executable command. Existing capability registry, execution journal, observation fingerprint, and permission gates remain authoritative.

## 1. Supervised action model

### Click and type

- ERA captures a structured Accessibility tree only after screen-inspection consent.
- A proposed target must resolve uniquely in the current observed tree.
- Click and type approvals are scoped to the target package, selector, action, and SHA-256 fingerprint of the reviewed tree.
- Approval is one-shot and expires after two minutes.
- The target is re-resolved against a fresh tree at dispatch time.
- Password/OTP/PIN/CVV, card/payment, bank-account, UPI and API/access-token fields are blocked. Credential-like payloads are rejected before persistence and again before Accessibility dispatch.
- The pending text payload is AES-GCM encrypted in app-private preferences while waiting for dispatch. Failed encryption/decryption clears the action.

### Consequential actions

Send, Submit, Publish, Delete, Payment, Transfer and Purchase controls receive a second approval stage:

1. ERA prepares one exact click target using the reviewed screen.
2. It derives a short summary from visible, non-editable text only. It does not read current editable values or password fields.
3. For Payment, Transfer and Purchase, exactly one distinct visible currency amount must be present. Missing or multiple distinct amounts fail closed; ERA does not guess which amount is the total.
4. ERA requests an exact spoken OK or Cancel. The approval binds to package, selector, resource ID, action and reviewed observation fingerprint and expires after 30 seconds.
5. After OK, ERA moves itself to the background so the previously active target app can regain focus.
6. Accessibility takes a new live snapshot. Dispatch proceeds only if the fresh tree fingerprint still equals the reviewed fingerprint, the action target resolves uniquely, permission/consent is still active, the approval is fresh, and the final approval can be consumed once.
7. If the app or screen changed, approval expired, the target is not unique, or any gate fails, nothing is dispatched.

The final confirmation is not a reusable blanket grant. Approval is revoked on Cancel, unrelated speech, expiry, screen mismatch, or ERA process restart.

### Authentication boundary

ERA must not capture, store, transmit to a planner, or type passwords, OTPs, PINs, CVVs, card data or bank authentication values. The user completes secure authentication in the target app's own secure UI. This implementation does not automate an OTP or banking PIN workflow.

Accessibility is inherently platform- and app-dependent: some controls expose no reliable labels or tree, and some financial apps intentionally restrict inspection. The safe result in those cases is manual completion, not heuristic clicking.

## 2. Reminder scheduling

Room persists reminder metadata and ciphertext fields. Reminder title and note values are AES-256-GCM encrypted with a non-exportable Android Keystore key. The database stores encrypted values plus non-secret scheduling metadata.

- AlarmManager uses exact scheduling only when Android reports exact-alarm access.
- Without exact access, the reminder is scheduled as unique WorkManager one-time work. Android battery policy may delay this fallback.
- Notifications require the Android 13+ POST_NOTIFICATIONS runtime permission. Denial does not delete a reminder.
- Reminder delivery re-reads the Room row and atomically claims only due, enabled, untriggered reminders to prevent duplicate delivery.
- Pending reminders are rescheduled after boot, package replacement, system time/time-zone changes and exact-alarm access changes.
- Triggered reminders are eligible for bounded retention cleanup.

## 3. Calendar export

Calendar export is user-initiated for one selected reminder. ERA requests READ_CALENDAR and WRITE_CALENDAR only after the export action. It locates a visible writable calendar and inserts an event with the reminder title and scheduled start time.

ERA does not sync the entire calendar, monitor it in the background, modify existing events, or silently export all reminders. A row stores the created event ID to avoid accidental duplicate export.

## 4. Encrypted memory and controls

### New managed memory

Room stores user-approved categories: USER_PREFERENCE, HOUSEHOLD, ROUTINE and PROJECT_NOTE. Titles and content are encrypted with AES-256-GCM. Saving requires an explicit checkbox in the Personal UI; deletion is per-record or user-confirmed clear-all.

Credential-like values, credential field labels, raw card-number-like sequences and common UPI virtual-address suffixes are rejected before save.

### Existing Agent Memory

PersistentAgentMemory's JSON payload in app-private SharedPreferences is now encrypted with the Android Keystore key. Existing plaintext rows are read through the migration path and rewritten with an encrypted prefix when migration succeeds. If decryption/encryption fails, the code does not fall back to writing plaintext.

The Personal workspace exposes existing agent memory records and per-record/category/all deletion controls. These decrypted values exist in process memory when the agent needs them, but are encrypted at rest.

The new Room database does not use destructive automatic migration. A schema change without an explicit migration fails closed so that user data is not silently dropped.

## 5. Testing strategy

### JVM tests

- Exact one-shot OK and Bengali approval phrase handling.
- Critical risk requires both the existing exact action approval and separate consequential approval.
- Consequential target classification.
- Payment summary rejects missing or multiple distinct visible amounts.
- Editable payment field values are excluded from summary generation.
- Credential/payment field labels, raw card number-like strings and common UPI payloads are blocked.
- Ordinary non-credential text remains allowed.

### Android instrumentation

Instrumented tests are added for:
- Personal navigation.
- Android Keystore AES-GCM round-trip and randomized ciphertext.
- Room reminder and managed-memory persistence of ciphertext rather than plaintext.
- Live Accessibility capture of an actual foreground Settings screen, conditional on the device owner having already enabled ERA's Accessibility service and screen-inspection consent.

The live Accessibility test uses an explicit assumption when the service or consent is not enabled; it must not be reported as a passing live device test when skipped.

### Device validation still required

A connected physical device or emulator is required to execute instrumentation and live Accessibility tests. This Termux session currently reports no ADB target, so only source compilation, JVM tests and instrumentation APK assembly can be verified here. No device-level success is claimed without a connected target.

## 6. Known limitations

- Payment amount detection depends on labels exposed by the target app's Accessibility tree. It deliberately refuses to guess if the amount is absent or ambiguous.
- A visible currency amount is not a substitute for reviewing merchant/recipient details. The user should review the target screen before speaking OK.
- WorkManager fallback delivery can be delayed by Doze, OEM battery management or force-stop.
- Calendar export requires a visible writable Android calendar and explicit runtime permission.
- Exact-alarm access is controlled by Android Settings and may be revoked by the operating system.
- These changes add supervised autonomy, not unrestricted control over every application or protected financial workflow.
