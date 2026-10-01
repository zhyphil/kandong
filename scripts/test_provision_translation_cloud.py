#!/usr/bin/env python3
"""Offline tests: synthetic bundles and temporary files only. Never call main/adb/load_key."""
import hashlib
import json
import os
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch
import provision_translation_cloud as cloud


class CloudProvisioningTest(unittest.TestCase):
    origin = "https://kandong-translation-pilot.test-account.workers.dev"

    def test_rejects_non_origin_non_tls_and_other_services(self):
        for value in ("http://localhost", "https://127.0.0.1", self.origin + "/", self.origin + "?a=b",
                      self.origin + "#a", self.origin + ":8443", self.origin + ".other.test",
                      self.origin.replace("https://", "https://user@"), "https://api-free.deepl.com"):
            with self.assertRaises(ValueError):
                cloud.endpoint(value)
        self.assertEqual(self.origin, cloud.endpoint(self.origin + ":443"))

    def test_bundle_has_exact_ninety_day_random_credential_and_hash_only_server(self):
        with patch.object(cloud.secrets, "token_hex", return_value="a" * 64):
            server, phone = cloud.make_bundle("synthetic-private-key:fx", self.origin, 1000)
        self.assertEqual(1000 + 90 * 86400000, phone["expiresAt"])
        devices = json.loads(server["DEVICE_CREDENTIALS"])
        self.assertEqual(cloud.DEVICE_ID, devices[0]["id"])
        self.assertEqual(hashlib.sha256(phone["token"].encode()).hexdigest(), devices[0]["sha256"])
        self.assertNotIn(phone["token"], json.dumps(server))
        self.assertEqual(phone, cloud.validate_phone(phone, 1001))
        with self.assertRaises(ValueError):
            cloud.validate_phone(phone, phone["expiresAt"])

    def test_private_atomic_artifacts_reject_world_readable_or_symlink(self):
        with tempfile.TemporaryDirectory(dir="/private/tmp") as d:
            path = Path(d) / "fixture.json"
            cloud.write_private(path, {"synthetic": True})
            self.assertEqual(0o600, path.stat().st_mode & 0o777)
            self.assertEqual({"synthetic": True}, cloud.read_private(path))
            path.chmod(0o644)
            with self.assertRaises(ValueError):
                cloud.read_private(path)
            path.chmod(0o600)
            link = Path(d) / "link"
            link.symlink_to(path)
            with self.assertRaises(OSError):
                cloud.read_private(link)

    def test_repeated_prepare_preserves_bundle_and_never_reads_provider_key(self):
        with tempfile.TemporaryDirectory(dir="/private/tmp") as d, patch.object(cloud, "DIRECTORY", Path(d)), patch.object(cloud, "private_directory"):
            server, phone = cloud.make_bundle("synthetic-private-key:fx", self.origin)
            cloud.write_private(Path(d) / cloud.SERVER, server)
            cloud.write_private(Path(d) / cloud.PHONE, phone)
            with patch("deepl_credentials.load_key", side_effect=AssertionError("No key read")), patch("builtins.print"):
                cloud.prepare(self.origin)
            self.assertEqual(phone, cloud.read_private(Path(d) / cloud.PHONE))
            self.assertEqual(server, cloud.read_private(Path(d) / cloud.SERVER))

    def test_revocation_prepares_disabled_secret_without_reset_or_network(self):
        with tempfile.TemporaryDirectory(dir="/private/tmp") as d, patch.object(cloud, "DIRECTORY", Path(d)), patch.object(cloud, "private_directory"):
            server, phone = cloud.make_bundle("synthetic-private-key:fx", self.origin)
            cloud.write_private(Path(d) / cloud.SERVER, server)
            cloud.write_private(Path(d) / cloud.PHONE, phone)
            with patch("builtins.print"), patch.object(cloud, "adb", side_effect=AssertionError("No device call")):
                cloud.revoke()
            self.assertEqual("[]", cloud.read_private(Path(d) / cloud.SERVER)["DEVICE_CREDENTIALS"])
            self.assertFalse((Path(d) / cloud.PHONE).exists())


if __name__ == "__main__":
    unittest.main()
