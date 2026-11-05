#!/usr/bin/env bash
# Read-only diagnostics. Missing optional tools must not block rule updates.
set -u

printf 'Проверка инструментов в PATH установщика:\n'
if tool_path="$(type -P plannotator)" && [ -f "$tool_path" ] && [ -x "$tool_path" ]; then
  printf '  plannotator: найден — %s\n' "$tool_path"
else
  printf '  plannotator: не найден в PATH\n'
  printf '    Официальный GitHub и актуальная установка: https://github.com/backnotprop/plannotator\n'
fi
printf 'Проверяется наличие CLI; запуск и PATH внутри BB/агента проверяются отдельно.\n'
printf 'Отсутствие инструмента не блокирует обновление правил. Автоустановки нет.\n'
