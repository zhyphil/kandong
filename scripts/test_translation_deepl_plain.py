import json
import tempfile
import unittest
from pathlib import Path
from unittest.mock import Mock
from translation_candidate_c import cases
from translation_deepl import DeepLError, request_body
from translation_deepl_plain import TARGETS, PACE_SECONDS, plain_request, plan, execute


class DeepLPlainTest(unittest.TestCase):
    def test_complete_source_only_in_reading_order_and_only_context_changes(self):
        for case in cases():
            if case["sourceLanguage"].startswith("zh"): continue
            for target in case["targetIds"]:
                body = plain_request(case["id"], target)
                self.assertEqual(request_body(case["id"], target, False),
                                 {k: v for k, v in body.items() if k != "context"})
                self.assertEqual([b["text"] for b in sorted(case["blocks"], key=lambda b: b["readingOrder"])],
                                 [line for line in body["context"].splitlines() if line])
                for tag in ("targetId", "bounds", "groupId", "criteria", "scenario"):
                    self.assertNotIn(tag, body["context"])

    def test_fixed_subset_does_not_send_chinese_or_arbitrary_text(self):
        self.assertEqual(14, len(set(TARGETS)))
        self.assertLessEqual(plan()["sourceCharacters"], 500)
        with self.assertRaises(ValueError): plain_request("unknown-page", "unknown-target")
        for case in cases():
            if case["sourceLanguage"].startswith("zh"):
                with self.assertRaises(ValueError): plain_request(case["id"], case["targetIds"][0])

    def test_quota_refusal_sends_no_translation(self):
        client = Mock(); client.usage.return_value = {"character_count": 999999, "character_limit": 1000000}
        with tempfile.TemporaryDirectory() as d:
            status = execute(Path(d), client, Mock())
            self.assertEqual("INSUFFICIENT_QUOTA", status["error"])
            client._request.assert_not_called()

    def test_failure_stops_without_retry_and_keeps_completed_evidence(self):
        client = Mock(); client.usage.return_value = {"character_count": 0, "character_limit": 1000000}
        client._request.side_effect = [{"translations": [{"detected_source_language": "EN", "text": "测试文本"}]},
                                       DeepLError("HTTP_429")]
        sleeper = Mock()
        with tempfile.TemporaryDirectory() as d:
            status = execute(Path(d), client, sleeper)
            self.assertEqual(1, status["completed"])
            self.assertEqual("HTTP_429", status["error"])
            self.assertEqual(2, client._request.call_count)
            self.assertEqual([((PACE_SECONDS,), {}), ((PACE_SECONDS,), {})], sleeper.call_args_list)
            self.assertTrue((Path(d) / "response-000.json").exists())
            self.assertFalse((Path(d) / "response-001.json").exists())
            self.assertEqual(status, json.loads((Path(d) / "status.json").read_text()))


if __name__ == "__main__": unittest.main()
