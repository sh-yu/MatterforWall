"""Generate OTA metadata without copying signing material."""
import argparse
import hashlib
import json
import re
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]


def version():
    return re.search(r'set\(PROJECT_VER "(\d+\.\d+\.\d+)"\)',
                     (ROOT / 'firmware/CMakeLists.txt').read_text()).group(1)


def manifest(image):
    data = image.read_bytes()
    if not (4096 < len(data) <= 0x1e0000 and data[0] == 0xe9 and data[-4096] == 0xe7):
        raise ValueError('Signed ESP image within OTA slot required')
    if data[48:80].split(b'\0')[0].decode() != version():
        raise ValueError('Image version mismatch')
    if data[80:112].split(b'\0')[0] != b'wallpad-matter-bridge':
        raise ValueError('Wrong project')
    return dict(schema=1, project='wallpad-matter-bridge', target='esp32c6',
                version=version(), size=len(data), sha256=hashlib.sha256(data).hexdigest())


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('--image', type=Path)
    parser.add_argument('--output', type=Path)
    parser.add_argument('--check-version')
    parser.add_argument('--print-version', action='store_true')
    args = parser.parse_args()
    if args.check_version and args.check_version.startswith('v') and args.check_version != 'v' + version():
        raise ValueError('Tag must match PROJECT_VER')
    if args.print_version:
        print(version())
    if args.image:
        args.output.write_text(json.dumps(manifest(args.image), indent=2) + '\n')
