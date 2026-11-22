#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
OUT="${TMPDIR:-/tmp}/era-agent-runtime-smoke.jar"
kotlinc \
  "$ROOT/app/src/main/java/com/eraassistant/mobile/ai/BoundedAgentRuntime.kt" \
  "$ROOT/app/src/main/java/com/eraassistant/mobile/ai/AgentCapabilityRegistry.kt" \
  "$ROOT/app/src/main/java/com/eraassistant/mobile/ai/AgentLoopPolicy.kt" \
  "$ROOT/tools/AgentRuntimeSmoke.kt" \
  -include-runtime -d "$OUT"
java -jar "$OUT"
