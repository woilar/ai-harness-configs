#!/usr/bin/env bash
# macOS Bash 3.2 and Linux Bash; no changes to other Codex settings.
set -euo pipefail

die() { printf 'Ошибка: %s\n' "$*" >&2; exit 2; }
usage() {
  cat <<'USAGE'
Подключение глобальных правил и skills к локальному Codex CLI / IDE.

Использование: bash codex/scripts/install.sh [--dry-run] [--codex-home PATH] [--skills-dir PATH]
  --codex-home PATH  Каталог инструкций (по умолчанию $CODEX_HOME или ~/.codex).
  --skills-dir PATH  Пользовательский каталог skills (по умолчанию ~/.agents/skills).
  --dry-run          Показать действия, ничего не записывая.
  --help             Эта справка.
Пути должны быть абсолютными. Существующие файлы не перезаписываются.
USAGE
}

codex_home="${CODEX_HOME:-$HOME/.codex}"
skills_dir="$HOME/.agents/skills"
dry_run=0
while [ "$#" -gt 0 ]; do
  case "$1" in
    --codex-home) [ "$#" -ge 2 ] || die 'После --codex-home нужен путь.'; codex_home="$2"; shift 2 ;;
    --skills-dir) [ "$#" -ge 2 ] || die 'После --skills-dir нужен путь.'; skills_dir="$2"; shift 2 ;;
    --dry-run) dry_run=1; shift ;;
    --help|-h) usage; exit 0 ;;
    *) die "Неизвестный параметр: $1" ;;
  esac
done
for dir in "$codex_home" "$skills_dir"; do
  case "$dir" in /*) ;; *) die 'Каталоги должны быть абсолютными путями.' ;; esac
  [ "$dir" != / ] || die 'Корень файловой системы нельзя использовать как каталог настроек.'
done
while [ "${codex_home%/}" != "$codex_home" ]; do codex_home="${codex_home%/}"; done
while [ "${skills_dir%/}" != "$skills_dir" ]; do skills_dir="${skills_dir%/}"; done
[ -n "$codex_home" ] && [ -n "$skills_dir" ] || die 'Корень файловой системы нельзя использовать как каталог настроек.'
script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd -P)"
source_root="$(cd "$script_dir/../../shared/agent" && pwd -P)"

sources=(
  "$source_root/role-orchestration.md"
  "$source_root/references/policy-details.md"
  "$source_root/skills/coach"
  "$source_root/skills/copilot"
  "$source_root/skills/executor"
)
destinations=(
  "$codex_home/AGENTS.md"
  "$codex_home/references/policy-details.md"
  "$skills_dir/coach"
  "$skills_dir/copilot"
  "$skills_dir/executor"
)
required=(
  "$source_root/role-orchestration.md"
  "$source_root/references/policy-details.md"
  "$source_root/skills/coach/SKILL.md"
  "$source_root/skills/coach/references/scientific-basis.md"
  "$source_root/skills/coach/references/java-impact-checklist.md"
  "$source_root/skills/copilot/SKILL.md"
  "$source_root/skills/copilot/references/java-impact-checklist.md"
  "$source_root/skills/executor/SKILL.md"
  "$source_root/skills/executor/references/java-impact-checklist.md"
)
for file in "${required[@]}"; do [ -f "$file" ] && [ -r "$file" ] || die "Исходный файл недоступен: $file"; done
# Codex reads the override in preference to AGENTS.md. Never change it silently.
if [ -e "$codex_home/AGENTS.override.md" ] || [ -L "$codex_home/AGENTS.override.md" ]; then
  die "Найден $codex_home/AGENTS.override.md; он может перекрыть AGENTS.md. Проверьте и объедините инструкции вручную. Установка не начата."
fi

conflicts=0
for ((i=0; i<${#destinations[@]}; i++)); do
  destination="${destinations[$i]}"; source="${sources[$i]}"
  if [ -L "$destination" ] && [ "$(readlink "$destination")" = "$source" ] && [ -e "$destination" ]; then
    continue
  fi
  if [ -e "$destination" ] || [ -L "$destination" ]; then
    printf 'Конфликт: %s\n' "$destination" >&2; conflicts=$((conflicts+1))
  fi
  parent="$(dirname "$destination")"
  while [ "$parent" != / ]; do
    if { [ -e "$parent" ] || [ -L "$parent" ]; } && [ ! -d "$parent" ]; then
      printf 'Родительский путь не является папкой: %s\n' "$parent" >&2; conflicts=$((conflicts+1)); break
    fi
    parent="$(dirname "$parent")"
  done
done
[ "$conflicts" -eq 0 ] || { printf 'Установка не начата. Существующие файлы сохранены.\n' >&2; exit 1; }
printf 'Общие исходники: %s\nИнструкции: %s\nSkills: %s\n' "$source_root" "$codex_home" "$skills_dir"
if [ "$dry_run" -eq 1 ]; then
  for ((i=0; i<${#destinations[@]}; i++)); do
    if [ -L "${destinations[$i]}" ]; then printf 'Уже подключено: %s\n' "${destinations[$i]}"
    else printf 'Создать ссылку: %s -> %s\n' "${destinations[$i]}" "${sources[$i]}"; fi
  done
  printf 'Dry-run: ничего не записано.\n'; exit 0
fi
created_paths=()
created_sources=()
created_count=0
cleanup() {
  result=$?
  trap - EXIT
  if [ "$result" -ne 0 ]; then
    for ((j=0; j<created_count; j++)); do
      if [ -L "${created_paths[$j]}" ] && [ "$(readlink "${created_paths[$j]}")" = "${created_sources[$j]}" ]; then
        rm "${created_paths[$j]}" || true
      fi
    done
    printf 'Установка прервана; новые ссылки этого запуска убраны.\n' >&2
  fi
  exit "$result"
}
trap cleanup EXIT
trap 'exit 130' INT
trap 'exit 143' TERM
for ((i=0; i<${#destinations[@]}; i++)); do
  destination="${destinations[$i]}"; source="${sources[$i]}"
  if [ -L "$destination" ] && [ "$(readlink "$destination")" = "$source" ] && [ -e "$destination" ]; then
    printf 'Уже подключено: %s\n' "$destination"; continue
  fi
  if [ -e "$destination" ] || [ -L "$destination" ]; then
    printf 'Путь появился во время установки: %s\n' "$destination" >&2; exit 1
  fi
  mkdir -p "$(dirname "$destination")"
  ln -s "$source" "$destination"
  created_paths[$created_count]="$destination"; created_sources[$created_count]="$source"; created_count=$((created_count+1))
  printf 'Подключено: %s\n' "$destination"
done
# A different override could have appeared between preflight and final verification.
if [ -e "$codex_home/AGENTS.override.md" ] || [ -L "$codex_home/AGENTS.override.md" ]; then
  printf 'Во время установки появился AGENTS.override.md: %s\n' "$codex_home" >&2; exit 1
fi
for ((i=0; i<${#destinations[@]}; i++)); do
  [ -L "${destinations[$i]}" ] && [ "$(readlink "${destinations[$i]}")" = "${sources[$i]}" ] && [ -r "${destinations[$i]}" ] || {
    printf 'Не удалось проверить: %s\n' "${destinations[$i]}" >&2; exit 1; }
done
printf 'Готово. Новых ссылок: %s. Проверьте обнаружение в новой сессии Codex.\n' "$created_count"
printf 'Проверка файлов не подтверждает загрузку и следование правилам в сеансе.\n'
