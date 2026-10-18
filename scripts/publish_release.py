"""Publish locally verified release assets using the existing Git credential.

Credentials stay in memory and are sent only to GitHub's official APIs.
Never prints credential-helper output or request headers.
"""
import argparse
import hashlib
import json
import subprocess
import sys
import urllib.error
import urllib.parse
import urllib.request
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
REPOSITORY = 'sh-yu/MatterforWall'


def publish(folder, version):
    credential = subprocess.run(['git', 'credential', 'fill'],
                                input='protocol=https\nhost=github.com\n\n',
                                capture_output=True, text=True, check=True)
    fields = dict(line.split('=', 1) for line in credential.stdout.splitlines() if '=' in line)
    token = fields.get('password')
    if not token:
        raise ValueError('GitHub authentication is required')

    def request(url, data=None, content_type='application/json', method=None):
        parsed = urllib.parse.urlparse(url)
        if parsed.scheme != 'https' or parsed.hostname not in ('api.github.com', 'uploads.github.com'):
            raise ValueError('Unexpected GitHub API endpoint')
        headers = {'Authorization': 'Bearer ' + token, 'Accept': 'application/vnd.github+json',
                   'User-Agent': 'WallpadBridgeRelease', 'Content-Type': content_type}
        req = urllib.request.Request(url, data=data, headers=headers, method=method)
        with urllib.request.urlopen(req, timeout=120) as response:
            return json.load(response)

    base = 'https://api.github.com/repos/' + REPOSITORY
    sha = subprocess.check_output(['git', 'rev-parse', 'HEAD'], text=True).strip()
    remote_sha = request(base + '/git/ref/heads/main')['object']['sha']
    if sha != remote_sha:
        raise ValueError('Push the current commit to main before publishing')
    firmware = json.loads((folder / 'firmware-update.json').read_text())
    if firmware['version'] != version:
        raise ValueError('Release version mismatch')
    names = ['wallpad-matter-bridge.bin', 'firmware-update.json', 'app-debug.apk', 'android-update.json']
    if not all((folder / name).is_file() for name in names):
        raise ValueError('Missing release asset')
    for metadata, binary in [('firmware-update.json', 'wallpad-matter-bridge.bin'),
                             ('android-update.json', 'app-debug.apk')]:
        info = json.loads((folder / metadata).read_text())
        data = (folder / binary).read_bytes()
        if len(data) != info['size'] or hashlib.sha256(data).hexdigest() != info['sha256']:
            raise ValueError('Release asset hash/size mismatch')
    tag = 'v' + version
    try:
        existing = request(base + '/releases/tags/' + tag)
    except urllib.error.HTTPError as error:
        if error.code != 404:
            raise
        existing = None
    if existing:
        raise ValueError('Release already exists; inspect it before retrying')
    body = dict(tag_name=tag, target_commitish=sha, name=tag, draft=True,
                body='Signed firmware ' + version +
                ' and Android manager with GitHub app updates. Elevator candidates are passive/offline only; '
                'live bus and Matter hub commissioning validation: HARDWARE_TEST_PENDING.')
    release = request(base + '/releases', json.dumps(body).encode())
    upload = release['upload_url'].split('{')[0]
    for name in names:
        mime = 'application/json' if name.endswith('.json') else 'application/octet-stream'
        request(upload + '?name=' + urllib.parse.quote(name), (folder / name).read_bytes(), mime)
        print('Uploaded ' + name)
    result = request(base + '/releases/' + str(release['id']),
                     json.dumps(dict(draft=False, make_latest='true')).encode(), method='PATCH')
    print('Published ' + result['html_url'])


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('folder', type=Path)
    parser.add_argument('--version', required=True)
    args = parser.parse_args()
    try:
        publish(args.folder, args.version)
    except urllib.error.HTTPError as error:
        print('GitHub API failed: HTTP ' + str(error.code), file=sys.stderr)
        sys.exit(1)
