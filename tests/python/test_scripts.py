import tempfile
import unittest
from pathlib import Path
import sys

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "scripts"))

import check_ota_size
import validate_profiles


class RepositoryScriptTests(unittest.TestCase):
    def test_partition_has_two_equal_ota_slots(self):
        sizes = check_ota_size.ota_slots(ROOT / "firmware" / "partitions.csv")
        self.assertEqual([0x1E0000, 0x1E0000], sizes)

    def test_checked_in_profiles_are_safe(self):
        for profile in (ROOT / "profiles").rglob("*.json"):
            if profile.name != "profile.schema.json":
                validate_profiles.validate(profile)

    def test_dangerous_profile_is_rejected(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "bad.json"
            path.write_text('''{
              "schemaVersion":1,"manufacturer":"x","model":"x","profileVersion":"1.0.0",
              "verification":"CONTROL_VERIFIED","uart":{},"framing":{},"commands":{},
              "txPolicy":{"defaultAllow":false,"allowList":["gas.open"]}}
            ''', encoding="utf-8")
            with self.assertRaises(ValueError):
                validate_profiles.validate(path)


if __name__ == "__main__":
    unittest.main()
