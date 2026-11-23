#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
OUT="${TMPDIR:-/tmp}/era-core-smoke.jar"
GRADLE_CACHE="${GRADLE_USER_HOME:-$HOME/.gradle}/caches/modules-2/files-2.1"
COROUTINES_JAR="${COROUTINES_JAR:-$(find "$GRADLE_CACHE/org.jetbrains.kotlinx/kotlinx-coroutines-core-jvm" -name 'kotlinx-coroutines-core-jvm-*.jar' 2>/dev/null | sort -V | tail -n 1)}"
if [[ -z "$COROUTINES_JAR" || ! -f "$COROUTINES_JAR" ]]; then
  echo "Missing kotlinx-coroutines-core-jvm. Set COROUTINES_JAR or restore the Gradle dependency cache." >&2
  exit 2
fi
kotlinc \
  -cp "$COROUTINES_JAR" \
  "$ROOT/app/src/main/java/com/eraassistant/mobile/core/AssistantCommand.kt" \
  "$ROOT/app/src/main/java/com/eraassistant/mobile/core/CommandRouter.kt" \
  "$ROOT/app/src/main/java/com/eraassistant/mobile/planner/TaskPlan.kt" \
  "$ROOT/app/src/main/java/com/eraassistant/mobile/memory/PrivacyFilter.kt" \
  "$ROOT/app/src/main/java/com/eraassistant/mobile/screen/UiElement.kt" \
  "$ROOT/app/src/main/java/com/eraassistant/mobile/screen/UiSnapshotFingerprint.kt" \
  "$ROOT/app/src/main/java/com/eraassistant/mobile/screen/UiActionProposal.kt" \
  "$ROOT/app/src/main/java/com/eraassistant/mobile/screen/SensitiveInputPolicy.kt" \
  "$ROOT/app/src/main/java/com/eraassistant/mobile/observation/NormalizedObservation.kt" \
  "$ROOT/app/src/main/java/com/eraassistant/mobile/capability/CapabilityContract.kt" \
  "$ROOT/app/src/main/java/com/eraassistant/mobile/capability/CapabilitySchemas.kt" \
  "$ROOT/app/src/main/java/com/eraassistant/mobile/capability/CapabilityRegistry.kt" \
  "$ROOT/app/src/main/java/com/eraassistant/mobile/statemachine/TaskStatePersistence.kt" \
  "$ROOT/app/src/main/java/com/eraassistant/mobile/statemachine/TaskStateMachine.kt" \
  "$ROOT/app/src/main/java/com/eraassistant/mobile/verification/VerificationEngine.kt" \
  "$ROOT/app/src/main/java/com/eraassistant/mobile/recovery/ExecutionJournal.kt" \
  "$ROOT/app/src/main/java/com/eraassistant/mobile/orchestration/TaskPlanDAG.kt" \
  "$ROOT/app/src/main/java/com/eraassistant/mobile/orchestration/PlannerFeedbackLoop.kt" \
  "$ROOT/app/src/main/java/com/eraassistant/mobile/tasks/TaskExecutionLedger.kt" \
  "$ROOT/app/src/main/java/com/eraassistant/mobile/tasks/TaskLedgerPersistence.kt" \
  "$ROOT/app/src/main/java/com/eraassistant/mobile/tasks/ActionApprovalStore.kt" \
  "$ROOT/app/src/main/java/com/eraassistant/mobile/execution/AutonomyExecutionPolicy.kt" \
  "$ROOT/app/src/main/java/com/eraassistant/mobile/tasks/TaskExecutionCoordinator.kt" \
  "$ROOT/tools/CoreSmoke.kt" \
  -include-runtime -d "$OUT"
java -cp "$OUT:$COROUTINES_JAR" CoreSmokeKt
