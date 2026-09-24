import json
import tempfile
import unittest
import urllib.error
from pathlib import Path
from unittest.mock import patch
from translation_candidate_c import cases
from translation_deepl import DeepLClient, DeepLError, NoRedirect, request_body, bind_response, remaining_characters
from deepl_credentials import save_key, load_key

DUMMY = "dummy-not-a-real-credential-123456"
class DeepLContractTest(unittest.TestCase):
    def test_whole_screen_and_own_target_retained_per_request(self):
        for case in cases():
            if case["sourceLanguage"].startswith("zh"): continue
            for target in case["targetIds"]:
                full = request_body(case["id"], target); isolated = request_body(case["id"], target, False)
                context = json.loads(full["context"])
                self.assertEqual(target, context["targetId"])
                self.assertEqual(case["blocks"], context["screen"]["blocks"])
                self.assertEqual(case["groups"], context["screen"]["groups"])
                self.assertEqual(isolated, {k: v for k, v in full.items() if k != "context"})
                self.assertEqual(1, len(full["text"]))

    def test_chinese_and_unknown_targets_cannot_be_sent(self):
        for c in cases():
            with self.assertRaises(ValueError): request_body(c["id"], "invented")
            if c["sourceLanguage"].startswith("zh"):
                with self.assertRaises(ValueError): request_body(c["id"], c["targetIds"][0])

    def test_single_response_binds_original_geometry_and_rejects_mismatched_count_language(self):
        c=cases()[0]; target=c["targetIds"][0]
        row={"text":"人工测试译文", "detected_source_language":"EN", "bounds":[0,0,1,1]}
        result=bind_response(c["id"], target, {"translations":[row]})
        self.assertEqual(next(b for b in c["blocks"] if b["id"]==target), result["source"])
        for response in [{"translations":[]},{"translations":[row,row]}, {"translations":[{**row,"text":""}]},
                         {"translations":[{**row,"detected_source_language":"FR"}]}, {"translations":[row],"extra":"bad"}]:
            with self.assertRaises(ValueError): bind_response(c["id"], target, response)

    def test_quota_is_checked_against_account_and_key_without_guessing_unknown_values(self):
        self.assertEqual(10, remaining_characters({"character_count":90,"character_limit":100}))
        self.assertEqual(3, remaining_characters({"character_count":90,"character_limit":100,"api_key_character_count":7,"api_key_character_limit":10}))
        self.assertEqual(0, remaining_characters({"character_count":101,"character_limit":100}))
        for data in [None,[],{},{"character_count":False,"character_limit":100},{"character_count":0,"character_limit":-1}]:
            with self.assertRaises(DeepLError): remaining_characters(data)

    def test_endpoint_and_redirect_restrictions(self):
        self.assertEqual("https://api-free.deepl.com", DeepLClient(DUMMY+":fx").endpoint)
        self.assertEqual("https://api.deepl.com", DeepLClient(DUMMY).endpoint)
        with self.assertRaises(DeepLError): DeepLClient(DUMMY)._request("/v2/documents", {})
        with self.assertRaises(DeepLError): NoRedirect().redirect_request(None,None,302,None,None,"https://example.com")

    def test_error_never_echoes_key_or_response_body_and_never_retries(self):
        client=DeepLClient(DUMMY)
        with patch.object(client._opener,"open",side_effect=urllib.error.HTTPError("https://api.deepl.com",403,DUMMY,{},None)) as call:
            with self.assertRaisesRegex(DeepLError,"^HTTP_403$"): client.usage()
            self.assertEqual(1,call.call_count)
        with patch.object(client._opener,"open",side_effect=TimeoutError(DUMMY)) as call:
            with self.assertRaisesRegex(DeepLError,"^CONNECTION_FAILED_NO_RETRY$"): client.usage()
            self.assertEqual(1,call.call_count)

    def test_local_credential_file_is_private_and_rejects_symlinks_and_bad_format(self):
        with tempfile.TemporaryDirectory(prefix="kandong-key-test-") as temp:
            root=Path(temp); directory=root/".local"/"translation"; key=directory/"deepl-api-key"
            with patch("deepl_credentials.ROOT",root),patch("deepl_credentials.KEY_DIR",directory),patch("deepl_credentials.KEY_FILE",key):
                save_key(DUMMY); self.assertEqual(DUMMY,load_key())
                self.assertEqual(0o600,key.stat().st_mode & 0o777)
                key.chmod(0o644)
                with self.assertRaises(ValueError): load_key()
                key.unlink(); key.symlink_to(root/"other")
                with self.assertRaises(OSError): save_key(DUMMY)
                with self.assertRaises(ValueError): save_key("short")

if __name__=="__main__":unittest.main()
