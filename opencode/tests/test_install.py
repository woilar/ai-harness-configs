import json
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[2]
INSTALL = ROOT / 'opencode/scripts/install.sh'
SOURCE = ROOT / 'shared/agent'

class InstallTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.home = Path(self.tmp.name)
        self.env = dict(os.environ, HOME=str(self.home), XDG_CONFIG_HOME=str(self.home / 'config'))
        self.target = self.home / 'config/opencode'

    def run_install(self, *args):
        return subprocess.run(['bash', str(INSTALL), *args], env=self.env, capture_output=True, text=True)

    def test_new_and_repeat(self):
        self.assertEqual(self.run_install('--dry-run').returncode, 0)
        self.assertFalse(self.target.exists())
        self.assertEqual(self.run_install().returncode, 0)
        config = self.target / 'opencode.json'
        value = json.loads(config.read_text())
        self.assertEqual(value['instructions'], [str(SOURCE / 'role-orchestration.md')])
        self.assertEqual(value['skills'], [str(SOURCE / 'skills')])
        original = config.read_text()
        self.assertEqual(self.run_install().returncode, 0)
        self.assertEqual(config.read_text(), original)

    def test_existing_jsonc_and_agents_are_preserved(self):
        self.target.mkdir(parents=True)
        cfg = self.target / 'opencode.jsonc'
        cfg.write_text('{\n // comment\n "model": "some/model",\n "instructions": ["~/other.md",],\n "skills": [],\n}\n')
        (self.target / 'AGENTS.md').write_text('personal')
        r = self.run_install()
        self.assertEqual(r.returncode, 0, r.stderr)
        self.assertIn('// comment', cfg.read_text())
        self.assertIn('"model": "some/model"', cfg.read_text())
        self.assertIn('"~/other.md",', cfg.read_text())
        self.assertIn(str(SOURCE / 'role-orchestration.md'), cfg.read_text())
        self.assertEqual((self.target / 'AGENTS.md').read_text(), 'personal')
        self.assertEqual(self.run_install().returncode, 0)

    def test_conflicts_preserved(self):
        self.target.mkdir(parents=True)
        cfg = self.target / 'opencode.json'
        cfg.write_text('{"skills": {"paths": []}}')
        self.assertNotEqual(self.run_install().returncode, 0)
        self.assertEqual(cfg.read_text(), '{"skills": {"paths": []}}')
        cfg.write_text('{"skills": [], "skills": []}')
        self.assertNotEqual(self.run_install().returncode, 0)
        cfg.write_text('{}')
        (self.target / 'opencode.jsonc').write_text('{}')
        self.assertNotEqual(self.run_install().returncode, 0)
        self.assertEqual(cfg.read_text(), '{}')

    def test_old_managed_links_removed_after_registration(self):
        self.target.mkdir(parents=True)
        for name in ('AGENTS.md','references/policy-details.md','skills/coach','skills/copilot','skills/executor'):
            p = self.target / name
            p.parent.mkdir(parents=True, exist_ok=True)
            p.symlink_to(SOURCE / name)
        self.assertEqual(self.run_install('--dry-run').returncode, 0)
        self.assertTrue((self.target / 'AGENTS.md').is_symlink())
        r = self.run_install()
        self.assertEqual(r.returncode, 0, r.stderr)
        self.assertFalse((self.target / 'AGENTS.md').is_symlink())
        self.assertTrue((self.target / 'opencode.json').exists())

    def test_without_omp_directory_and_legacy_data(self):
        isolated = self.home / 'repository'
        shutil.copytree(ROOT / 'shared', isolated / 'shared')
        shutil.copytree(ROOT / 'opencode', isolated / 'opencode')
        old = self.home / '.engineering-learning/gaps'
        old.mkdir(parents=True)
        (old / 'card.md').write_text('existing gap')
        r = subprocess.run(['bash', str(isolated / 'opencode/scripts/install.sh')], env=self.env, capture_output=True, text=True)
        self.assertEqual(r.returncode, 0, r.stderr)
        self.assertIn('прежний учебный журнал', r.stdout)
        self.assertEqual((old / 'card.md').read_text(), 'existing gap')
        self.assertIn(str(isolated / 'shared/agent/skills'), (self.target / 'opencode.json').read_text())

if __name__ == '__main__': unittest.main()
