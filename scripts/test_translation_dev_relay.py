import copy
import unittest
from translation_dev_relay import Relay, Refused, prepare, DISCLOSURE


def page():
    return {"requestId": "01234567-1234-1234-1234-012345678901", "language": "EN",
            "disclosure": DISCLOSURE, "publicPageConfirmed": True, "remainingMillis": 50000,
            "blocks": [{"id": "page/row-1", "text": "The museum closes at 18:00."},
                       {"id": "page/row-2", "text": "Last entry is one hour before closing."}]}


class FakeClient:
    def __init__(self):
        self.calls = []
    def usage(self):
        return {"character_count": 0, "character_limit": 500000}
    def _request(self, path, body):
        self.calls.append((path, copy.deepcopy(body)))
        return {"translations": [{"text": "译文" + str(i), "detected_source_language": "EN"} for i in range(len(body["text"]))]}


class RelayTest(unittest.TestCase):
    def test_single_start_disclosure_can_translate_without_preview_roundtrip(self):
        client=FakeClient(); relay=Relay(client,"a"*64)
        payload=page(); payload["disclosure"]="deepl-free-direct-v2"
        result=relay.translate("Bearer " + "a"*64,payload)
        self.assertEqual(len(result["translations"]),2)
        self.assertEqual(len(client.calls),1)
        self.assertEqual(client.calls[0][1]["context"],"\n".join(b["text"] for b in payload["blocks"]))

    def test_old_preview_disclosure_does_not_authorize_new_direct_flow(self):
        client=FakeClient(); relay=Relay(client,"a"*64)
        payload=page(); payload["disclosure"]="deepl-free-public-v1"
        with self.assertRaisesRegex(Refused,"CONSENT_REQUIRED"):
            relay.translate("Bearer " + "a"*64,payload)
        self.assertEqual(client.calls,[])

    def test_entire_page_context_and_stable_response_order(self):
        client=FakeClient(); relay=Relay(client,"a"*64)
        result=relay.translate("Bearer " + "a"*64,page())
        self.assertEqual([r["id"] for r in result["translations"]], [r["id"] for r in page()["blocks"]])
        self.assertEqual(client.calls[0][1]["context"], "\n".join(b["text"] for b in page()["blocks"]))
        self.assertEqual(client.calls[0][1]["text"], [b["text"] for b in page()["blocks"]])

    def test_no_request_without_explicit_page_consent_or_token(self):
        client=FakeClient(); relay=Relay(client,"a"*64)
        with self.assertRaises(Refused): relay.translate("Bearer wrong",page())
        p=page(); p["publicPageConfirmed"]=False
        with self.assertRaises(Refused): relay.translate("Bearer " + "a"*64,p)
        self.assertEqual(client.calls,[])

    def test_sensitive_full_context_is_rejected_before_usage_or_translate(self):
        p=page(); p["blocks"][1]["text"]="Verification code 123456"
        with self.assertRaisesRegex(Refused,"SENSITIVE_PAGE"): prepare(p)

    def test_duplicate_and_free_budget_do_not_retry(self):
        client=FakeClient(); relay=Relay(client,"a"*64)
        relay.translate("Bearer " + "a"*64,page())
        with self.assertRaisesRegex(Refused,"DUPLICATE"): relay.translate("Bearer " + "a"*64,page())
        self.assertEqual(len(client.calls),1)
        with self.assertRaisesRegex(Refused,"SESSION_LIMIT"): Relay(client,"b"*64,budget=1).translate("Bearer " + "b"*64,page())

    def test_unknown_fields_wrong_language_duplicate_ids_rejected(self):
        for mutate in (lambda p: p.update(image="not allowed"),lambda p: p.update(language="DE"),
                       lambda p: p["blocks"][1].update(id=p["blocks"][0]["id"])):
            p=page(); mutate(p)
            with self.assertRaises(Refused): prepare(p)

    def test_incomplete_provider_response_fails_closed(self):
        class Partial(FakeClient):
            def _request(self,path,body): return {"translations": []}
        with self.assertRaisesRegex(Refused,"INVALID_RESPONSE"):
            Relay(Partial(),"a"*64).translate("Bearer " + "a"*64,page())

    def test_cancel_during_usage_never_submits_page_to_provider(self):
        client=FakeClient(); relay=Relay(client,"a"*64)
        def usage():
            relay.cancel("Bearer " + "a"*64,page()["requestId"])
            return {"character_count":0,"character_limit":500000}
        client.usage=usage
        with self.assertRaisesRegex(Refused,"CANCELLED"):
            relay.translate("Bearer " + "a"*64,page())
        self.assertEqual(client.calls,[])

    def test_cancel_arriving_before_translate_is_sticky(self):
        client=FakeClient(); relay=Relay(client,"a"*64)
        relay.cancel("Bearer " + "a"*64,page()["requestId"])
        with self.assertRaisesRegex(Refused,"CANCELLED"): relay.translate("Bearer " + "a"*64,page())
        self.assertEqual(client.calls,[])

    def test_expired_lease_after_usage_never_submits_page(self):
        from unittest.mock import patch
        client=FakeClient(); relay=Relay(client,"a"*64)
        with patch("translation_dev_relay.time.monotonic",side_effect=[100.,100.,146.]):
            with self.assertRaisesRegex(Refused,"PAGE_EXPIRED"): relay.translate("Bearer " + "a"*64,page())
        self.assertEqual(client.calls,[])

    def test_phone_disconnect_after_usage_never_submits_page(self):
        client=FakeClient(); relay=Relay(client,"a"*64)
        checks=iter([True,False])
        with self.assertRaisesRegex(Refused,"CANCELLED"):
            relay.translate("Bearer " + "a"*64,page(),lambda: next(checks))
        self.assertEqual(client.calls,[])


if __name__=="__main__": unittest.main()
