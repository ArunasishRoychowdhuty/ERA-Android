#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"

printf '\n=== ERA Core smoke tests ===\n'
bash tools/run-core-smoke.sh

printf '\n=== ERA Bounded Agent Runtime smoke tests ===\n'
bash tools/run-agent-runtime-smoke.sh

printf '\n=== ERA Pending UI Action smoke tests ===\n'
bash tools/run-pending-action-smoke.sh

printf '\n=== ERA Permission-flow and XML checks ===\n'
python3 tools/check-permission-flow.py

printf '\nALL ERA SOURCE SMOKE CHECKS PASSED\n'
