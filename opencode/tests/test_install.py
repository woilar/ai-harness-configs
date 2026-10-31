import os
from pathlib import Path
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[2]
INSTALL = ROOT / 'opencode/scripts/install.sh'
SOURCE = ROOT / 'omp/agent'
PATHS = ('AGENTS.md', 'references/policy-details.md', 'skills/copilot',
         'skills/coach', 'skills/executor')


class InstallTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.home = Path(self.tmp.name)
        self.env = dict(os.environ, HOME=str(self.home), XDG_CONFIG_HOME=str(self.home / 'config'))
        self.target = self.home / 'config/opencode'

    def run_install(self, *args):
        return subprocess.run(['bash', str(INSTALL), *args], env=self.env, capture_output=True, text=True)

    def test_install_dry_run_and_repeat(self):
        self.assertEqual(self.run_install('--dry-run').returncode, 0)
        self.assertFalse(self.target.exists())
        self.assertEqual(self.run_install().returncode, 0)
        for path in PATHS:
            self.assertTrue((self.target / path).is_symlink(), path)
            self.assertEqual((self.target / path).resolve(), (SOURCE / path).resolve())
        self.assertEqual(self.run_install().returncode, 0)

    def test_conflict_stops_before_writes(self):
        self.target.mkdir(parents=True)
        (self.target / 'AGENTS.md').write_text('personal')
        self.assertNotEqual(self.run_install().returncode, 0)
        self.assertEqual((self.target / 'AGENTS.md').read_text(), 'personal')
        self.assertFalse((self.target / 'skills').exists())

    def test_default_and_invalid_xdg(self):
        self.env.pop('XDG_CONFIG_HOME')
        self.assertEqual(self.run_install().returncode, 0)
        self.assertTrue((self.home / '.config/opencode/AGENTS.md').is_symlink())
        self.env['XDG_CONFIG_HOME'] = 'relative'
        self.assertNotEqual(self.run_install().returncode, 0)


if __name__ == '__main__':
    unittest.main()
