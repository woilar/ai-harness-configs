#!/usr/bin/env bash
set -euo pipefail
export PYTHONDONTWRITEBYTECODE=1
script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd -P)"
if [ "${1:-}" = "--verify" ]; then
  [ "$#" -eq 1 ] || { printf "--verify не принимает другие параметры.\n" >&2; exit 2; }
  exec python3 "$script_dir/verify.py"
fi
exec python3 "$script_dir/install.py" "$@"
