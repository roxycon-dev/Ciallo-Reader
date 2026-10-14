import importlib.util
import pathlib
import unittest

spec = importlib.util.spec_from_file_location("upgrade", pathlib.Path(__file__).with_name("verify-apk-upgrade.py"))
upgrade = importlib.util.module_from_spec(spec)
spec.loader.exec_module(upgrade)
PACKAGE = "com.aistudio.novelreader.kxmpzq"
CERT = "a" * 64


class UpgradeTest(unittest.TestCase):
    def test_certificate_output(self):
        self.assertEqual(upgrade.apk_identity(f"package: name='{PACKAGE}' versionCode='218' versionName='1.3.7'", f"Signer #1 certificate SHA-256 digest: {CERT}"), (PACKAGE, 218, {CERT}))

    def test_same_key_upgrade(self):
        upgrade.validate_upgrade((PACKAGE, 218, {CERT}), (PACKAGE, 217, {CERT}), CERT)

    def test_reject_conflicting_upgrade(self):
        for previous in [(PACKAGE, 217, {"b" * 64}), (PACKAGE + ".debug", 217, {CERT}), (PACKAGE, 218, {CERT}), (PACKAGE, 219, {CERT})]:
            with self.subTest(previous=previous), self.assertRaises(ValueError):
                upgrade.validate_upgrade((PACKAGE, 218, {CERT}), previous, CERT)

    def test_reject_wrong_release(self):
        for new, debug in [((PACKAGE, 218, {"b" * 64}), False), ((PACKAGE + ".debug", 218, {CERT}), False), ((PACKAGE, 218, {CERT}), True)]:
            with self.subTest(new=new, debug=debug), self.assertRaises(ValueError):
                upgrade.validate_upgrade(new, None, CERT, debug)

    def test_reject_missing_metadata(self):
        with self.assertRaises(ValueError):
            upgrade.apk_identity("", "")


if __name__ == "__main__":
    unittest.main()
