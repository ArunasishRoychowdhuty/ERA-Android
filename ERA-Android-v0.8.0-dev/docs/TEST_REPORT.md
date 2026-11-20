# Test report — ERA v0.8.0-dev source revision

## Target device and environment

- Device: Motorola Moto G64 5G.
- Android: 15 / API 35 / arm64.
- Runtime: Termux on Android.
- Java runtime used by Gradle: OpenJDK 21.
- Gradle: 8.9.
- Android Gradle Plugin: 8.7.3.
- Kotlin Gradle Plugin: 2.0.21.
- Android SDK Platform: 35.
- Native Termux AAPT2 override: `/data/data/com.termux/files/usr/bin/aapt2`.

## Source smoke tests

Command:

```bash
bash tools/run-all-smoke.sh
```

| Check | Result |
|---|---:|
| Core Kotlin smoke | 36 PASS |
| Bounded Agent Runtime smoke | 29 PASS |
| Pending UI Action smoke | 25 PASS |
| Permission-flow source assertions | 8 PASS |
| Android XML parsing | 3 files PASS |
| **Total source assertions** | **98 PASS** |

All four smoke groups completed and the script exited with code 0. The Kotlin CLI emits a non-fatal Jansi warning because its bundled Linux/ARM64 native library expects glibc `libc.so.6`, while Termux uses Android/Bionic. The assertions continue to compile and execute successfully.

## Android Gradle unit tests

Command:

```bash
gradle --console=plain testDebugUnitTest --no-daemon --max-workers=2
```

| Task | Result |
|---|---|
| `:app:compileDebugKotlin` | PASS |
| `:app:compileDebugUnitTestKotlin` | PASS |
| `:app:testDebugUnitTest` | BUILD SUCCESSFUL |
| JUnit total | **68 passed, 0 failures, 0 errors, 0 skipped** |

JUnit breakdown:

| Test suite | Passed |
|---|---:|
| CommandRouterTest | 12 |
| TaskExecutionLedgerTest | 8 |
| UiSelectorTest | 9 |
| PreviewPlannerTest | 5 |
| ContactMatchEngineTest | 5 |
| CallConfirmationPolicyTest | 3 |
| CommandHistoryCodecTest | 4 |
| AIProviderConfigurationTest | 7 |
| PlannerResponseAdapterTest | 4 |
| TaskExecutionMonitorTest | 2 |
| UiAgentContextObservationTest | 3 |
| UiCompactContextTest | 3 |
| UiVisualFallbackPolicyTest | 3 |
| **Total** | **68** |

The counts were read from all 13 generated JUnit XML suite reports. The final Gradle test task completed with exit code 0.

## Current feature validation boundaries

- Contact matching logic is unit-tested. The runtime call route still uses Android's system contact picker; the app does not scan the full address book.
- Call confirmation policy is unit-tested. The final action opens the Android dialer after user confirmation; ERA does not place the call.
- Instagram preparation is a task-plan preview. Media selection and final Share remain user-controlled.
- Provider configuration, validation, fallback policy and remote-plan conversion are unit-tested. No live Gemini/OpenAI request was made and no user API key was configured.
- The screenshot capture callback and Android Keystore interaction compile as app source but have not been runtime-tested on an installed updated APK.
- The task monitor is a plan/ledger projection, not a continuous autonomous execution loop.
- Agent Browser/Chrome CDP integration and visual product/price search are still pending.

## APK/build scope for this revision

- APK assembly was intentionally **not run** in this work session, in accordance with the user's instruction.
- No updated APK was generated, copied or installed.
- The previous APK in Downloads belongs to an earlier source revision and must not be treated as containing these changes.
- No install/launch, screenshot callback, external provider, or Instagram live runtime test was performed against this source revision.

## Non-fatal environment notes

- Android SDK metadata emitted a warning that the installed command-line tools understand SDK XML only through version 3 while an SDK XML v4 file was present.
- `android.aapt2FromMavenOverride` is reported as experimental; it was already used by the project environment.
- The above warnings did not fail source smoke checks or Gradle unit tests.

