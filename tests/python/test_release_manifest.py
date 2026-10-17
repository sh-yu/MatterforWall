import importlib.util
import tempfile
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
SPEC = importlib.util.spec_from_file_location('release_manifest', ROOT / 'scripts/release_manifest.py')
release = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(release)


class ReleaseManifestTests(unittest.TestCase):
    def test_synthetic_metadata_and_reject_invalid_images(self):
        # SYNTHETIC_TEST_DATA: format fixture, not a valid cryptographic signature.
        data = bytearray(8192)
        data[0] = 0xe9
        data[-4096] = 0xe7
        encoded = release.version().encode()
        data[48:48 + len(encoded)] = encoded
        project = b'wallpad-matter-bridge'
        data[80:80 + len(project)] = project
        with tempfile.TemporaryDirectory() as directory:
            image = Path(directory) / 'SYNTHETIC_TEST_DATA.bin'
            image.write_bytes(data)
            result = release.manifest(image)
            self.assertEqual(result['size'], 8192)
            self.assertEqual(len(result['sha256']), 64)
            data[80] = 0
            image.write_bytes(data)
            with self.assertRaises(ValueError):
                release.manifest(image)
            image.write_bytes(b'bad')
            with self.assertRaises(ValueError):
                release.manifest(image)
