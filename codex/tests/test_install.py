#!/usr/bin/env python3
"""Exercise installation in disposable directories, not the user's Codex home."""
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
        self.root = Path(self.tmp.name).resolve()
        self.repo = self.root / 'repository with spaces'
        shutil.copytree(REPO, self.repo, ignore=shutil.ignore_patterns('.git', '__pycache__'))
        self.script = self.repo / 'codex/scripts/install.sh'
        self.codex = self.root / 'my codex'
        self.skills = self.root / 'my skills'
        self.links = {
            self.codex / 'AGENTS.md': self.repo / 'shared/agent/role-orchestration.md',
            self.codex / 'references/policy-details.md': self.repo / 'shared/agent/references/policy-details.md',
            **{self.skills / role: self.repo / 'shared/agent/skills' / role
               for role in ('coach', 'copilot', 'executor')},
        }

    def install(self, *args, env=None):
        return subprocess.run(['bash', str(self.script), '--codex-home', str(self.codex),
                               '--skills-dir', str(self.skills), *args],
                              cwd=self.root, text=True, capture_output=True, env=env)

    def test_dry_run_and_repeated_install(self):
        r = self.install('--dry-run')
        self.assertEqual(r.returncode, 0, r.stderr)
        self.assertFalse(self.codex.exists())
        self.assertFalse(self.skills.exists())
        r = self.install()
        self.assertEqual(r.returncode, 0, r.stderr)
        for dest, src in self.links.items():
            self.assertTrue(dest.is_symlink(), dest)
            self.assertEqual(os.readlink(dest), str(src))
            self.assertTrue(dest.exists(), dest)
        mtimes = {dest: dest.lstat().st_mtime_ns for dest in self.links}
        r = self.install()
        self.assertEqual(r.returncode, 0, r.stderr)
        self.assertIn('Новых ссылок: 0', r.stdout)
        self.assertEqual(mtimes, {dest: dest.lstat().st_mtime_ns for dest in self.links})
        self.assertIn('name: coach', (self.skills / 'coach/SKILL.md').read_text())
        self.assertIn('План и синхронизация', (self.codex / 'references/policy-details.md').read_text())

    def test_existing_agents_preserved_no_partial_install(self):
        self.codex.mkdir()
        existing = self.codex / 'AGENTS.md'
        existing.write_text('My existing instructions\n')
        result = self.install()
        self.assertNotEqual(result.returncode, 0)
        self.assertEqual(existing.read_text(), 'My existing instructions\n')
        self.assertFalse(self.skills.exists())

    def test_override_prevents_install(self):
        self.codex.mkdir()
        override = self.codex / 'AGENTS.override.md'
        override.write_text('Other instructions\n')
        result = self.install()
        self.assertNotEqual(result.returncode, 0)
        self.assertIn('AGENTS.override.md', result.stderr)
        self.assertEqual(override.read_text(), 'Other instructions\n')
        self.assertFalse(self.skills.exists())
        self.assertFalse((self.codex / 'AGENTS.md').exists())

    def test_existing_skill_and_dangling_link_are_conflicts(self):
        self.skills.mkdir()
        (self.skills / 'executor').mkdir()
        (self.skills / 'coach').symlink_to(self.root / 'missing')
        result = self.install()
        self.assertNotEqual(result.returncode, 0)
        self.assertIn('executor', result.stderr)
        self.assertIn('coach', result.stderr)
        self.assertFalse(self.codex.exists())
        self.assertTrue((self.skills / 'executor').is_dir())

    def test_missing_source_and_bad_parents_do_not_change_anything(self):
        (self.repo / 'shared/agent/skills/executor/SKILL.md').unlink()
        r = self.install()
        self.assertNotEqual(r.returncode, 0)
        self.assertFalse(self.codex.exists())
        self.assertFalse(self.skills.exists())
        (self.repo / 'shared/agent/skills/executor/SKILL.md').write_text('replacement')
        self.skills.write_text('not a directory')
        r = self.install()
        self.assertNotEqual(r.returncode, 0)
        self.assertEqual(self.skills.read_text(), 'not a directory')
        self.assertFalse(self.codex.exists())

    def test_failure_rolls_back_only_links_created_in_this_run(self):
        self.codex.mkdir()
        existing = self.codex / 'AGENTS.md'
        existing.symlink_to(self.links[existing])
        bin_dir = self.root / 'bin'
        bin_dir.mkdir()
        link_command = shutil.which('ln')
        fake = bin_dir / 'ln'
        fake.write_text('#!/bin/sh\ncase "$*" in *executor*) exit 1;; esac\nexec "' + link_command + '" "$@"\n')
        fake.chmod(0o755)
        env = dict(os.environ, PATH=str(bin_dir) + os.pathsep + os.environ['PATH'])
        result = self.install(env=env)
        self.assertNotEqual(result.returncode, 0)
        self.assertTrue(existing.is_symlink())
        for dest in self.links:
            if dest != existing:
                self.assertFalse(dest.is_symlink(), dest)

    def test_codex_home_env_and_absolute_paths(self):
        env = dict(os.environ, CODEX_HOME=str(self.codex))
        result = subprocess.run(['bash', str(self.script), '--skills-dir', str(self.skills)],
                                cwd=self.root, text=True, capture_output=True, env=env)
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertTrue((self.codex / 'AGENTS.md').is_symlink())
        r = subprocess.run(['bash', str(self.script), '--codex-home', 'relative', '--dry-run'],
                           cwd=self.root, text=True, capture_output=True)
        self.assertNotEqual(r.returncode, 0)
        for invalid in ('/', '///'):
            for flag in ('--codex-home', '--skills-dir'):
                r = subprocess.run(['bash', str(self.script), flag, invalid, '--dry-run'],
                                   cwd=self.root, text=True, capture_output=True)
                self.assertNotEqual(r.returncode, 0, (flag, invalid))


if __name__ == '__main__':
    unittest.main(verbosity=2)
