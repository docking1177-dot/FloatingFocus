"""Use existing gh authentication or GCM; never persist or print credentials."""
import json
import os
import shutil
import subprocess
from source_state import ROOT


def gh(*args, binary=False):
    executable = shutil.which('gh') or str(ROOT / '.tools/gh/gh.exe')
    env = os.environ.copy()
    env.update(GH_PROMPT_DISABLED='1', GH_HOST='github.com', GCM_INTERACTIVE='never', GIT_TERMINAL_PROMPT='0')
    if not (env.get('GH_TOKEN') or env.get('GITHUB_TOKEN')):
        auth = subprocess.run([executable, 'auth', 'status'], capture_output=True, env=env, timeout=30)
        if auth.returncode:
            credential = subprocess.run(['git', 'credential', 'fill'],
                input='protocol=https\nhost=github.com\n\n', text=True, capture_output=True, env=env, timeout=45)
            values = dict(line.split('=', 1) for line in credential.stdout.splitlines() if '=' in line)
            if credential.returncode or not values.get('password'):
                raise RuntimeError('GitHub authentication unavailable; sign in using gh or Git Credential Manager.')
            env['GH_TOKEN'] = values['password']
    result = subprocess.run([executable, *args], capture_output=True, env=env, timeout=180)
    if result.returncode:
        # Do not relay credential subprocess output or arbitrary API error bodies.
        raise RuntimeError(f'gh {args[0]} failed (exit {result.returncode}); check authentication/network and remote state.')
    return result.stdout if binary else result.stdout.decode('utf-8').strip()


def api(path):
    return json.loads(gh('api', path))
