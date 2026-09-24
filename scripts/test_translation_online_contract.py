import copy
import json
import unittest
from unittest.mock import patch
from translation_online_contract import prepare, accept, plan, cases

class OnlineContractTest(unittest.TestCase):
    def test_full_page_keeps_outside_context_and_target_only_is_explicit_comparison(self):
        case = next(c for c in cases() if c["sourceLanguage"] == "en")
        full = prepare(case["id"]); isolated = prepare(case["id"], False)
        self.assertEqual(len(case["blocks"]), len(full["data"]["screen"]["blocks"]))
        self.assertEqual(set(case["targetIds"]), {b["id"] for b in isolated["data"]["screen"]["blocks"]})
        self.assertEqual(full["data"]["targets"], isolated["data"]["targets"])
        full["data"]["screen"]["blocks"][0]["text"] = "mutated"
        self.assertNotEqual("mutated", prepare(case["id"])["data"]["screen"]["blocks"][0]["text"])

    def test_only_allowlisted_fields_can_reach_model_input(self):
        corpus = copy.deepcopy(cases()); c = corpus[0]
        c["rubric"] = "PRIVATE_ANSWER"; c["package"] = "PRIVATE_APP"
        c["blocks"][0]["rawOcr"] = "PRIVATE_OCR"
        with patch("translation_online_contract.cases", return_value=corpus):
            data = json.dumps(prepare(c["id"]))
        for secret in ["PRIVATE_ANSWER", "PRIVATE_APP", "PRIVATE_OCR"]:
            self.assertNotIn(secret, data)
        self.assertEqual({"targets", "screen"}, set(prepare(c["id"])["data"]))

    def test_unknown_page_refused_and_chinese_never_prepares_remote_request(self):
        with self.assertRaises(ValueError): prepare("arbitrary-real-screen")
        for c in cases():
            if c["sourceLanguage"].startswith("zh"):
                result = prepare(c["id"])
                self.assertEqual("preserve-local", result["action"])
                self.assertNotIn("data", result)
                self.assertTrue(all(b["status"] == "original" for b in result["bound"]))
                with self.assertRaises(ValueError): accept(c["id"], "{}")

    def test_candidate_binding_never_uses_model_geometry_and_null_remains_uncertain(self):
        c = cases()[0]; ids = c["targetIds"]
        result = accept(c["id"], json.dumps({i: None for i in ids}))
        self.assertTrue(all(b["status"] == "uncertain" for b in result))
        by_id = {b["id"]: b for b in c["blocks"]}
        self.assertEqual([by_id[i] for i in ids], [b["source"] for b in result])
        bad = [{i: {"text": "人工", "bounds": [0,0,1,1]} for i in ids}, {},
               {**{i: "人工" for i in ids}, "invented": "extra"}]
        for obj in bad:
            with self.assertRaises(ValueError): accept(c["id"], json.dumps(obj))

    def test_duplicate_blank_oversized_and_wrong_typed_responses_are_rejected(self):
        c = cases()[0]; target = c["targetIds"][0]
        raws = ['{"%s":"a","%s":"b"}' % (target,target), " " * 32769, "[]", "null"]
        raws += [json.dumps({i: value for i in c["targetIds"]}) for value in ("", "x"*2001, 1, True, [])]
        for raw in raws:
            with self.assertRaises(ValueError): accept(c["id"], raw)

    def test_plan_contains_only_fixed_regression_pages_and_no_network(self):
        p = plan()
        self.assertEqual(0, p["networkCalls"]); self.assertIsNone(p["provider"])
        self.assertEqual(44, sum(r["prepared"]["action"] == "candidate-request" for r in p["rows"]))
        self.assertEqual(2, sum(r["mode"] == "original" for r in p["rows"]))
        self.assertEqual(24, len({r["page"] for r in p["rows"]}))

if __name__ == "__main__": unittest.main()
