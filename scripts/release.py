"""Prepare/verify a fixed-signature Debug pre-release. Never creates commits or tags."""
import argparse
import json
import os
import re
import shutil
import subprocess
from pathlib import Path
from github_cli import api, gh
from source_state import ROOT, build_inputs, git, sha256, source_state

REPO = 'docking1177-dot/FloatingFocus'
SIGNER = '2119d9676b8cbf5a1efee1f01b74d0846cd5573d4f18f6faf64a73146158d11a'


def read_json(path):
    return json.loads(Path(path).read_text(encoding='utf-8-sig'))


def require_check(report, state, variant):
    if not (report.get('schemaVersion') == 1 and report.get('status') == 'passed'
            and report.get('variant') == variant and report.get('mode') in ('Full', 'Emulator')
            and report.get('source') == state and report.get('sourceAfter') == state
            and report.get('localBuildInputs') == build_inputs()):
        raise RuntimeError(f'{variant} check missing/stale/incomplete; run check.ps1 -Variant {variant}.')
    artifact = report.get('apk', {})
    if not artifact.get('path') or sha256(artifact['path']) != artifact.get('sha256'):
        raise RuntimeError(f'{variant} APK no longer matches check result.')


def successful_ci(runs, sha):
    eligible = [r for r in runs if r['head_sha'] == sha and r['event'] in ('push', 'workflow_dispatch')
                and r['head_branch'] == 'main' and r['path'] == '.github/workflows/android.yml']
    if not eligible:
        raise RuntimeError('No main CI for this SHA. Push/merge main or explicitly dispatch android.yml; tags do not rebuild.')
    latest = max(eligible, key=lambda r: (r['id'], r.get('run_attempt', 1)))
    if latest['status'] != 'completed' or latest['conclusion'] != 'success':
        raise RuntimeError(f"Exact-SHA CI is {latest['status']}/{latest['conclusion']}; resume after it succeeds: {latest['html_url']}")
    return latest


def version():
    source = (ROOT / 'app/build.gradle.kts').read_text(encoding='utf-8')
    return re.search(r'versionName = "([\d.]+)"', source)[1], int(re.search(r'versionCode = (\d+)', source)[1])


def inspect_apk(apk, name, code):
    build_tools = ROOT / '.tools/android-sdk/build-tools/35.0.0'
    env = os.environ.copy()
    if not env.get('JAVA_HOME'):
        fallback = Path('C:/Program Files/JetBrains/PyCharm 2025.3.3/jbr')
        if fallback.exists():
            env['JAVA_HOME'] = str(fallback)
    signed = subprocess.check_output([str(build_tools / 'apksigner.bat'), 'verify', '--print-certs', str(apk)], env=env).decode()
    if f'certificate SHA-256 digest: {SIGNER}' not in signed:
        raise RuntimeError('APK signer differs from existing phone builds. Do not replace the signing key.')
    badging = subprocess.check_output([str(build_tools / 'aapt.exe'), 'dump', 'badging', str(apk)]).decode('utf-8')
    if f"package: name='app.floatingfocus' versionCode='{code}' versionName='{name}'" not in badging:
        raise RuntimeError('APK package/version mismatch.')
    subprocess.run([str(build_tools / 'zipalign.exe'), '-c', '-P', '16', '4', str(apk)], check=True, capture_output=True)


def validate_bundle(folder, state):
    info = read_json(folder / 'BUILD_INFO.json')
    if info.get('schemaVersion') != 1:
        raise RuntimeError('Legacy/unrecognized bundle metadata; preserve it. Prepare a new version with this workflow.')
    if info.get('source') != state or state['dirty']:
        raise RuntimeError('Bundle does not match the current clean source; prepare again.')
    for name, digest in info['files'].items():
        if Path(name).name != name or sha256(folder / name) != digest:
            raise RuntimeError(f'Bundle checksum mismatch: {name}')
    expected = dict(info['files'], **{'BUILD_INFO.json': sha256(folder / 'BUILD_INFO.json')})
    lines = ''.join(f'{digest}  {name}\n' for name, digest in sorted(expected.items()))
    if (folder / 'SHA256SUMS.txt').read_text(encoding='utf-8') != lines:
        raise RuntimeError('Checksum manifest mismatch.')
    inspect_apk(folder / info['apk'], info['versionName'], info['versionCode'])
    return info


def prepare(args, state, name, code, folder):
    if state['dirty']:
        raise RuntimeError('Commit source changes before release checks/preparation; untracked asset/ is excluded.')
    if not (ROOT / '.tools/debug.keystore').is_file():
        raise RuntimeError('Fixed Debug key missing; release must never generate a replacement.')
    debug, release = read_json(args.debug_check), read_json(args.release_check)
    require_check(debug, state, 'Debug')
    require_check(release, state, 'Release')
    device = read_json(args.device_report)
    if device.get('status') != 'passed' or device.get('source') != state or device.get('apkSha256') != debug['apk']['sha256']:
        raise RuntimeError('Device regression does not match this source and Debug APK.')
    if not {'smoke', 'edges', 'inline'}.issubset(device.get('passedSuites', [])):
        raise RuntimeError('Device report must cover smoke, edges and inline extension.')
    notes = ROOT / f'docs/releases/{name}.md'
    if not notes.is_file():
        raise RuntimeError(f'Write reviewed release notes first: {notes}')
    apk = Path(debug['apk']['path'])
    inspect_apk(apk, name, code)
    if folder.exists():
        validate_bundle(folder, state)
        print(f'Existing verified bundle: {folder}')
        return
    folder.mkdir(parents=True)
    apk_name = f'FloatingFocus-{name}-debug.apk'
    shutil.copy2(apk, folder / apk_name)
    shutil.copy2(notes, folder / 'RELEASE_NOTES.md')
    info = {'schemaVersion': 1, 'source': state, 'versionName': name, 'versionCode': code,
            'apk': apk_name, 'buildType': 'debug', 'signerSha256': SIGNER,
            'validation': {'debugTests': debug['tests'], 'releaseTests': release['tests'],
                           'deviceSuites': device['passedSuites'], 'physicalDevice': 'not automatically verified'},
            'files': {n: sha256(folder / n) for n in (apk_name, 'RELEASE_NOTES.md')}}
    (folder / 'BUILD_INFO.json').write_text(json.dumps(info, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
    hashes = dict(info['files'], **{'BUILD_INFO.json': sha256(folder / 'BUILD_INFO.json')})
    (folder / 'SHA256SUMS.txt').write_text(''.join(f'{h}  {n}\n' for n, h in sorted(hashes.items())), encoding='utf-8')
    validate_bundle(folder, state)
    print(f'Prepared {folder}; no remote changes.')


def remote_gate(state, tag):
    sha = state['commit']
    refs = git('ls-remote', 'origin', f'refs/tags/{tag}', f'refs/tags/{tag}^{{}}').decode().splitlines()
    resolved = {line.split()[1]: line.split()[0] for line in refs}
    if resolved.get(f'refs/tags/{tag}^{{}}', resolved.get(f'refs/tags/{tag}')) != sha:
        raise RuntimeError('Remote tag must already resolve to this exact commit. No tag is created or moved automatically.')
    runs = api(f'repos/{REPO}/actions/workflows/android.yml/runs?head_sha={sha}&per_page=100')['workflow_runs']
    return successful_ci(runs, sha)


def verify_remote(folder, tag, remote):
    expected = {p.name: p for p in folder.iterdir() if p.is_file()}
    actual = {a['name']: a for a in remote['assets']}
    if set(actual) != set(expected):
        raise RuntimeError('Remote asset names differ from prepared bundle.')
    for name, path in expected.items():
        data = gh('api', '-H', 'Accept: application/octet-stream', f"repos/{REPO}/releases/assets/{actual[name]['id']}", binary=True)
        import hashlib
        if hashlib.sha256(data).hexdigest() != sha256(path):
            raise RuntimeError(f'Remote download checksum mismatch: {name}')
    print(f'All downloaded assets match: {remote["html_url"]}')


def find_remote_release(tag):
    # Listing with authentication includes drafts; the public tag endpoint is not
    # used to discover a newly created draft.
    pages = json.loads(gh('api', '--paginate', '--slurp', f'repos/{REPO}/releases?per_page=100'))
    return next((r for page in pages for r in page if r['tag_name'] == tag), None)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('action', choices=['prepare', 'draft', 'publish', 'verify'])
    parser.add_argument('--debug-check', type=Path)
    parser.add_argument('--release-check', type=Path)
    parser.add_argument('--device-report', type=Path)
    args = parser.parse_args()
    os.chdir(ROOT)
    if git('remote', 'get-url', 'origin').decode().strip() not in (f'https://github.com/{REPO}.git', f'git@github.com:{REPO}.git'):
        raise RuntimeError('Unexpected origin; review before releasing.')
    state = source_state()
    name, code = version()
    folder, tag = ROOT / 'dist' / name, f'v{name}'
    if args.action == 'prepare':
        if not all((args.debug_check, args.release_check, args.device_report)):
            parser.error('prepare requires --debug-check, --release-check and --device-report')
        prepare(args, state, name, code, folder)
        return
    validate_bundle(folder, state)
    ci = remote_gate(state, tag)
    remote = find_remote_release(tag)
    if args.action == 'draft':
        if remote and not remote['draft']:
            raise RuntimeError('Already public; use verify. Published releases are never overwritten.')
        if not remote:
            gh('release', 'create', tag, '--repo', REPO, '--verify-tag', '--draft', '--prerelease',
               '--title', f'Floating Focus {name} (Debug preview)', '--notes-file', str(folder / 'RELEASE_NOTES.md'))
            remote = find_remote_release(tag)
            if not remote:
                raise RuntimeError('Draft not yet visible; retry draft after checking remote state.')
        # Resume only missing uploads. Existing bytes are verified, never clobbered.
        for path in sorted(folder.iterdir()):
            if path.is_file() and path.name not in {a['name'] for a in remote['assets']}:
                gh('release', 'upload', tag, str(path), '--repo', REPO)
        remote = find_remote_release(tag)
        if not remote:
            raise RuntimeError('Draft disappeared; inspect remote state before retrying.')
        verify_remote(folder, tag, remote)
        print(f'Draft ready; reused CI {ci["html_url"]}')
    elif args.action == 'publish':
        if not remote:
            raise RuntimeError('Create and verify the draft first.')
        verify_remote(folder, tag, remote)
        if remote['draft']:
            gh('release', 'edit', tag, '--repo', REPO, '--draft=false', '--prerelease')
        verify_remote(folder, tag, api(f'repos/{REPO}/releases/tags/{tag}'))
    else:
        if not remote:
            raise RuntimeError('Release does not exist.')
        verify_remote(folder, tag, remote)


if __name__ == '__main__':
    try:
        main()
    except (RuntimeError, OSError, ValueError, subprocess.SubprocessError) as error:
        raise SystemExit(str(error))
