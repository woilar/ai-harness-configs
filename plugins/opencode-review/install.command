#!/usr/bin/env bash
set -euo pipefail
root="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd -P)"
if [ -f "$root/scripts/install.py" ]; then exec python3 "$root/scripts/install.py" "$@"; fi
exec python3 "$root/install.py" "$@"
