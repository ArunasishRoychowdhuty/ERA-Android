# Remote Desktop Test & Fix Audit — ERA v0.7.2

## Device and environment

- Device: Motorola Moto G64 5G.
- Android: 15 / API 35 / arm64.
- Runtime: Termux.
- Source archive initially found in Downloads: `ERA-Android-v0.7.1.zip`.
- Archive SHA-256 verified on-device before extraction: `f6714df069509d051e0e0df12a664c362ab30301497b5c220e8f3d58784d16f2`.
- Working project: `~/projects/ERA-test/ERA-Android-v0.7.2`.
- JDK 17 and Kotlin CLI installed in Termux.
- Gradle 8.9 installed under `~/gradle-8.9`.
- Android SDK Platform 35 and Build Tools 35.0.0 installed under `~/android-sdk`.
- Termux native ARM64 AAPT2 is used through Gradle's `android.aapt2FromMavenOverride`.

## Tests

### Termux source checks

`bash tools/run-all-smoke.sh` completed with exit code 0:
- Core smoke: 36 PASS.
- Bounded Agent Runtime: 29 PASS.
- Pending UI Action: 25 PASS.
- Permission-flow source checks: 8 PASS.
- Android XML parse: 3 files PASS.
- Total: 98 PASS.

### Android Gradle and JUnit

- `:app:compileDebugKotlin`: BUILD SUCCESSFUL.
- `:app:testDebugUnitTest`: BUILD SUCCESSFUL.
- 33 JUnit tests, 0 failures, 0 errors, 0 skipped.
- `:app:assembleDebug`: BUILD SUCCESSFUL in 7m 19s.
- 37 actionable tasks; 15 executed and 22 up-to-date.

JUnit suites:
- CommandRouterTest: 12.
- TaskExecutionLedgerTest: 8.
- UiSelectorTest: 9.
- PreviewPlannerTest: 4.

## Fixes applied in v0.7.2

- Added `tools/run-all-smoke.sh` as a single entry point for all four source-check groups.
- Updated GitHub Actions to install Kotlin CLI 2.0.21 and run all four source checks before Android unit tests and APK assembly.
- Removed a redundant Kotlin non-null assertion from task-ledger restoration.
- Removed the large `material-icons-extended` dependency; the screen only used one microphone glyph. Replaced it with a lightweight text glyph to reduce unused icon payload.
- Limited Gradle CPU worker count during phone builds to reduce resource pressure.
- Bumped app version to 0.7.2 / versionCode 10.

## APK output and integrity

- Build output: `app/build/outputs/apk/debug/app-debug.apk`.
- Package: `com.eraassistant.mobile.debug`.
- Version: `0.7.2-debug`, versionCode 10.
- Size: 9,717,845 bytes (about 9.3 MiB).
- SHA-256: `1d28802239805fb2302ff61ac529d06c6d12762ef75ff3039993ac8d5ac66113`.
- Apksigner V2 verification: PASS.
- ZIP integrity: PASS.
- 4-byte data alignment: PASS for 67 stored/uncompressed entries.
- Copied to shared Downloads: `ERA-Android-v0.7.2-debug.apk`.

## Install status and limitation

- `adb devices -l` showed no connected/authorized ADB device.
- Direct `pm install -r` from Termux private storage failed because Package Manager's system process cannot read Termux's private app-data path as the Termux UID.
- APK was copied to shared Downloads and opened with `termux-open --view`. The user still needs to approve Android's normal installer dialog.
- Installation, first launch, microphone/camera permissions, Accessibility activation and actual UI actions are not yet verified.

## Non-fatal warnings

- Kotlin CLI's bundled Jansi binary requests glibc `libc.so.6`; Termux uses Bionic. The warning is non-fatal and source smoke tests pass.
- SDK XML v4 metadata warning from the installed command-line tools/AGP processor.
- Gradle marks the native AAPT2 override as experimental.
- `libandroidx.graphics.path.so` is packaged unchanged because the available strip tool could not strip it.

## Verification boundary

The source smoke checks, Android Kotlin compile, JUnit tests and signed debug APK assembly are real device-side results from this Moto G64. App installation and runtime interaction testing remain pending user approval in Android's Package Installer.
