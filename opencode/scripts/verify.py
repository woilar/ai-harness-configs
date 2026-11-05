#!/usr/bin/env python3
"""Read-only checks for the global OpenCode V2 framework installation."""
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys

from install import ROOT, SOURCE, parse, report_plannotator_plugin


def verify(check_tools=True):
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
    config_available = False
    if len(configs) != 1:
        failures.append('Нужен ровно один глобальный opencode.json или opencode.jsonc.')
    else:
        try:
            data = parse(configs[0].read_text(encoding='utf-8'))
            config_available = True
        except (OSError, ValueError) as e:
            failures.append('Не удалось прочитать глобальный конфиг: ' + str(e))
    for key, path in [('instructions', SOURCE / 'role-orchestration.md'), ('skills', SOURCE / 'skills')]:
        values = data.get(key)
        if not isinstance(values, list) or str(path) not in values:
            failures.append(f'В {key} отсутствует путь {path}.')
        else:
            print(f'OK: {key} содержит путь к фреймворку.')
    try:
        reference_paths = (SOURCE / 'reference-files.txt').read_text().splitlines()
    except (OSError, UnicodeError) as e:
        failures.append('Недоступен список модулей: ' + str(e))
        reference_paths = []
    for file in ['role-orchestration.md', *reference_paths,
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
    try:
        router = (SOURCE / 'role-orchestration.md').read_text(encoding='utf-8')
        for relative in re.findall(r'`(references/[\w-]+\.md)`', router):
            if relative not in reference_paths:
                failures.append('Модуль маршрутизатора не зарегистрирован: ' + relative)
        for relative in reference_paths:
            module = SOURCE / relative
            if not module.is_file():
                continue
            for link in re.findall(r'\]\(([^)#]+\.md)\)', module.read_text(encoding='utf-8')):
                if not (module.parent / link).is_file():
                    failures.append(f'Недоступна ссылка модуля {relative}: {link}')
        eager = set(reference_paths) - {'references/policy-details.md'}
        instructions = data.get('instructions', [])
        if isinstance(instructions, list):
            for relative in eager:
                if str(SOURCE / relative) in instructions:
                    warnings.append('Модуль загружается при старте вместо чтения по событию: ' + relative)
    except (OSError, UnicodeError) as e:
        failures.append('Маршрутизация модулей не проверена: ' + str(e))
    if not failures: print('OK: файлы фреймворка и метаданные скиллов доступны.')
    if check_tools:
        report_plannotator_plugin(data if config_available else None, configs[0] if len(configs) == 1 else target)
        try:
            subprocess.run(['bash', str(ROOT / 'shared/scripts/check-tools.sh')], check=False)
        except OSError as e:
            warnings.append('Диагностика Plannotator недоступна: ' + str(e))
    # A user-owned global skill with a matching ID can affect discovery/precedence.
    for name in ('coach', 'copilot', 'executor'):
        for base in (target / 'skills', home / '.agents/skills', home / '.claude/skills'):
            candidate = base / name
            if candidate.exists() or candidate.is_symlink(): warnings.append(f'Проверьте дублирование скилла {name}: {candidate}')
    old = home / '.engineering-learning'
    if old.exists() and not old.is_symlink(): warnings.append(f'Есть прежний журнал: {old}. Проверьте перенос в {home / "ai/learning"}.')
    binary = shutil.which('opencode2') or shutil.which('opencode')
    if binary:
        try:
            r = subprocess.run([binary, '--version'], capture_output=True, text=True, timeout=10)
            version = (r.stdout or r.stderr).strip().splitlines()
            ver = version[0] if version else 'нет ответа'
            if r.returncode != 0 or not ver.lstrip('v').startswith('2.'):
                warnings.append(f'Не удалось подтвердить OpenCode V2 по --version: {ver}')
            else: print('OK: версия OpenCode:', ver, '(' + binary + ')')
        except (OSError, subprocess.TimeoutExpired) as e: warnings.append(f'Версия OpenCode не проверена: {e}')
    else: warnings.append('OpenCode CLI не найден в PATH; Desktop и версию V2 проверьте отдельно.')
    for message in failures: print('ОШИБКА:', message, file=sys.stderr)
    for message in warnings: print('ВНИМАНИЕ:', message)
    print('Проверка не подтверждает подключение модели, фактическое обнаружение скиллов или их поведение в сессии.')
    return 1 if failures else 0


if __name__ == '__main__':
    sys.exit(verify())

