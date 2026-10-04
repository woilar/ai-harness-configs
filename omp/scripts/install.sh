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
source_root="$(cd "$script_dir/../agent" && pwd -P)"
required_files=(
  'AGENTS.md'
  'references/policy-details.md'
  'skills/copilot/SKILL.md'
  'skills/copilot/references/java-impact-checklist.md'
  'skills/coach/SKILL.md'
  'skills/coach/references/java-impact-checklist.md'
  'skills/coach/references/scientific-basis.md'
  'skills/executor/SKILL.md'
  'skills/executor/references/java-impact-checklist.md'
)
for relative in "${required_files[@]}"; do
  [ -f "$source_root/$relative" ] && [ -r "$source_root/$relative" ] ||
    die "Отсутствует или недоступен исходник: $source_root/$relative"
done

relative_paths=(
  'AGENTS.md'
  'references/policy-details.md'
  'skills/copilot'
  'skills/coach'
  'skills/executor'
)
conflicts=0
for relative in "${relative_paths[@]}"; do
  destination="$target/$relative"
  source="$source_root/$relative"
  if [ -L "$destination" ] && [ "$(readlink "$destination")" = "$source" ]; then
    continue
  fi
  if [ -e "$destination" ] || [ -L "$destination" ]; then
    printf 'Конфликт: %s\n' "$destination" >&2
    conflicts=$((conflicts + 1))
  fi
  parent="$(dirname "$destination")"
  while [ "$parent" != '/' ]; do
    if { [ -e "$parent" ] || [ -L "$parent" ]; } && [ ! -d "$parent" ]; then
      printf 'Родительский путь не является папкой: %s\n' "$parent" >&2
      conflicts=$((conflicts + 1))
      break
    fi
    parent="$(dirname "$parent")"
  done
done
if [ "$conflicts" -gt 0 ]; then
  printf 'Установка не начата. Объедините инструкции и сохраните конфликтующие файлы согласно omp/README.md.\n' >&2
  exit 1
fi

printf 'Источник: %s\nЦель: %s\n' "$source_root" "$target"
if [ "$dry_run" -eq 1 ]; then
  for relative in "${relative_paths[@]}"; do
    if [ -L "$target/$relative" ]; then
      printf 'Уже подключено: %s\n' "$target/$relative"
    else
      printf 'Создать ссылку: %s -> %s\n' "$target/$relative" "$source_root/$relative"
    fi
  done
  printf 'Dry-run: ничего не записано.\n'
  exit 0
fi

created_paths=()
created_count=0
cleanup() {
  result=$?
  trap - EXIT
  if [ "$result" -ne 0 ]; then
    for ((i=0; i<created_count; i++)); do
      relative="${created_paths[$i]}"
      destination="$target/$relative"
      if [ -L "$destination" ] && [ "$(readlink "$destination")" = "$source_root/$relative" ]; then
        rm "$destination" || true
      fi
    done
    printf 'Установка прервана; новые ссылки этого запуска убраны.\n' >&2
  fi
  exit "$result"
}
trap cleanup EXIT
trap 'exit 130' INT
trap 'exit 143' TERM

for relative in "${relative_paths[@]}"; do
  destination="$target/$relative"
  source="$source_root/$relative"
  if [ -L "$destination" ] && [ "$(readlink "$destination")" = "$source" ]; then
    printf 'Уже подключено: %s\n' "$destination"
    continue
  fi
  # Recheck after preflight, before each write.
  if [ -e "$destination" ] || [ -L "$destination" ]; then
    printf 'Путь появился во время установки: %s\n' "$destination" >&2
    exit 1
  fi
  mkdir -p "$(dirname "$destination")"
  ln -s "$source" "$destination"
  created_paths[$created_count]="$relative"
  created_count=$((created_count + 1))
  printf 'Подключено: %s\n' "$destination"
done
printf 'Готово. Новых ссылок: %s. Запустите новую сессию Oh My Pi.\n' "$created_count"
