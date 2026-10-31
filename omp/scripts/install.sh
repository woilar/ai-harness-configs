#!/usr/bin/env bash
# Apache License 2.0. Compatible with Bash 3.2 on macOS and Bash on Linux.
set -euo pipefail

usage() {
  cat <<'USAGE'
Установка глобальных инструкций и skills Oh My Pi.

Использование: bash omp/scripts/install.sh [параметры]
  --profile NAME  Стандартный каталог именованного профиля; default — обычный.
  --target PATH   Собственный абсолютный путь к agent-каталогу.
  --dry-run       Показать действия без записи.
  --help          Эта справка.

Без параметров: ~/.omp/agent/. --profile и --target несовместимы.
Существующие чужие файлы, каталоги и ссылки не перезаписываются.
USAGE
}

die() {
  printf 'Ошибка: %s\n' "$*" >&2
  exit 2
}

profile='default'
target=''
profile_selected=0
target_selected=0
dry_run=0
while [ "$#" -gt 0 ]; do
  case "$1" in
    --profile)
      [ "$#" -ge 2 ] || die 'После --profile нужно имя.'
      profile="$2"
      profile_selected=1
      shift 2
      ;;
    --target)
      [ "$#" -ge 2 ] || die 'После --target нужен путь.'
      target="$2"
      target_selected=1
      shift 2
      ;;
    --dry-run) dry_run=1; shift ;;
    --help|-h) usage; exit 0 ;;
    *) die "Неизвестный параметр: $1. Используйте --help." ;;
  esac
done
[ "$profile_selected" -eq 0 ] || [ "$target_selected" -eq 0 ] ||
  die 'Выберите --profile или --target, а не оба.'
case "$profile" in
  ''|*[!a-zA-Z0-9_-]*) die 'Имя профиля: латинские буквы, цифры, _ или -.' ;;
esac
if [ "$target_selected" -eq 0 ]; then
  if [ "$profile" = 'default' ]; then
    target="$HOME/.omp/agent"
  else
    target="$HOME/.omp/profiles/$profile/agent"
  fi
fi
case "$target" in
  /*) ;;
  *) die '--target требует абсолютного пути.' ;;
esac
while [ "${target%/}" != "$target" ]; do target="${target%/}"; done
[ -n "$target" ] || die 'Корень файловой системы не является agent-каталогом.'

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd -P)"
repo_root="$(cd "$script_dir/../.." && pwd -P)"
if [ "$dry_run" -eq 1 ]; then
  exec bash "$repo_root/shared/scripts/install.sh" --target "$target" --dry-run
fi
exec bash "$repo_root/shared/scripts/install.sh" --target "$target"
