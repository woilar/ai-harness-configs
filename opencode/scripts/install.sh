#!/usr/bin/env bash
# Installs the shared framework into OpenCode's global discovery directory.
set -euo pipefail
script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd -P)"
repo_root="$(cd "$script_dir/../.." && pwd -P)"
target="${XDG_CONFIG_HOME:-$HOME/.config}/opencode"
case "$target" in /*) ;; *) printf 'XDG_CONFIG_HOME должен быть абсолютным путём.\n' >&2; exit 2 ;; esac
exec bash "$repo_root/shared/scripts/install.sh" --target "$target" "$@"
