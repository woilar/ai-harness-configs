#!/usr/bin/env bash
# Compatibility entry point; use the maintained OMP installer.
set -euo pipefail
script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd -P)"
exec bash "$script_dir/../../omp/scripts/install.sh" "$@"

