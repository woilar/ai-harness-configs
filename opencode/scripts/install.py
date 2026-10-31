#!/usr/bin/env python3
"""Register the framework in the OpenCode V2 global configuration."""
import argparse
import json
import os
from pathlib import Path
import tempfile

ROOT = Path(__file__).resolve().parents[2]
SOURCE = ROOT / 'shared/agent'


def clean_jsonc(text):
    # Preserve offsets while removing comments and trailing commas outside strings.
    chars = list(text)
    i = 0
    quoted = False
    while i < len(text):
        c = text[i]
        if quoted:
            if c == '\\':
                i += 2
                continue
            if c == '"':
                quoted = False
            i += 1
            continue
        if c == '"':
            quoted = True
            i += 1
        elif text[i:i+2] == '//':
            end = text.find('\n', i)
            end = len(text) if end < 0 else end
            for j in range(i, end): chars[j] = ' '
            i = end
        elif text[i:i+2] == '/*':
            end = text.find('*/', i + 2)
            if end < 0: raise ValueError('Незакрытый комментарий JSONC')
            end += 2
            for j in range(i, end):
                if text[j] not in '\r\n': chars[j] = ' '
            i = end
        else:
            i += 1
    if quoted: raise ValueError('Незакрытая строка JSONC')
    # Strip trailing commas based on the already comment-free representation.
    comment_free = ''.join(chars)
    quoted = False
    i = 0
    while i < len(comment_free):
        c = comment_free[i]
        if quoted:
            if c == '\\': i += 2; continue
            if c == '"': quoted = False
        elif c == '"': quoted = True
        elif c == ',':
            j = i + 1
            while j < len(chars) and chars[j].isspace(): j += 1
            if j < len(chars) and chars[j] in ']}': chars[i] = ' '
        i += 1
    return ''.join(chars)


def parse(text):
    def unique(pairs):
        result = {}
        for k, v in pairs:
            if k in result: raise ValueError('Повторяющийся ключ: ' + k)
            result[k] = v
        return result
    result = json.loads(clean_jsonc(text), object_pairs_hook=unique)
    if not isinstance(result, dict): raise ValueError('Корень конфига должен быть объектом')
    return result


def with_entry(text, name, entry):
    cleaned = clean_jsonc(text)
    data = parse(text)
    if name in data and not isinstance(data[name], list):
        raise ValueError(f'{name} должен быть массивом OpenCode V2')
    if name in data and entry in data[name]: return text
    decoder = json.JSONDecoder()
    skip = lambda i: next((j for j in range(i, len(cleaned)) if not cleaned[j].isspace()), len(cleaned))
    i = skip(0) + 1
    last_value_end = None
    while cleaned[skip(i)] != '}':
        i = skip(i)
        key, end = decoder.raw_decode(cleaned, i)
        i = skip(end)
        if cleaned[i] != ':': raise ValueError('Неверный объект конфига')
        start = skip(i + 1)
        _, end = decoder.raw_decode(cleaned, start)
        if key == name:
            if not data[name]:
                pos = start + 1
                insertion = json.dumps(entry, ensure_ascii=False)
            else:
                pos = start + 1
                while True:
                    pos = skip(pos)
                    _, pos = decoder.raw_decode(cleaned, pos)
                    nxt = skip(pos)
                    if cleaned[nxt] == ']': break
                    if cleaned[nxt] != ',': raise ValueError('Неверный массив')
                    pos = nxt + 1
                insertion = ', ' + json.dumps(entry, ensure_ascii=False)
            return text[:pos] + insertion + text[pos:]
        last_value_end = end
        i = skip(end)
        if cleaned[i] == ',': i += 1
    pos = last_value_end if last_value_end is not None else skip(0) + 1
    insertion = (', ' if last_value_end is not None else '') + json.dumps(name) + ': [' + json.dumps(entry, ensure_ascii=False) + ']'
    return text[:pos] + insertion + text[pos:]


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--dry-run', action='store_true')
    args = parser.parse_args()
    xdg = Path(os.environ.get('XDG_CONFIG_HOME') or Path.home() / '.config')
    if not xdg.is_absolute(): raise ValueError('XDG_CONFIG_HOME должен быть абсолютным путём')
    target = xdg / 'opencode'
    for file in ['role-orchestration.md', 'references/policy-details.md', 'skills/coach/SKILL.md', 'skills/copilot/SKILL.md', 'skills/executor/SKILL.md']:
        if not (SOURCE / file).is_file(): raise ValueError('Нет файла фреймворка: ' + str(SOURCE / file))
    configs = [target / 'opencode.json', target / 'opencode.jsonc']
    present = [p for p in configs if p.exists() or p.is_symlink()]
    if len(present) > 1: raise ValueError('Найдены оба opencode.json и opencode.jsonc: требуется сверка')
    config = present[0] if present else configs[0]
    if config.is_symlink(): raise ValueError('Конфиг является ссылкой: изменение отменено')
    old = config.read_text(encoding='utf-8') if present else '{}\n'
    if present and not config.is_file(): raise ValueError('Конфиг не является файлом')
    instruction = str(SOURCE / 'role-orchestration.md')
    skills = str(SOURCE / 'skills')
    result = with_entry(with_entry(old, 'instructions', instruction), 'skills', skills)
    parse(result)
    print('Конфиг:', config)
    print('Инструкции:', instruction)
    print('Скиллы:', skills)
    legacy = Path.home() / '.engineering-learning'
    if legacy.exists() and not legacy.is_symlink(): print('Найден прежний учебный журнал:', legacy)
    # The old installer used symlinks; do not load its rules twice or override skill IDs.
    old_paths = ['AGENTS.md', 'references/policy-details.md', 'skills/coach', 'skills/copilot', 'skills/executor']
    managed = []
    for relative in old_paths:
        dest = target / relative
        if dest.is_symlink():
            link = Path(os.readlink(dest))
            candidates = [SOURCE / relative, ROOT / 'omp/agent' / relative]
            if link.is_absolute() and any(link == c for c in candidates): managed.append(dest)
    if managed:
        print('Прежние ссылки фреймворка будут отключены:', *(str(p) for p in managed), sep='\n  ')
    if args.dry_run:
        print('Dry-run: ничего не записано.')
        return
    if result != old or not present:
        target.mkdir(parents=True, exist_ok=True)
        if present and config.read_text(encoding='utf-8') != old: raise ValueError('Конфиг изменился во время установки')
        if not present and (config.exists() or config.is_symlink()): raise ValueError('Конфиг появился во время установки')
        fd, temp = tempfile.mkstemp(prefix='.opencode-', dir=target)
        try:
            with os.fdopen(fd, 'w', encoding='utf-8') as f: f.write(result)
            if present: os.chmod(temp, config.stat().st_mode & 0o777)
            os.replace(temp, config)
        finally:
            if os.path.exists(temp): os.unlink(temp)
    for dest in managed:
        if dest.is_symlink() and Path(os.readlink(dest)) in [SOURCE / str(dest.relative_to(target)), ROOT / 'omp/agent' / str(dest.relative_to(target))]:
            dest.unlink()
    from verify import verify
    print('Проверка установленной конфигурации:')
    if verify(): raise ValueError('Проверка не пройдена; см. ошибки выше')
    print('Готово. Начните новую сессию OpenCode V2.')


if __name__ == '__main__':
    try: main()
    except (OSError, ValueError, json.JSONDecodeError) as exc:
        print('Ошибка установки:', exc, file=__import__('sys').stderr)
        raise SystemExit(1)
