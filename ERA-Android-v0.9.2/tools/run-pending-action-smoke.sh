#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
OUT="${TMPDIR:-/tmp}/era-pending-action-smoke.jar"
kotlinc \
  "$ROOT/tools/AndroidContentTestStubs.kt" \
  "$ROOT/app/src/main/java/com/eraassistant/mobile/screen/UiElement.kt" \
  "$ROOT/app/src/main/java/com/eraassistant/mobile/screen/SensitiveInputPolicy.kt" \
  "$ROOT/app/src/main/java/com/eraassistant/mobile/screen/PendingUiActionStore.kt" \
  "$ROOT/tools/PendingUiActionStoreSmoke.kt" \
  -include-runtime -d "$OUT"
java -jar "$OUT"
