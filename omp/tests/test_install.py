#!/usr/bin/env python3
"""Integration checks using isolated directories; the real agent config is untouched."""
from pathlib import Path
import os
import shutil
import subprocess
import tempfile
import unittest

REPOSITORY = Path(__file__).resolve().parents[2]
LINKS = ('AGENTS.md', 'references/policy-details.md', 'skills/copilot',
         'skills/coach', 'skills/executor')


class InstallTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory(prefix='omp-install-check-')
        self.addCleanup(self.tmp.cleanup)
        self.work = Path(self.tmp.name).resolve()
        self.repo = self.work / 'repository with spaces'
        shutil.copytree(REPOSITORY, self.repo,
                        ignore=shutil.ignore_patterns('.git', '__pycache__'))
        self.script = self.repo / 'omp/scripts/install.sh'
        self.target = self.work / 'global settings with spaces' / 'agent'

    def run_install(self, *args, env=None):
        return subprocess.run(['bash', str(self.script), '--target', str(self.target), *args],
                              cwd=self.work, env=env, text=True, capture_output=True)

    def assert_installed(self):
        for relative in LINKS:
            destination = self.target / relative
            source = self.repo / 'omp/agent' / relative
            self.assertTrue(destination.is_symlink(), relative)
            self.assertEqual(os.readlink(destination), str(source))
        self.assertIn('name: copilot', (self.target / 'skills/copilot/SKILL.md').read_text())
        self.assertIn('name: coach', (self.target / 'skills/coach/SKILL.md').read_text())
        self.assertIn('name: executor', (self.target / 'skills/executor/SKILL.md').read_text())
        self.assertIn('# Договорённости:', (self.target / 'references/policy-details.md').read_text())
        self.assertTrue((self.target / 'skills/coach/references/scientific-basis.md').is_file())

    def test_fresh_install_and_repeat(self):
        self.target.mkdir(parents=True)
        unrelated = self.target / 'config.yml'
        unrelated.write_text('existing: keep\n')
        result = self.run_install()
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assert_installed()
        timestamps = [os.lstat(self.target / p).st_mtime_ns for p in LINKS]
        repeated = self.run_install()
        self.assertEqual(repeated.returncode, 0, repeated.stderr)
        self.assertIn('Новых ссылок: 0', repeated.stdout)
        self.assertEqual(timestamps, [os.lstat(self.target / p).st_mtime_ns for p in LINKS])
        self.assertEqual(unrelated.read_text(), 'existing: keep\n')

    def test_dry_run_does_not_create_target(self):
        result = self.run_install('--dry-run')
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertIn('ничего не записано', result.stdout)
        self.assertFalse(self.target.exists())

    def test_late_destination_conflict_has_no_partial_install(self):
        conflict = self.target / 'skills/executor'
        conflict.mkdir(parents=True)
        private_file = conflict / 'private.md'
        private_file.write_text('keep my skill\n')
        result = self.run_install()
        self.assertNotEqual(result.returncode, 0)
        self.assertIn(str(conflict), result.stderr)
        self.assertFalse((self.target / 'AGENTS.md').exists())
        self.assertFalse((self.target / 'references').exists())
        self.assertEqual(private_file.read_text(), 'keep my skill\n')

    def test_existing_agents_is_preserved(self):
        self.target.mkdir(parents=True)
        existing = self.target / 'AGENTS.md'
        existing.write_text('personal instructions\n')
        result = self.run_install()
        self.assertNotEqual(result.returncode, 0)
        self.assertFalse(existing.is_symlink())
        self.assertEqual(existing.read_text(), 'personal instructions\n')
        self.assertFalse((self.target / 'skills').exists())

    def test_dangling_link_is_a_conflict(self):
        self.target.mkdir(parents=True)
        link = self.target / 'AGENTS.md'
        link.symlink_to(self.work / 'missing-original.md')
        result = self.run_install()
        self.assertNotEqual(result.returncode, 0)
        self.assertEqual(os.readlink(link), str(self.work / 'missing-original.md'))

    def test_parent_file_is_rejected_before_install(self):
        self.target.mkdir(parents=True)
        (self.target / 'skills').write_text('not a directory\n')
        result = self.run_install()
        self.assertNotEqual(result.returncode, 0)
        self.assertFalse((self.target / 'AGENTS.md').exists())
        self.assertEqual((self.target / 'skills').read_text(), 'not a directory\n')

    def test_missing_reference_is_rejected(self):
        reference = self.repo / 'omp/agent/skills/coach/references/scientific-basis.md'
        reference.unlink()
        result = self.run_install()
        self.assertNotEqual(result.returncode, 0)
        self.assertFalse(self.target.exists())

    def test_incompatible_flags_are_rejected(self):
        result = self.run_install('--profile', 'java')
        self.assertNotEqual(result.returncode, 0)
        self.assertFalse(self.target.exists())

    def test_profile_path_and_invalid_name(self):
        for name, suffix in [('java', '.omp/profiles/java/agent'), ('default', '.omp/agent')]:
            result = subprocess.run(['bash', str(self.script), '--profile', name, '--dry-run'],
                                    text=True, capture_output=True)
            self.assertIn(result.returncode, (0, 1), result.stderr)
            self.assertIn(str(Path.home() / suffix), result.stdout + result.stderr)
            if result.returncode == 1:
                self.assertIn('Установка не начата', result.stderr)
        result = subprocess.run(['bash', str(self.script), '--profile', '../escape'],
                                text=True, capture_output=True)
        self.assertNotEqual(result.returncode, 0)

    def test_runtime_failure_rolls_back_only_new_links(self):
        self.target.mkdir(parents=True)
        old_link = self.target / 'AGENTS.md'
        old_link.symlink_to(self.repo / 'omp/agent/AGENTS.md')
        stubs = self.work / 'command-stubs'
        stubs.mkdir()
        wrapper = stubs / 'ln'
        wrapper.write_text('#!/usr/bin/env bash\ncase "$3" in */skills/copilot) exit 9;; esac\nexec /bin/ln "$@"\n')
        wrapper.chmod(0o755)
        env = dict(os.environ)
        env['PATH'] = str(stubs) + os.pathsep + env.get('PATH', '')
        result = self.run_install(env=env)
        self.assertNotEqual(result.returncode, 0)
        self.assertTrue(old_link.is_symlink())
        self.assertFalse((self.target / 'references/policy-details.md').is_symlink())
        self.assertFalse((self.target / 'skills/copilot').exists())


if __name__ == '__main__':
    unittest.main(verbosity=2)
