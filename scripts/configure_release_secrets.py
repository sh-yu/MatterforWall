"""Encrypt existing signing identities to GitHub Actions; never prints key data.

Requires PyNaCl. Run only in an authorized environment holding the existing
device key and installed Android application's development keystore.
"""
import argparse
import base64
import json
import subprocess
import urllib.request
from pathlib import Path

from nacl.public import PublicKey, SealedBox


def configure(firmware_key, android_keystore):
    credential = subprocess.run(['git', 'credential', 'fill'],
                                input='protocol=https\nhost=github.com\n\n',
                                capture_output=True, text=True, check=True)
    fields = dict(line.split('=', 1) for line in credential.stdout.splitlines() if '=' in line)
    token = fields.get('password')
    if not token:
        raise ValueError('GitHub authentication required')
    base = 'https://api.github.com/repos/sh-yu/MatterforWall/actions/secrets'
    headers = {'Authorization': 'Bearer ' + token, 'Accept': 'application/vnd.github+json',
               'Content-Type': 'application/json', 'User-Agent': 'WallpadBridgeRelease'}
    with urllib.request.urlopen(urllib.request.Request(base + '/public-key', headers=headers), timeout=30) as response:
        public_key = json.load(response)
    box = SealedBox(PublicKey(base64.b64decode(public_key['key'])))
    for name, path in [('FIRMWARE_SIGNING_KEY_B64', firmware_key),
                       ('ANDROID_DEBUG_KEYSTORE_B64', android_keystore)]:
        encrypted = base64.b64encode(box.encrypt(base64.b64encode(path.read_bytes()))).decode()
        body = json.dumps(dict(encrypted_value=encrypted, key_id=public_key['key_id'])).encode()
        request = urllib.request.Request(base + '/' + name, data=body, headers=headers, method='PUT')
        with urllib.request.urlopen(request, timeout=30):
            pass
        print('Configured encrypted Actions secret: ' + name)


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('--firmware-key', type=Path, required=True)
    parser.add_argument('--android-keystore', type=Path, required=True)
    args = parser.parse_args()
    configure(args.firmware_key, args.android_keystore)
