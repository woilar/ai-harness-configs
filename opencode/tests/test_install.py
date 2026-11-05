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
        self.assertEqual(self.run_install('--verify').returncode, 0)
        self.assertEqual(config.read_text(), original)

    def test_verify_finds_missing_entries_without_changing_config(self):
        self.assertNotEqual(self.run_install('--verify').returncode, 0)
        self.assertEqual(self.run_install().returncode, 0)
        cfg = self.target / 'opencode.json'
        cfg.write_text('{"skills": []}')
        original = cfg.read_text()
        result = self.run_install('--verify')
        self.assertNotEqual(result.returncode, 0)
        self.assertIn('instructions', result.stderr)
        self.assertIn('skills', result.stderr)
        self.assertEqual(cfg.read_text(), original)

    def test_verify_reports_missing_skill_file(self):
        isolated = self.home / 'repository'
        shutil.copytree(ROOT / 'shared', isolated / 'shared')
        shutil.copytree(ROOT / 'opencode', isolated / 'opencode')
        script = isolated / 'opencode/scripts/install.sh'
        self.assertEqual(subprocess.run(['bash', str(script)], env=self.env, capture_output=True).returncode, 0)
        (isolated / 'shared/agent/skills/coach/SKILL.md').unlink()
        result = subprocess.run(['bash', str(script), '--verify'], env=self.env, capture_output=True, text=True)
        self.assertNotEqual(result.returncode, 0)
        self.assertIn('coach/SKILL.md', result.stderr)

    def test_missing_workflow_module(self):
        isolated = self.home / 'repository'
        shutil.copytree(ROOT / 'shared', isolated / 'shared')
        shutil.copytree(ROOT / 'opencode', isolated / 'opencode')
        script = isolated / 'opencode/scripts/install.sh'
        self.assertEqual(subprocess.run(['bash', str(script)], env=self.env, capture_output=True).returncode, 0)
        for name in ('planning.md', 'task-state.md', 'inbox.md', 'handoff.md'):
            with self.subTest(module=name):
                module = isolated / 'shared/agent/references' / name
                original = module.read_text()
                module.unlink()
                result = subprocess.run(['bash', str(script), '--verify'], env=self.env, capture_output=True, text=True)
                self.assertNotEqual(result.returncode, 0)
                self.assertIn(name, result.stderr)
                module.write_text(original)

    def test_verify_checks_router_manifest_contract(self):
        isolated = self.home / 'repository'
        shutil.copytree(ROOT / 'shared', isolated / 'shared')
        shutil.copytree(ROOT / 'opencode', isolated / 'opencode')
        script = isolated / 'opencode/scripts/install.sh'
        self.assertEqual(subprocess.run(['bash', str(script)], env=self.env, capture_output=True).returncode, 0)
        manifest = isolated / 'shared/agent/reference-files.txt'
        manifest.write_text(manifest.read_text().replace('references/inbox.md\n', ''))
        result = subprocess.run(['bash', str(script), '--verify'], env=self.env, capture_output=True, text=True)
        self.assertNotEqual(result.returncode, 0)
        self.assertIn('не зарегистрирован: references/inbox.md', result.stderr)

    def test_verify_prefers_v2_cli_and_reports_plannotator_without_running_it(self):
        commands = self.home / 'bin'
        commands.mkdir()
        marker = self.home / 'unexpected-execution'
        for name, body in {
            'opencode2': "printf '2.0.99\\n'\n",
            'opencode': 'printf executed >> "' + str(marker) + '"\nexit 88\n',
            'plannotator': 'printf executed >> "' + str(marker) + '"\nexit 99\n',
        }.items():
            executable = commands / name
            executable.write_text('#!/bin/sh\n' + body)
            executable.chmod(0o755)
        self.env['PATH'] = str(commands) + os.pathsep + self.env.get('PATH', '')
        for args in ([], ['--verify']):
            result = self.run_install(*args)
            self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
            self.assertIn('2.0.99', result.stdout)
            self.assertIn('plannotator CLI: найден', result.stdout)
            self.assertEqual(result.stdout.count('Проверка инструментов в PATH установщика:'), 1)
            self.assertFalse(marker.exists())

    def test_plannotator_plugin_is_distinct_from_missing_cli(self):
        commands = self.home / 'isolated-bin'
        commands.mkdir()
        for name in ('bash', 'python3', 'dirname'):
            (commands / name).symlink_to(shutil.which(name))
        self.env['PATH'] = str(commands)
        self.target.mkdir(parents=True)
        cfg = self.target / 'opencode.jsonc'
        content = '{\n // keep comment\n "plugins": [{"package": "@plannotator/opencode@latest", "options": {"agentTool": true}},],\n}\n'
        cfg.write_text(content)
        for args in (['--dry-run'], [], [], ['--verify']):
            result = self.run_install(*args)
            self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
            self.assertIn('Plannotator расширение OpenCode: указано в глобальном конфиге', result.stdout)
            self.assertIn('@plannotator/opencode@latest', result.stdout)
            self.assertIn('plannotator CLI: не найден в PATH', result.stdout)
            self.assertIn('не подтверждает загрузку', result.stdout)
            if args == ['--dry-run']:
                self.assertEqual(cfg.read_text(), content)
        self.assertIn(content.splitlines()[1], cfg.read_text())

    def test_plannotator_plugin_package_variants_and_scope(self):
        self.target.mkdir(parents=True)
        cfg = self.target / 'opencode.json'
        for package in ('@plannotator/opencode', '@plannotator/opencode@0.28.20'):
            with self.subTest(package=package):
                cfg.write_text(json.dumps({'plugins': [{'package': package}]}))
                result = self.run_install('--dry-run')
                self.assertEqual(result.returncode, 0, result.stderr)
                self.assertIn('расширение OpenCode: указано', result.stdout)
        cfg.write_text(json.dumps({'plugins': [{'package': '@plannotator/opencode-extra'}]}))
        result = self.run_install('--dry-run')
        self.assertIn('не найдено в проверенном глобальном конфиге', result.stdout)
        self.assertIn('Проектные настройки и локальные расширения не проверены', result.stdout)
        cfg.write_text(json.dumps({'plugins': ['unknown-shape']}))
        result = self.run_install('--dry-run')
        self.assertIn('неизвестный формат', result.stdout)
        cfg.write_text(json.dumps({'plugins': {'package': '@plannotator/opencode'}}))
        result = self.run_install('--dry-run')
        self.assertIn('не является списком', result.stdout)

    def test_missing_and_invalid_global_config_are_not_reported_as_missing_plugin(self):
        r = self.run_install('--dry-run')
        self.assertEqual(r.returncode, 0, r.stderr)
        self.assertIn('глобальный конфиг отсутствует; расширения не проверены', r.stdout)
        self.target.mkdir(parents=True)
        (self.target / 'opencode.json').write_text('{invalid')
        r = self.run_install('--verify')
        self.assertNotEqual(r.returncode, 0)
        self.assertIn('глобальная конфигурация не проверена', r.stdout)
        self.assertNotIn('не найдено в проверенном глобальном конфиге', r.stdout)

    def test_upgrade_removes_only_retired_owned_links(self):
        refs = self.target / 'references'
        refs.mkdir(parents=True)
        stale = refs / 'retired.md'
        stale.symlink_to(SOURCE / 'references/retired.md')
        foreign = refs / 'foreign.md'
        foreign.symlink_to(self.home / 'missing-user-source.md')
        personal = refs / 'personal.md'
        personal.write_text('keep')
        self.assertEqual(self.run_install('--dry-run').returncode, 0)
        self.assertTrue(stale.is_symlink())
        self.assertEqual(self.run_install().returncode, 0)
        self.assertFalse(stale.is_symlink())
        self.assertTrue(foreign.is_symlink())
        self.assertEqual(personal.read_text(), 'keep')

    def test_existing_jsonc_and_agents_are_preserved(self):
        self.target.mkdir(parents=True)
        cfg = self.target / 'opencode.jsonc'
        plugins = '"plugins": [{"package": "@plannotator/opencode@latest", "options": {"agentTool": true}}]'
        mcp = '"mcp": {"personal": {"type": "local", "command": ["custom-server"]}}'
        cfg.write_text('{\n // comment\n "model": "some/model",\n "instructions": ["~/other.md",],\n "skills": [],\n' + plugins + ',\n' + mcp + ',\n}\n')
        (self.target / 'AGENTS.md').write_text('personal')
        r = self.run_install()
        self.assertEqual(r.returncode, 0, r.stderr)
        self.assertIn('// comment', cfg.read_text())
        self.assertIn('"model": "some/model"', cfg.read_text())
        self.assertIn('"~/other.md",', cfg.read_text())
        self.assertIn(str(SOURCE / 'role-orchestration.md'), cfg.read_text())
        self.assertEqual((self.target / 'AGENTS.md').read_text(), 'personal')
        self.assertIn(plugins, cfg.read_text())
        self.assertIn(mcp, cfg.read_text())
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

