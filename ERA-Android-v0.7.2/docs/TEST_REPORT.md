# Test report — ERA v0.7.2

## Target device and environment

- Device: Motorola Moto G64 5G.
- Android: 15 / API 35 / arm64.
- Runtime: Termux.
- JDK: 17.
- Gradle: 8.9.
- Android Gradle Plugin: 8.7.3.
- Kotlin Gradle Plugin: 2.0.21.
- Android SDK Platform: 35.
- Native Termux AAPT2 override: `/data/data/com.termux/files/usr/bin/aapt2`.

## Source smoke tests on Moto G64

Command: `bash tools/run-all-smoke.sh`

| Check | Result |
|---|---:|
| Core Kotlin smoke | 36 PASS |
| Bounded Agent Runtime smoke | 29 PASS |
| Pending UI Action smoke | 25 PASS |
| Permission-flow source assertions | 8 PASS |
| Android XML parsing | 3 files PASS |
| **Total source checks** | **98 PASS** |

The Kotlin CLI emits a non-fatal Jansi native-library warning because its bundled Linux/ARM64 Jansi binary expects glibc `libc.so.6`, while Termux uses Android/Bionic. Compilation and execution complete successfully.

## Android Gradle checks on Moto G64

| Task | Result |
|---|---|
| `:app:compileDebugKotlin` | BUILD SUCCESSFUL |
| `:app:testDebugUnitTest` | BUILD SUCCESSFUL |
| JUnit tests | 33 passed, 0 failures, 0 errors, 0 skipped |
| `:app:assembleDebug` | BUILD SUCCESSFUL (37 actionable tasks; 15 executed, 22 up-to-date) |

JUnit breakdown:
- CommandRouterTest: 12 passed.
- TaskExecutionLedgerTest: 8 passed.
- UiSelectorTest: 9 passed.
- PreviewPlannerTest: 4 passed.

## Final debug APK verification

- File: `app/build/outputs/apk/debug/app-debug.apk`.
- Package: `com.eraassistant.mobile.debug`.
- Version: `0.7.2-debug`, versionCode 10.
- minSdk 26 / targetSdk 35 / compileSdk 35.
- Size: 9,717,845 bytes (about 9.3 MiB).
- SHA-256: `1d28802239805fb2302ff61ac529d06c6d12762ef75ff3039993ac8d5ac66113`.
- APK signature: apksigner verifies V2.
- Signer: local Android Debug certificate, SHA-256 `acb8afa549827dfbe4f47614cd104d4de34804d4176a45f4123e60cf870fe194`.
- ZIP integrity: PASS.
- 4-byte alignment: PASS (67 uncompressed entries inspected with a Python local-header check).
- Copied to Android shared Downloads as `ERA-Android-v0.7.2-debug.apk`.

## Install and launch status

The APK has been built and verified but is **not yet confirmed installed or launched**.

- `adb devices -l` returned no connected/authorized devices.
- Direct `pm install -r` from the Termux private app directory failed: Android's Package Manager system process cannot read that private path under Termux's app UID.
- The APK was copied to shared Downloads and opened via `termux-open --view`; installation still requires the user to approve Android's normal Package Installer UI.
- `pm path com.eraassistant.mobile.debug` did not yet report an installed package at the last check.

Manual step: open Downloads, tap `ERA-Android-v0.7.2-debug.apk`, allow Termux/Files to install unknown apps if Android asks, then tap **Install**. After installation, launch **ERA Assistant**.

Still pending after install:
- First launch and Compose UI inspection.
- Microphone and camera runtime permission flow.
- Accessibility service activation and settings return flow.
- Fresh Accessibility tree capture, semantic target resolution and approval flow.
- App launch and settings/volume/torch commands on-device.

## Non-fatal build notes

- SDK XML v4 metadata warning from the command-line tools/AGP processor.
- `android.aapt2FromMavenOverride` reports an experimental-option warning; the native Termux AAPT2 override worked.
- `libandroidx.graphics.path.so` was packaged unchanged because the available strip tool could not strip it.
- APK is signed with the local Android Debug certificate; this is a development build, not a production release signature.
- `material-icons-extended` was removed because the UI used only one microphone glyph; a lightweight text glyph replaces it.
