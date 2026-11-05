#!/usr/bin/env bash
# Read-only diagnostics. Missing optional tools must not block rule updates.
set -u

printf 'Проверка инструментов в PATH установщика:\n'
for tool in bd plannotator; do
  if tool_path="$(type -P "$tool")" && [ -f "$tool_path" ] && [ -x "$tool_path" ]; then
    printf '  %s: найден — %s\n' "$tool" "$tool_path"
  else
    printf '  %s: не найден в PATH\n' "$tool"
    case "$tool" in
      bd) printf '    Установка через Homebrew: brew install beads\n' ;;
      plannotator) printf '    Установка macOS/Linux: curl -fsSL https://plannotator.ai/install.sh | bash -s -- --minimal\n' ;;
    esac
  fi
done
printf 'Проверяется наличие CLI; запуск и PATH внутри BB/агента проверяются отдельно.\n'
printf 'Отсутствие инструмента не блокирует обновление правил. Автоустановки нет.\n'
