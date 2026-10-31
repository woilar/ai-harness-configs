#!/usr/bin/env python3
"""Read-only checks for the global OpenCode V2 framework installation."""
import os
from pathlib import Path
import shutil
import subprocess
import sys

from install import ROOT, SOURCE, parse


def verify():
    failures = []
    warnings = []
    home = Path.home()
    config_root = Path(os.environ.get('XDG_CONFIG_HOME') or home / '.config')
    if not config_root.is_absolute():
        print('Ошибка: XDG_CONFIG_HOME должен быть абсолютным путём.', file=sys.stderr)
        return 1
    target = config_root / 'opencode'
    configs = [p for p in (target / 'opencode.json', target / 'opencode.jsonc') if p.exists() or p.is_symlink()]
    data = {}
    if len(configs) != 1:
        failures.append('Нужен ровно один глобальный opencode.json или opencode.jsonc.')
    else:
        try:
            data = parse(configs[0].read_text(encoding='utf-8'))
        except (OSError, ValueError) as e:
            failures.append('Не удалось прочитать глобальный конфиг: ' + str(e))
    for key, path in [('instructions', SOURCE / 'role-orchestration.md'), ('skills', SOURCE / 'skills')]:
        values = data.get(key)
        if not isinstance(values, list) or str(path) not in values:
            failures.append(f'В {key} отсутствует путь {path}.')
        else:
            print(f'OK: {key} содержит путь к фреймворку.')
    for file in ['role-orchestration.md', 'references/policy-details.md',
                 'skills/coach/SKILL.md', 'skills/coach/references/java-impact-checklist.md',
                 'skills/coach/references/scientific-basis.md', 'skills/copilot/SKILL.md',
                 'skills/copilot/references/java-impact-checklist.md', 'skills/executor/SKILL.md',
                 'skills/executor/references/java-impact-checklist.md']:
        try:
            text = (SOURCE / file).read_text(encoding='utf-8')
            if not text.strip(): failures.append('Пустой файл: ' + str(SOURCE / file))
            if file.endswith('/SKILL.md'):
                name = Path(file).parent.name
                front = text.split('---', 2)
                if len(front) < 3 or not text.startswith('---\n') or f'name: {name}' not in front[1] or 'description:' not in front[1]:
                    failures.append('Проверьте имя и description скилла: ' + str(SOURCE / file))
        except (OSError, UnicodeError) as e:
            failures.append(f'Недоступен {SOURCE / file}: {e}')
    if not failures: print('OK: файлы фреймворка и метаданные скиллов доступны.')
    # A user-owned global skill with a matching ID can affect discovery/precedence.
    for name in ('coach', 'copilot', 'executor'):
        for base in (target / 'skills', home / '.agents/skills', home / '.claude/skills'):
            candidate = base / name
            if candidate.exists() or candidate.is_symlink(): warnings.append(f'Проверьте дублирование скилла {name}: {candidate}')
    old = home / '.engineering-learning'
    if old.exists() and not old.is_symlink(): warnings.append(f'Есть прежний журнал: {old}. Проверьте перенос в {home / "ai/learning"}.')
    binary = shutil.which('opencode')
    if binary:
        try:
            r = subprocess.run([binary, '--version'], capture_output=True, text=True, timeout=10)
            version = (r.stdout or r.stderr).strip().splitlines()
            ver = version[0] if version else 'нет ответа'
            if r.returncode != 0 or not ver.lstrip('v').startswith('2.'):
                warnings.append(f'Не удалось подтвердить OpenCode V2 по --version: {ver}')
            else: print('OK: версия OpenCode:', ver)
        except (OSError, subprocess.TimeoutExpired) as e: warnings.append(f'Версия OpenCode не проверена: {e}')
    else: warnings.append('OpenCode CLI не найден в PATH; Desktop и версию V2 проверьте отдельно.')
    for message in failures: print('ОШИБКА:', message, file=sys.stderr)
    for message in warnings: print('ВНИМАНИЕ:', message)
    print('Проверка не подтверждает подключение модели, фактическое обнаружение скиллов или их поведение в сессии.')
    return 1 if failures else 0


if __name__ == '__main__':
    sys.exit(verify())
