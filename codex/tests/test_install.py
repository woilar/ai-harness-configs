#!/usr/bin/env python3
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest

REPO = Path(__file__).resolve().parents[2]


class InstallTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory(prefix='codex-install-')
        self.addCleanup(self.tmp.cleanup)
        self.work = Path(self.tmp.name).resolve()
        self.repo = self.work / 'repository with spaces'
        shutil.copytree(REPO, self.repo, ignore=shutil.ignore_patterns('.git', '__pycache__'))
        self.script = self.repo / 'codex/scripts/install.sh'
        self.codex = self.work / 'codex settings'
        self.skills = self.work / 'skills settings'
        self.agent = self.codex / 'AGENTS.md'
        self.router = self.repo / 'shared/agent/role-orchestration.md'
        self.policy = self.repo / 'shared/agent/references/policy-details.md'
        self.links = {self.codex / 'references/policy-details.md': self.policy,
                      **{self.skills / role: self.repo / 'shared/agent/skills' / role
                         for role in ('coach', 'copilot', 'executor')}}

    def install(self, *args, env=None):
        return subprocess.run(['bash', str(self.script), '--codex-home', str(self.codex),
                               '--skills-dir', str(self.skills), *args], cwd=self.work,
                              text=True, capture_output=True, env=env)

    def test_dry_run_install_repeat_and_links(self):
        self.assertEqual(self.install('--dry-run').returncode, 0)
        self.assertFalse(self.codex.exists())
        self.assertFalse(self.skills.exists())
        result = self.install()
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertFalse(self.agent.is_symlink())
        self.assertIn(self.router.read_text().strip(), self.agent.read_text())
        self.assertIn(str(self.policy), self.agent.read_text())
        for dest, source in self.links.items():
            self.assertTrue(dest.is_symlink())
            self.assertEqual(os.readlink(dest), str(source))
        modified = self.agent.stat().st_mtime_ns
        result = self.install()
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertIn('уже актуален', result.stdout)
        self.assertEqual(self.agent.stat().st_mtime_ns, modified)
        self.assertEqual(list(self.codex.glob('*.ai-harness-backup-*')), [])

    def test_existing_instructions_update_and_backup(self):
        self.codex.mkdir()
        original = b'My existing rules\n'
        self.agent.write_bytes(original)
        result = self.install()
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertTrue(self.agent.read_bytes().startswith(original))
        backups = list(self.codex.glob('AGENTS.md.ai-harness-backup-*'))
        self.assertEqual(len(backups), 1)
        self.assertEqual(backups[0].read_bytes(), original)
        with self.agent.open('a') as stream:
            stream.write('\nMy later rules\n')
        previous = self.agent.read_bytes()
        self.router.write_text(self.router.read_text() + '\nUpdated role rule\n')
        result = self.install('--dry-run')
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(self.agent.read_bytes(), previous)
        result = self.install()
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertIn('Updated role rule', self.agent.read_text())
        self.assertTrue(self.agent.read_bytes().startswith(original))
        self.assertTrue(self.agent.read_text().endswith('\nMy later rules\n'))
        self.assertEqual(self.agent.read_text().count('AI-HARNESS-ROLES:BEGIN'), 1)
        self.assertIn(previous, [p.read_bytes() for p in self.codex.glob('AGENTS.md.ai-harness-backup-*')])

    def test_modified_and_bad_markers_stop_without_changes(self):
        self.assertEqual(self.install().returncode, 0)
        text = self.agent.read_text().replace('## Выбор роли', '## Ручная правка')
        self.agent.write_text(text)
        result = self.install()
        self.assertNotEqual(result.returncode, 0)
        self.assertIn('вручную', result.stderr)
        self.assertEqual(self.agent.read_text(), text)
        text = text.replace('<!-- AI-HARNESS-ROLES:END -->', '')
        self.agent.write_text(text)
        result = self.install()
        self.assertNotEqual(result.returncode, 0)
        self.assertEqual(self.agent.read_text(), text)
        text = text.replace('<!-- AI-HARNESS-ROLES:BEGIN -->', '')
        self.agent.write_text(text)
        self.assertNotEqual(self.install().returncode, 0)
        self.assertEqual(self.agent.read_text(), text)

    def test_override_active_and_empty_falls_back(self):
        self.codex.mkdir()
        override = self.codex / 'AGENTS.override.md'
        override.write_text('My override\n')
        self.agent.write_text('My AGENTS\n')
        result = self.install()
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertIn('AI-HARNESS-ROLES:BEGIN', override.read_text())
        self.assertEqual(self.agent.read_text(), 'My AGENTS\n')
        self.assertTrue(list(self.codex.glob('AGENTS.override.md.ai-harness-backup-*')))
        override.write_text('')
        result = self.install()
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertIn('AI-HARNESS-ROLES:BEGIN', self.agent.read_text())
        self.assertEqual(override.read_text(), '')

    def test_conflict_leaves_agents_and_other_files_untouched(self):
        self.codex.mkdir()
        self.agent.write_text('Personal rules')
        self.skills.mkdir()
        (self.skills / 'executor').mkdir()
        (self.skills / 'coach').symlink_to(self.work / 'missing')
        result = self.install()
        self.assertNotEqual(result.returncode, 0)
        self.assertEqual(self.agent.read_text(), 'Personal rules')
        self.assertFalse((self.codex / 'references').exists())

    def test_symlinked_agents_stops_without_overwriting_target(self):
        self.codex.mkdir()
        target = self.work / 'my-instructions'
        target.write_text('keep me')
        self.agent.symlink_to(target)
        result = self.install()
        self.assertNotEqual(result.returncode, 0)
        self.assertEqual(target.read_text(), 'keep me')
        self.assertFalse(self.skills.exists())

    def test_missing_source_and_bad_parents(self):
        (self.repo / 'shared/agent/skills/executor/SKILL.md').unlink()
        self.assertNotEqual(self.install().returncode, 0)
        self.assertFalse(self.codex.exists())
        (self.repo / 'shared/agent/skills/executor/SKILL.md').write_text('replacement')
        self.skills.write_text('not a directory')
        self.assertNotEqual(self.install().returncode, 0)
        self.assertFalse(self.codex.exists())

    def test_failure_during_symlinks_rolls_back_created_links(self):
        self.codex.mkdir()
        self.agent.write_text('My rules')
        launcher = """import importlib.util, sys
from unittest.mock import patch
spec = importlib.util.spec_from_file_location('installer', sys.argv[1])
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)
sys.argv = [sys.argv[1], '--codex-home', sys.argv[2], '--skills-dir', sys.argv[3]]
real = module.os.symlink
calls = [0]
def fail_third(src, dest):
    calls[0] += 1
    if calls[0] == 3: raise OSError('simulated failure')
    return real(src, dest)
with patch.object(module.os, 'symlink', fail_third):
    try: module.main()
    except OSError: sys.exit(1)
"""
        result = subprocess.run(['python3', '-c', launcher, str(self.repo / 'codex/scripts/install.py'),
                                 str(self.codex), str(self.skills)], text=True, capture_output=True)
        self.assertNotEqual(result.returncode, 0)
        self.assertEqual(self.agent.read_text(), 'My rules')
        self.assertFalse(any(path.is_symlink() for path in self.links))

    def test_codex_home_env_and_absolute_paths(self):
        env = dict(os.environ, CODEX_HOME=str(self.codex))
        result = subprocess.run(['bash', str(self.script), '--skills-dir', str(self.skills)],
                                cwd=self.work, text=True, capture_output=True, env=env)
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertTrue(self.agent.is_file())
        for invalid in ('relative', '/', '///'):
            for flag in ('--codex-home', '--skills-dir'):
                result = subprocess.run(['bash', str(self.script), flag, invalid, '--dry-run'],
                                        cwd=self.work, text=True, capture_output=True)
                self.assertNotEqual(result.returncode, 0)


if __name__ == '__main__':
    unittest.main(verbosity=2)
