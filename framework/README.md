# Фреймворк работы с AI

- `agent/` — маршрутизатор, политика, справочники и роли Copilot/Coach/Executor.
- `scripts/` — общий установщик OMP и диагностика инструментов.
- `tests/` — проверки диагностики.
- `evals/` — сценарии оценки поведения ролей.

Адаптеры установки находятся в [tools](../tools/):

```bash
bash tools/omp/scripts/install.sh --dry-run
bash tools/opencode/scripts/install.sh --dry-run
bash tools/codex/scripts/install.sh --dry-run
```

Для установки уберите `--dry-run`. Точные параметры и правила сохранения пользовательских настроек — в README каждого инструмента.
