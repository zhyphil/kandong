"""DeepL adapter for fixed synthetic KanDong pages, not real-screen translation."""
import copy
import json
import urllib.error
import urllib.request
from deepl_credentials import validate_key
from translation_online_contract import case_by_id, prepare
from translation_probe import bind, reject_duplicate_keys

MAX_BYTES = 32768

class DeepLError(RuntimeError):
    """Only a fixed error code may be surfaced, never the HTTP body/credential."""

class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        raise DeepLError("REDIRECT_REFUSED")


def request_body(page_id, target_id, full_context=True):
    c = case_by_id(page_id)
    if c["sourceLanguage"] not in ("en", "fr") or target_id not in c["targetIds"]:
        raise ValueError("Unsupported synthetic target")
    target = next(b for b in c["blocks"] if b["id"] == target_id)
    body = {"text": [target["text"]], "source_lang": c["sourceLanguage"].upper(),
            "target_lang": "ZH-HANS", "show_billed_characters": True}
    if full_context:
        # The entire visible page travels with EVERY target, including other cards and footnotes.
        # This is an evaluated context representation, not proof DeepL understands its structure.
        data = prepare(page_id)["data"]["screen"]
        body["context"] = json.dumps({"targetId": target_id, "screen": data}, ensure_ascii=False)
    if len(json.dumps(body, ensure_ascii=False).encode()) > MAX_BYTES:
        raise ValueError("Synthetic request too large")
    return body


def bind_response(page_id, target_id, response):
    case = case_by_id(page_id)
    if target_id not in case["targetIds"] or case["sourceLanguage"] not in ("en", "fr"):
        raise ValueError("Unsupported synthetic target")
    if not isinstance(response, dict) or set(response) != {"translations"}:
        raise ValueError("Unexpected response envelope")
    rows = response["translations"]
    if not isinstance(rows, list) or len(rows) != 1 or not isinstance(rows[0], dict):
        raise ValueError("Expected exactly one source-bound translation")
    row = rows[0]
    if row.get("detected_source_language") != case["sourceLanguage"].upper():
        raise ValueError("Unexpected source language")
    if not isinstance(row.get("text"), str):
        raise ValueError("Missing translated text")
    # No string splitting or guessed output geometry; this response belongs to this one target.
    single = copy.deepcopy(case); single["targetIds"] = [target_id]
    return bind(single, json.dumps({"translations": [{"id": target_id, "text": row["text"]}]}, ensure_ascii=False))[0]


def remaining_characters(usage):
    if not isinstance(usage, dict): raise DeepLError("USAGE_UNVERIFIED")
    pairs = [("character_count", "character_limit")]
    if "api_key_character_limit" in usage: pairs.append(("api_key_character_count", "api_key_character_limit"))
    left = []
    for count, limit in pairs:
        if type(usage.get(count)) is not int or type(usage.get(limit)) is not int or usage[count] < 0 or usage[limit] < 0:
            raise DeepLError("USAGE_UNVERIFIED")
        left.append(max(0, usage[limit] - usage[count]))
    return min(left)


class DeepLClient:
    def __init__(self, key):
        self._key = validate_key(key)
        # Official authentication convention. No guessed third-party proxy or endpoint fallback.
        self.endpoint = "https://api-free.deepl.com" if key.endswith(":fx") else "https://api.deepl.com"
        self._opener = urllib.request.build_opener(urllib.request.ProxyHandler({}), NoRedirect())

    def _request(self, path, body=None):
        if (path, body is None) not in (("/v2/usage", True), ("/v2/translate", False)):
            raise DeepLError("OPERATION_REFUSED")
        data = None if body is None else json.dumps(body, ensure_ascii=False).encode()
        if data is not None and len(data) > MAX_BYTES: raise DeepLError("REQUEST_TOO_LARGE")
        request = urllib.request.Request(self.endpoint + path, data=data,
            headers={"Authorization": "DeepL-Auth-Key " + self._key, "Content-Type": "application/json"})
        try:
            with self._opener.open(request, timeout=30) as response:
                raw = response.read(MAX_BYTES + 1)
            if len(raw) > MAX_BYTES: raise DeepLError("RESPONSE_TOO_LARGE")
            return json.loads(raw, object_pairs_hook=reject_duplicate_keys)
        except urllib.error.HTTPError as error:
            raise DeepLError("HTTP_" + str(error.code)) from None
        except (urllib.error.URLError, TimeoutError, OSError):
            raise DeepLError("CONNECTION_FAILED_NO_RETRY") from None
        except (ValueError, UnicodeError):
            raise DeepLError("INVALID_RESPONSE") from None

    def usage(self): return self._request("/v2/usage")

    def translate(self, page_id, target_id, full_context=True):
        body = request_body(page_id, target_id, full_context)
        response = self._request("/v2/translate", body)
        bound = bind_response(page_id, target_id, response)
        # Persist only authored-input requests and expected response fields, never headers/key.
        row = response["translations"][0]
        meta = {k: row[k] for k in ("detected_source_language", "billed_characters", "model_type_used") if k in row}
        return {"request": body, "bound": bound, "responseMetadata": meta}
