#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
CACHE="${GRADLE_USER_HOME:-$HOME/.gradle}/caches/modules-2/files-2.1"
COROUTINES_JAR="${COROUTINES_JAR:-$CACHE/org.jetbrains.kotlinx/kotlinx-coroutines-core-jvm/1.7.3/2b09627576f0989a436a00a4a54b55fa5026fb86/kotlinx-coroutines-core-jvm-1.7.3.jar}"
JUNIT_JAR="${JUNIT_JAR:-$CACHE/junit/junit/4.13.2/8ac9e16d933b6fb43bc7f576336b8f4d7eb5ba12/junit-4.13.2.jar}"
HAMCREST_JAR="${HAMCREST_JAR:-$CACHE/org.hamcrest/hamcrest-core/1.3/42a25dc3219429f0e5d060061f71acb49bf010a0/hamcrest-core-1.3.jar}"
OUT="${TMPDIR:-/tmp}/era-p0-unit-tests.jar"
for file in "$COROUTINES_JAR" "$JUNIT_JAR" "$HAMCREST_JAR"; do
  [[ -f "$file" ]] || { echo "Missing test dependency: $file" >&2; exit 2; }
done
CP="$COROUTINES_JAR:$JUNIT_JAR:$HAMCREST_JAR"
MAIN="$ROOT/app/src/main/java/com/eraassistant/mobile"
TEST="$ROOT/app/src/test/java/com/eraassistant/mobile"
kotlinc -cp "$CP" \
  "$MAIN/core/AssistantCommand.kt" \
  "$MAIN/planner/TaskPlan.kt" \
  "$MAIN/memory/PrivacyFilter.kt" \
  "$MAIN/observation/NormalizedObservation.kt" \
  "$MAIN/screen/UiElement.kt" \
  "$MAIN/screen/UiActionProposal.kt" \
  "$MAIN/capability/CapabilityContract.kt" \
  "$MAIN/capability/CapabilitySchemas.kt" \
  "$MAIN/capability/CapabilityRegistry.kt" \
  "$MAIN/statemachine/TaskStatePersistence.kt" \
  "$MAIN/statemachine/TaskStateMachine.kt" \
  "$MAIN/verification/VerificationEngine.kt" \
  "$MAIN/recovery/ExecutionJournal.kt" \
  "$MAIN/orchestration/TaskPlanDAG.kt" \
  "$MAIN/orchestration/PlannerFeedbackLoop.kt" \
  "$MAIN/tasks/TaskLedgerPersistence.kt" \
  "$MAIN/tasks/TaskExecutionLedger.kt" \
  "$MAIN/tasks/TaskExecutionMonitor.kt" \
  "$MAIN/approval/VoiceApprovalEngine.kt" \
  "$MAIN/storage/SafStoragePolicy.kt" \
  "$MAIN/execution/ActionIntentCompiler.kt" \
  "$MAIN/tasks/ActionApprovalStore.kt" \
  "$MAIN/tasks/TaskExecutionCoordinator.kt" \
  "$TEST/recovery/ExecutionJournalTest.kt" \
  "$TEST/statemachine/TaskStateMachineTest.kt" \
  "$TEST/statemachine/TaskStatePersistenceTest.kt" \
  "$TEST/observation/NormalizedObservationBoundsTest.kt" \
  "$TEST/approval/VoiceApprovalEngineTest.kt" \
  "$TEST/execution/ActionIntentCompilerTest.kt" \
  "$TEST/storage/SafStoragePolicyTest.kt" \
  "$TEST/tasks/ActionApprovalStoreTest.kt" \
  "$TEST/verification/VerificationEngineTest.kt" \
  "$TEST/tasks/TaskExecutionLedgerTest.kt" \
  "$TEST/tasks/TaskExecutionMonitorTest.kt" \
  "$TEST/tasks/TaskExecutionCoordinatorTest.kt" \
  -include-runtime -d "$OUT"
java -cp "$OUT:$CP" org.junit.runner.JUnitCore \
  com.eraassistant.mobile.recovery.ExecutionJournalTest \
  com.eraassistant.mobile.statemachine.TaskStateMachineTest \
  com.eraassistant.mobile.statemachine.TaskStatePersistenceTest \
  com.eraassistant.mobile.observation.NormalizedObservationBoundsTest \
  com.eraassistant.mobile.approval.VoiceApprovalEngineTest \
  com.eraassistant.mobile.execution.ActionIntentCompilerTest \
  com.eraassistant.mobile.storage.SafStoragePolicyTest \
  com.eraassistant.mobile.tasks.ActionApprovalStoreTest \
  com.eraassistant.mobile.verification.VerificationEngineTest \
  com.eraassistant.mobile.tasks.TaskExecutionLedgerTest \
  com.eraassistant.mobile.tasks.TaskExecutionMonitorTest \
  com.eraassistant.mobile.tasks.TaskExecutionCoordinatorTest
