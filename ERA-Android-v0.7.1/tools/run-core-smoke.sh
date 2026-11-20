#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
OUT="${TMPDIR:-/tmp}/era-core-smoke.jar"
kotlinc \
  "$ROOT/app/src/main/java/com/eraassistant/mobile/core/AssistantCommand.kt" \
  "$ROOT/app/src/main/java/com/eraassistant/mobile/core/CommandRouter.kt" \
  "$ROOT/app/src/main/java/com/eraassistant/mobile/planner/TaskPlan.kt" \
  "$ROOT/app/src/main/java/com/eraassistant/mobile/screen/UiElement.kt" \
  "$ROOT/app/src/main/java/com/eraassistant/mobile/screen/UiSnapshotFingerprint.kt" \
  "$ROOT/app/src/main/java/com/eraassistant/mobile/screen/UiActionProposal.kt" \
  "$ROOT/app/src/main/java/com/eraassistant/mobile/tasks/TaskExecutionLedger.kt" \
  "$ROOT/app/src/main/java/com/eraassistant/mobile/tasks/TaskLedgerPersistence.kt" \
  "$ROOT/app/src/main/java/com/eraassistant/mobile/tasks/TaskExecutionCoordinator.kt" \
  "$ROOT/tools/CoreSmoke.kt" \
  -include-runtime -d "$OUT"
java -jar "$OUT"
