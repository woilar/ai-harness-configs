"""Read-only tool discovery and installer integration with an isolated PATH."""
from pathlib import Path
import os
import shutil
import subprocess
import sys
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[2]
BASH = shutil.which('bash')


class ToolDiagnosticsTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.work = Path(self.tmp.name)
        self.bin = self.work / 'bin with spaces'
        self.bin.mkdir()
        self.marker = self.work / 'unexpected-execution'
        self.env = dict(os.environ, PATH=str(self.bin), PYTHONDONTWRITEBYTECODE='1')

    def stub(self, name):
        path = self.bin / name
        path.write_text('#!' + BASH + '\nprintf executed >> "' + str(self.marker) + '"\nexit 99\n')
        path.chmod(0o755)
        return path

    def diagnose(self):
        return subprocess.run([BASH, str(ROOT / 'shared/scripts/check-tools.sh')],
                              env=self.env, capture_output=True, text=True, timeout=10)

    def test_missing_tools_only_print_install_commands(self):
        self.stub('brew')
        self.stub('curl')
        result = self.diagnose()
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertIn('bd: не найден', result.stdout)
        self.assertIn('plannotator: не найден', result.stdout)
        self.assertIn('brew install beads', result.stdout)
        self.assertIn('plannotator.ai/install.sh', result.stdout)
        self.assertNotIn('dolt', result.stdout.lower())
        self.assertFalse(self.marker.exists())

    def test_found_tools_are_reported_without_execution(self):
        bd = self.stub('bd')
        plannotator = self.stub('plannotator')
        result = self.diagnose()
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertIn(str(bd), result.stdout)
        self.assertIn(str(plannotator), result.stdout)
        self.assertNotIn('brew install', result.stdout)
        self.assertFalse(self.marker.exists())

    def test_mixed_and_non_executable_tools(self):
        self.stub('bd')
        (self.bin / 'plannotator').write_text('not executable')
        result = self.diagnose()
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertIn('bd: найден', result.stdout)
        self.assertIn('plannotator: не найден', result.stdout)
        self.assertFalse(self.marker.exists())

    def test_all_installers_report_missing_tools_on_dry_run_and_repeat(self):
        for name in ('bash', 'dirname', 'mkdir', 'ln', 'readlink', 'rm'):
            (self.bin / name).symlink_to(shutil.which(name))
        (self.bin / 'python3').symlink_to(sys.executable)
        for harness in ('omp', 'codex', 'opencode'):
            with self.subTest(harness=harness):
                home = self.work / harness
                env = dict(self.env, HOME=str(home), CODEX_HOME=str(home / '.codex'),
                           XDG_CONFIG_HOME=str(home / 'config'))
                command = [BASH, str(ROOT / harness / 'scripts/install.sh')]
                for args in (['--dry-run'], [], []):
                    result = subprocess.run(command + args, env=env, capture_output=True,
                                            text=True, timeout=20)
                    self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
                    self.assertIn('bd: не найден', result.stdout)
                    self.assertIn('plannotator: не найден', result.stdout)
                    if args:
                        self.assertFalse(home.exists())
                self.assertTrue(home.exists())


if __name__ == '__main__':
    unittest.main(verbosity=2)
