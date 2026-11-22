# ERA Shizuku System Bridge

## Purpose

ERA v0.8.0-dev can connect to the Android shell identity exposed by the Shizuku server. This is an optional system-integration channel; ordinary ERA features continue to work without it. Shizuku is not root. The device currently reports the Android `shell` UID (2000).

## First-time setup

1. Install the signed `ERA-Android-v0.8.0-dev-shizuku.apk` update over the existing ERA debug build.
2. Make sure the Shizuku app says its Wireless Debugging service is running.
3. Open ERA and find **Shizuku · Android system bridge**.
4. Tap **Request Shizuku access** (or **Connect ERA to Shizuku** if permission was already granted).
5. Approve ERA in the Shizuku permission prompt. ERA then binds its UserService and displays the live connection state.
6. Use the diagnostic buttons only when needed. The result is displayed in the same card.

If the Shizuku server stops, restart it in the Shizuku app and refresh/reconnect from ERA. Permission and service state are checked at runtime; a previous connection is not treated as permanently available.

## Exposed read-only operations

| Operation | Data returned |
|---|---|
| `identity` | UserService UID/PID and shell identity |
| `device` | Manufacturer, model, Android release and SDK |
| `battery` | Selected battery service fields |
| `apps` | Bounded third-party package-name inventory |
| `display` | Display size/density and brightness values |
| `storage` | `/data` filesystem and selected memory counters |
| `app_path` | Package path for a validated package name (service API only; not exposed as a UI action) |

The bridge accepts operation IDs, not shell command strings. Each operation maps to a fixed command or fixed system-property read. Process timeouts and output-size/row limits apply. Package names are validated before use.

## Explicitly not enabled

- Arbitrary shell command execution or command strings supplied by an AI model.
- App install/uninstall, permission grants/revocations, app-data access, or app-data deletion.
- Changing secure/system settings, network configuration, accounts, lock-screen settings, or device policy.
- Sending taps/gestures or submitting forms through this system bridge.
- Silent permission approval or automatic re-execution after a server restart.

Existing ERA adapters retain their separate Android API, Accessibility, review, and confirmation boundaries. Any future write-capable Shizuku capability must have its own typed operation contract, precondition checks, user-visible target/value preview, one-time approval, cancellation, post-action verification, and audit record. It must not add a generic command endpoint.

## Implementation notes

- Uses the official Shizuku API/provider artifacts (`13.1.5`) and `Shizuku.UserService`.
- `ShizukuBridgeManager` owns binder lifecycle, user permission request, service binding and reconnect state.
- `IEraShizukuUserService` uses an explicit Binder/Parcel contract compatible with its small fixed interface. The Android SDK AIDL host compiler shipped in this Termux build is an x86_64 binary and cannot execute on this ARM64 phone, so no AIDL compiler task is required.
- The UserService is non-daemon and is explicitly unbound when the ERA activity is destroyed.
- Runtime validation after installing this APK still requires the user to tap Connect and approve ERA in Shizuku; the source build alone does not grant that permission.

## Verification for this build

- `:app:compileDebugKotlin` — PASS
- `:app:testDebugUnitTest` — 152 tests, 0 failures, 0 errors, 0 skipped
- `:app:assembleDebug` — PASS
- APK signature verified; the debug certificate matches the currently installed ERA debug package.
- No app update/install or Shizuku permission grant was performed automatically.
