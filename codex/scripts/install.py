#!/usr/bin/env python3
"""Install global Codex rules and role links without replacing user settings."""
import argparse
import hashlib
import os
from pathlib import Path
import re
import shutil
import sys
import tempfile

ROOT = Path(__file__).resolve().parents[2] / 'shared' / 'agent'
BEGIN = '<!-- AI-HARNESS-ROLES:BEGIN -->'
END = '<!-- AI-HARNESS-ROLES:END -->'
HASH = re.compile(r'<!-- AI-HARNESS-ROLES:SHA256=([a-f0-9]{64}) -->\n')


def error(message):
    raise ValueError(message)


def absolute(value):
    path = Path(value)
    if not path.is_absolute() or path == Path('/'):
        raise argparse.ArgumentTypeError('Требуется абсолютный путь, отличный от корня.')
    return path


def read_regular(path):
    if path.is_symlink():
        error(f'Символическая ссылка для глобальных инструкций требует ручной проверки: {path}')
    if path.exists() and not path.is_file():
        error(f'Глобальные инструкции не являются файлом: {path}')
    return path.read_bytes() if path.exists() else None


def render(original, router, policy):
    try:
        text = (original or b'').decode('utf-8')
        role = router.decode('utf-8').rstrip('\n') + '\n'
    except UnicodeError:
        error('Инструкции должны быть в UTF-8; файл не изменён.')
    # The environment-specific absolute path makes the shared policy discoverable.
    body = role + f'\nПодробная политика фреймворка: {policy}\n'
    digest = hashlib.sha256(body.encode('utf-8')).hexdigest()
    block = f'{BEGIN}\n<!-- AI-HARNESS-ROLES:SHA256={digest} -->\n{body}{END}'
    if text.count(BEGIN) != text.count(END) or text.count(BEGIN) > 1:
        error('Повреждены или дублируются маркеры управляемого блока.')
    if text.count(BEGIN) == 0 and 'AI-HARNESS-ROLES:' in text:
        error('Обнаружены повреждённые маркеры управляемого блока.')
    if text.count(BEGIN) == 0:
        separator = '' if not text or text.endswith('\n\n') else ('\n' if text.endswith('\n') else '\n\n')
        return (text + separator + block + '\n').encode('utf-8')
    start = text.index(BEGIN)
    end = text.index(END, start) + len(END) if END in text[start:] else -1
    if end < 0:
        error('Неверный порядок маркеров управляемого блока.')
    prior = text[start + len(BEGIN):end - len(END)]
    if not prior.startswith('\n'):
        error('Неизвестный формат управляемого блока.')
    match = HASH.match(prior[1:])
    if not match:
        error('Отсутствует контрольная сумма управляемого блока.')
    old_body = prior[1 + match.end():]
    if hashlib.sha256(old_body.encode('utf-8')).hexdigest() != match.group(1):
        error('Управляемый блок изменён вручную; проверьте его перед обновлением.')
    return (text[:start] + block + text[end:]).encode('utf-8')


def atomic_write(path, content, mode=None):
    fd, temporary = tempfile.mkstemp(prefix='.ai-harness-', dir=str(path.parent))
    try:
        with os.fdopen(fd, 'wb') as stream:
            stream.write(content)
            stream.flush()
            os.fsync(stream.fileno())
        if mode is not None:
            os.chmod(temporary, mode)
        os.replace(temporary, path)
    finally:
        if os.path.exists(temporary):
            os.unlink(temporary)


def main():
    parser = argparse.ArgumentParser(description='Подключить правила и skills к локальному Codex.')
    parser.add_argument('--codex-home', type=absolute, default=absolute(os.environ.get('CODEX_HOME') or str(Path.home() / '.codex')))
    parser.add_argument('--skills-dir', type=absolute, default=absolute(str(Path.home() / '.agents' / 'skills')))
    parser.add_argument('--dry-run', action='store_true')
    args = parser.parse_args()
    codex, skills = args.codex_home, args.skills_dir
    required = ['role-orchestration.md', 'references/policy-details.md',
                'skills/coach/SKILL.md', 'skills/coach/references/scientific-basis.md',
                *[f'skills/{role}/references/java-impact-checklist.md' for role in ('coach', 'copilot', 'executor')],
                'skills/copilot/SKILL.md', 'skills/executor/SKILL.md']
    for relative in required:
        p = ROOT / relative
        if not p.is_file() or not os.access(p, os.R_OK):
            error(f'Исходный файл недоступен: {p}')
    override = codex / 'AGENTS.override.md'
    override_bytes = read_regular(override)
    active = override if override_bytes else codex / 'AGENTS.md'
    before = read_regular(active)
    if active != override and override_bytes is None and override.is_symlink():
        error(f'Недоступная ссылка: {override}')
    source_policy = ROOT / 'references/policy-details.md'
    links = {codex / 'references/policy-details.md': source_policy,
             **{skills / role: ROOT / 'skills' / role for role in ('coach', 'copilot', 'executor')}}
    for dest, source in links.items():
        if dest.is_symlink() and os.readlink(dest) == str(source) and dest.exists():
            continue
        if dest.exists() or dest.is_symlink():
            error(f'Конфликт: {dest}; существующий путь сохранён.')
        for parent in dest.parents:
            if (parent.exists() or parent.is_symlink()) and not parent.is_dir():
                error(f'Родительский путь не является папкой: {parent}')
    for parent in active.parents:
        if (parent.exists() or parent.is_symlink()) and not parent.is_dir():
            error(f'Родительский путь не является папкой: {parent}')
    after = render(before, (ROOT / 'role-orchestration.md').read_bytes(), source_policy)
    change = before != after
    print(f'Активные глобальные инструкции: {active}')
    print(f'Управляемый блок: {"обновить" if change else "уже актуален"}')
    if len(after) > 32768:
        print('ВНИМАНИЕ: размер инструкций больше стандартного лимита Codex 32 KiB; проверьте project_doc_max_bytes.')
    for dest, source in links.items():
        print(f'{"Уже подключено" if dest.is_symlink() else "Создать ссылку"}: {dest} -> {source}')
    if args.dry_run:
        print('Dry-run: ничего не записано.')
        return
    created = []
    backup = None
    installed = False
    try:
        for dest, source in links.items():
            if dest.is_symlink() and os.readlink(dest) == str(source) and dest.exists():
                continue
            dest.parent.mkdir(parents=True, exist_ok=True)
            os.symlink(source, dest)
            created.append(dest)
        if read_regular(override) != override_bytes or read_regular(active) != before:
            error('Инструкции изменились во время установки; повторите после проверки.')
        if change:
            active.parent.mkdir(parents=True, exist_ok=True)
            mode = active.stat().st_mode & 0o777 if before is not None else 0o600
            if before is not None:
                fd, name = tempfile.mkstemp(prefix=active.name + '.ai-harness-backup-', dir=str(active.parent))
                os.close(fd)
                backup = Path(name)
                shutil.copy2(active, backup)
                if backup.read_bytes() != before:
                    error('Резервная копия не совпадает с исходным файлом.')
                print(f'Резервная копия: {backup}')
            atomic_write(active, after, mode)
            installed = True
            if active.read_bytes() != after:
                error('Не удалось подтвердить записанные инструкции.')
        print('Готово. Откройте новую сессию Codex и проверьте skills и инструкции.')
    except BaseException:
        if installed:
            if backup and backup.exists():
                os.replace(backup, active)
            elif before is None and active.exists() and active.read_bytes() == after:
                active.unlink()
        for path in reversed(created):
            if path.is_symlink() and os.readlink(path) == str(links[path]):
                path.unlink()
        raise


if __name__ == '__main__':
    try:
        main()
    except (ValueError, OSError) as exc:
        print(f'Ошибка: {exc}', file=sys.stderr)
        sys.exit(1)
