"""Workflow invariants, no Android runtime or GitHub mutations required."""
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import patch
from contextlib import ExitStack

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from source_state import ROOT, sha256, source_state
from release import require_check, successful_ci, prepare, validate_bundle, verify_remote
import release as release_script

ROOT.joinpath('.tools').mkdir(exist_ok=True)


class WorkflowTests(unittest.TestCase):
    def test_draft_resume_only_uploads_missing_assets_and_refuses_public(self):
        with tempfile.TemporaryDirectory(dir=ROOT / '.tools') as temp:
            root = Path(temp)
            folder = root / 'dist/9.9.9'
            folder.mkdir(parents=True)
            (folder / 'one.txt').write_text('one')
            (folder / 'two.txt').write_text('two')
            draft = dict(tag_name='v9.9.9', draft=True, assets=[dict(name='one.txt')])
            with ExitStack() as stack:
                for name, value in [('ROOT', root)]:
                    stack.enter_context(patch.object(release_script, name, value))
                stack.enter_context(patch.object(sys, 'argv', ['release.py', 'draft']))
                stack.enter_context(patch('release.os.chdir'))
                stack.enter_context(patch('release.git', return_value=b'https://github.com/docking1177-dot/FloatingFocus.git'))
                stack.enter_context(patch('release.source_state', return_value={'dirty': False}))
                stack.enter_context(patch('release.version', return_value=('9.9.9', 99)))
                stack.enter_context(patch('release.validate_bundle'))
                stack.enter_context(patch('release.remote_gate', return_value={'html_url': 'verified CI'}))
                find = stack.enter_context(patch('release.find_remote_release', return_value=draft))
                client = stack.enter_context(patch('release.gh'))
                stack.enter_context(patch('release.verify_remote'))
                release_script.main()
                client.assert_called_once_with('release', 'upload', 'v9.9.9', str(folder/'two.txt'), '--repo', release_script.REPO)
                client.reset_mock()
                find.return_value = dict(draft, draft=False)
                with self.assertRaises(RuntimeError):
                    release_script.main()
                client.assert_not_called()

    def test_prepare_resume_tamper_and_download_verification(self):
        from types import SimpleNamespace
        with tempfile.TemporaryDirectory(dir=ROOT / '.tools') as temp:
            root = Path(temp)
            (root / '.tools').mkdir()
            (root / '.tools/debug.keystore').write_bytes(b'fake key; signature verification mocked')
            (root / 'docs/releases').mkdir(parents=True)
            (root / 'docs/releases/9.9.9.md').write_text('test release notes')
            apk = root / 'checked.apk'
            apk.write_bytes(b'test apk')
            state = dict(commit='abc', fingerprint='xyz', dirty=False)
            debug = dict(schemaVersion=1, status='passed', variant='Debug', mode='Full', source=state,
                         sourceAfter=state, localBuildInputs={}, apk=dict(path=str(apk), sha256=sha256(apk)), tests=75)
            release = dict(debug, variant='Release', tests=69)
            device = dict(status='passed', source=state, apkSha256=sha256(apk), passedSuites=['smoke','edges','inline'])
            for name, data in [('debug', debug), ('release', release), ('device', device)]:
                (root / (name + '.json')).write_text(json.dumps(data))
            args = SimpleNamespace(debug_check=root/'debug.json', release_check=root/'release.json', device_report=root/'device.json')
            folder = root / 'dist/9.9.9'
            with patch('release.ROOT', root), patch('release.build_inputs', return_value={}), patch('release.inspect_apk'):
                prepare(args, state, '9.9.9', 99, folder)
                before = {p.name: p.read_bytes() for p in folder.iterdir()}
                prepare(args, state, '9.9.9', 99, folder)
                self.assertEqual(before, {p.name: p.read_bytes() for p in folder.iterdir()})
                remote = dict(assets=[dict(id=i, name=name) for i, name in enumerate(before)], html_url='test')
                values = list(before.values())
                with patch('release.gh', side_effect=lambda *a, **kw: values[int(a[-1].split('/')[-1])]):
                    verify_remote(folder, 'v9.9.9', remote)
                with patch('release.gh', return_value=b'corrupt download'), self.assertRaises(RuntimeError):
                    verify_remote(folder, 'v9.9.9', remote)
                (folder/'RELEASE_NOTES.md').write_text('tampered')
                with self.assertRaises(RuntimeError):
                    validate_bundle(folder, state)

    def test_source_tracks_content_not_only_commit_or_status(self):
        with tempfile.TemporaryDirectory(dir=ROOT / '.tools') as temp:
            root = Path(temp)
            def git(*args):
                return subprocess.check_output(['git', '-C', temp, *args], stderr=subprocess.DEVNULL)
            git('init')
            (root / '.gitignore').write_text('build/\n')
            (root / 'source.kt').write_text('first')
            git('add', '.')
            git('-c', 'user.name=Test', '-c', 'user.email=test@example.invalid', 'commit', '-m', 'fixture')
            clean = source_state(root)
            self.assertFalse(clean['dirty'])
            (root / 'source.kt').write_text('second')
            dirty = source_state(root)
            self.assertEqual(clean['commit'], dirty['commit'])
            self.assertNotEqual(clean['fingerprint'], dirty['fingerprint'])
            self.assertTrue(dirty['dirty'])
            git('add', 'source.kt')
            self.assertEqual(dirty['fingerprint'], source_state(root)['fingerprint'])
            (root / 'new.kt').write_text('new')
            extra = source_state(root)
            self.assertNotEqual(dirty['fingerprint'], extra['fingerprint'])
            (root / 'asset').mkdir()
            (root / 'asset/user.jpg').write_bytes(b'untouched')
            (root / 'build').mkdir()
            (root / 'build/output.apk').write_bytes(b'ignored')
            self.assertEqual(extra['fingerprint'], source_state(root)['fingerprint'])
            (root / 'source.kt').unlink()
            self.assertNotEqual(extra['fingerprint'], source_state(root)['fingerprint'])

    def test_unit_offline_arguments_all_modes(self):
        if os.name != 'nt':
            self.skipTest('PowerShell Windows entrypoint')
        with tempfile.TemporaryDirectory(dir=ROOT / '.tools') as temp:
            root = Path(temp)
            for bridge in (False, True):
                if bridge:
                    (root / '.tools').mkdir()
                    (root / '.tools/maven-bridge.init.gradle').write_text('// fixture')
                for mode in ('Unit', 'Full', 'Emulator'):
                    for variant in ('Debug', 'Release'):
                        for offline in (False, True):
                            script = f". '{ROOT / 'scripts/check-common.ps1'}'; @(Get-CheckTasks -Mode {mode} -Variant {variant} -Offline:${str(offline).lower()} -ProjectRoot '{root}') | ConvertTo-Json -Compress"
                            raw = subprocess.check_output(['powershell', '-NoProfile', '-Command', script])
                            tasks = json.loads(raw.decode('utf-8-sig'))
                            tasks = tasks if isinstance(tasks, list) else [tasks]
                            expected = [f'test{variant}UnitTest'] if mode == 'Unit' else [f'assemble{variant}', f'test{variant}UnitTest', f'lint{variant}']
                            if offline:
                                expected.append('--offline')
                                if bridge:
                                    expected += ['-I', str(root / '.tools/maven-bridge.init.gradle')]
                            self.assertEqual(expected, tasks)

    def test_ci_requires_exact_sha_latest_main_run(self):
        base = dict(id=1, head_sha='abc', head_branch='main', event='push', path='.github/workflows/android.yml',
                    status='completed', conclusion='success', html_url='https://example.invalid/run')
        self.assertEqual(base, successful_ci([base], 'abc'))
        for updates in ({'head_sha': 'other'}, {'event': 'pull_request'}, {'head_branch': 'other'},
                        {'path': 'other.yml'}, {'status': 'in_progress'}, {'conclusion': 'failure'}):
            with self.assertRaises(RuntimeError):
                successful_ci([dict(base, **updates)], 'abc')
        with self.assertRaises(RuntimeError):
            successful_ci([base, dict(base, id=2, conclusion='failure')], 'abc')

    def test_release_rejects_stale_source_partial_checks_and_changed_apk(self):
        with tempfile.TemporaryDirectory(dir=ROOT / '.tools') as temp:
            apk = Path(temp) / 'app.apk'
            apk.write_bytes(b'checked')
            state = dict(commit='abc', fingerprint='xyz', dirty=False)
            report = dict(schemaVersion=1, status='passed', variant='Debug', mode='Full', source=state,
                          sourceAfter=state, localBuildInputs={}, apk=dict(path=str(apk), sha256=sha256(apk)))
            with patch('release.build_inputs', return_value={}):
                require_check(report, state, 'Debug')
                for updates in ({'mode': 'Unit'}, {'variant': 'Release'}, {'status': 'failed'},
                                {'source': dict(state, fingerprint='old')}, {'localBuildInputs': {'changed': True}}):
                    with self.assertRaises(RuntimeError):
                        require_check(dict(report, **updates), state, 'Debug')
                apk.write_bytes(b'changed')
                with self.assertRaises(RuntimeError):
                    require_check(report, state, 'Debug')


if __name__ == '__main__':
    unittest.main()
