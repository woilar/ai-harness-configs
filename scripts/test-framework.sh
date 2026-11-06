#!/usr/bin/env bash
set -euo pipefail
root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd -P)"
export PYTHONDONTWRITEBYTECODE=1
python3 "$root/tools/omp/tests/test_install.py"
python3 "$root/tools/codex/tests/test_install.py"
python3 "$root/tools/opencode/tests/test_install.py"
python3 "$root/framework/tests/test_tools.py"
