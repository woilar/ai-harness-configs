#!/usr/bin/env bash
set -euo pipefail
die() { printf 'Ошибка: %s\n' "$*" >&2; exit 2; }
target=''
dry_run=0
while [ "$#" -gt 0 ]; do
  case "$1" in
    --target) [ "$#" -ge 2 ] || die 'После --target нужен путь.'; target="$2"; shift 2 ;;
    --dry-run) dry_run=1; shift ;;
    *) die "Неизвестный параметр: $1" ;;
  esac
done
case "$target" in /*) ;; *) die '--target требует абсолютного пути.' ;; esac
while [ "${target%/}" != "$target" ]; do target="${target%/}"; done
[ -n "$target" ] || die 'Корень файловой системы не является целевым каталогом.'
script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd -P)"
source_root="$(cd "$script_dir/../agent" && pwd -P)"
legacy_path="$(cd "$script_dir/../.." && pwd -P)/omp/agent"
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
  if [ -L "$destination" ] && { [ "$(readlink "$destination")" = "$source" ] || { [ "$(readlink "$destination")" = "$legacy_path/$relative" ] && [ -e "$destination" ]; }; }; then
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
  printf 'Установка не начата. Сохраните и объедините конфликтующие файлы вручную.\n' >&2
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
  if [ -L "$destination" ] && { [ "$(readlink "$destination")" = "$source" ] || { [ "$(readlink "$destination")" = "$legacy_path/$relative" ] && [ -e "$destination" ]; }; }; then
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
printf 'Готово. Новых ссылок: %s. Запустите новую сессию harness.\n' "$created_count"
