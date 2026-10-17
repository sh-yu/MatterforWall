"""Generate app release metadata from Gradle's APK output metadata."""
import argparse
import hashlib
import json
from pathlib import Path


def manifest(folder):
    metadata = json.loads((folder / 'output-metadata.json').read_text())
    elements = metadata['elements']
    if len(elements) != 1:
        raise ValueError('Only a universal APK is supported')
    element = elements[0]
    apk = folder / 'app-debug.apk'
    if element['outputFile'] != apk.name or metadata['applicationId'] != 'dev.wallpad.bridge':
        raise ValueError('Unexpected APK package or filename')
    data = apk.read_bytes()
    if not (0 < len(data) <= 32 * 1024 * 1024):
        raise ValueError('APK exceeds app download limit')
    return dict(schema=1, packageName=metadata['applicationId'], versionCode=element['versionCode'],
                versionName=element['versionName'], size=len(data), sha256=hashlib.sha256(data).hexdigest())


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('folder', type=Path)
    args = parser.parse_args()
    (args.folder / 'android-update.json').write_text(json.dumps(manifest(args.folder), indent=2) + '\n')
