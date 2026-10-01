#!/usr/bin/env python3
"""Opt-in USB development relay. Binds ONLY localhost; never records page content.

No API request is made at startup. A phone-confirmed page is required for every
translation. Only an existing API Free key is accepted; no paid endpoint/fallback.
"""
import argparse
import hmac
import json
import re
import secrets
import select
import socket
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from deepl_credentials import load_key
from translation_deepl import DeepLClient, DeepLError, remaining_characters

PORT = 18741
MAX_BODY = 32768
MAX_CHARS = 6000
DISCLOSURE = "deepl-free-direct-v2"
TOKEN_FILE = Path(__file__).resolve().parents[1] / ".local/translation/relay-token"


class Refused(Exception):
    pass


def unique_pairs(pairs):
    result = {}
    for key, value in pairs:
        if key in result:
            raise Refused("INVALID_REQUEST")
        result[key] = value
    return result


def sensitive(text):
    # Best-effort screen-text guard; NOT a guarantee. User starts translation only after the
    # setup screen states the whole-page scope and public-content restriction.
    return bool(re.search(
        r"(?i)(password|passcode|verification code|one.time code|mot de passe|"
        r"code de v[eé]rification|验证码|密码|银行卡|信用卡|身份证|"
        r"\bIBAN\b|\bCVV\b|\bCVC\b|[\w.+-]+@[\w.-]+\.[a-z]{2,}|"
        r"(?:\d[ -]?){13,19})", text))


def prepare(payload):
    if not isinstance(payload, dict) or set(payload) != {"requestId", "language", "disclosure", "publicPageConfirmed", "remainingMillis", "blocks"}:
        raise Refused("INVALID_REQUEST")
    request_id = payload["requestId"]
    if not isinstance(request_id, str) or not re.fullmatch(r"[a-f0-9-]{36}", request_id):
        raise Refused("INVALID_REQUEST")
    if payload["language"] not in ("EN", "FR") or payload["disclosure"] != DISCLOSURE or payload["publicPageConfirmed"] is not True:
        raise Refused("CONSENT_REQUIRED")
    if type(payload["remainingMillis"]) is not int or not 5000 < payload["remainingMillis"] <= 60000:
        raise Refused("PAGE_EXPIRED")
    blocks = payload["blocks"]
    if not isinstance(blocks, list) or not 1 <= len(blocks) <= 50:
        raise Refused("PAGE_TOO_LARGE")
    ids, texts = [], []
    for b in blocks:
        if not isinstance(b, dict) or set(b) != {"id", "text"}:
            raise Refused("INVALID_REQUEST")
        if not isinstance(b["id"], str) or not re.fullmatch(r"[A-Za-z0-9_/-]{1,240}", b["id"]) or b["id"] in ids:
            raise Refused("INVALID_REQUEST")
        value = b["text"]
        if not isinstance(value, str) or not value.strip() or len(value) > 1500 or any(ord(c) < 32 and c not in "\n\t" for c in value):
            raise Refused("INVALID_REQUEST")
        ids.append(b["id"])
        texts.append(value)
    chars = sum(map(len, texts))
    if chars > MAX_CHARS:
        raise Refused("PAGE_TOO_LARGE")
    context = "\n".join(texts)
    if sensitive(context):
        raise Refused("SENSITIVE_PAGE")
    body = {"text": texts, "source_lang": payload["language"], "target_lang": "ZH-HANS",
            "context": context, "split_sentences": "nonewlines"}
    if len(json.dumps(body, ensure_ascii=False).encode()) > MAX_BODY:
        raise Refused("PAGE_TOO_LARGE")
    return request_id, ids, body, chars


class Relay:
    def __init__(self, client, token, budget=20000):
        self.client, self.token, self.budget = client, token, budget
        self.lock = threading.Lock()
        self.control = threading.Lock()
        self.seen = set()
        self.cancelled = set()
        self.expires = time.monotonic() + 4 * 3600

    def cancel(self, authorization, request_id):
        if not hmac.compare_digest(authorization, "Bearer " + self.token):
            raise Refused("UNAUTHORIZED")
        if not isinstance(request_id,str) or not re.fullmatch(r"[a-f0-9-]{36}",request_id):
            raise Refused("INVALID_REQUEST")
        with self.control:
            if len(self.cancelled)>=200: raise Refused("SESSION_LIMIT")
            self.cancelled.add(request_id)  # Tombstone also covers cancellation arriving first.
        return {"cancelled": True}

    def translate(self, authorization, payload, connected=lambda: True):
        if not hmac.compare_digest(authorization, "Bearer " + self.token):
            raise Refused("UNAUTHORIZED")
        request_id, ids, body, chars = prepare(payload)
        # Use a relative lease with a conservative USB/network allowance. We never
        # compare the Android elapsed clock to the Mac monotonic clock.
        deadline=time.monotonic()+(payload["remainingMillis"]-5000)/1000
        if not self.lock.acquire(blocking=False):
            raise Refused("BUSY")
        try:
            if time.monotonic() >= self.expires:
                raise Refused("RELAY_EXPIRED")
            if request_id in self.seen:
                raise Refused("DUPLICATE_NO_RETRY")
            if len(self.seen) >= 100 or chars > self.budget:
                raise Refused("SESSION_LIMIT")
            self.seen.add(request_id)  # Even an uncertain request must never retry.
            with self.control:
                if request_id in self.cancelled or not connected(): raise Refused("CANCELLED")
            if remaining_characters(self.client.usage()) < chars:
                raise Refused("FREE_QUOTA_EXCEEDED")
            # Quota lookup may have taken seconds. Revoke/expiry/disconnect must be
            # checked AGAIN before the first submission of page text to DeepL.
            with self.control:
                if request_id in self.cancelled or not connected(): raise Refused("CANCELLED")
                if time.monotonic()>=deadline: raise Refused("PAGE_EXPIRED")
                self.budget -= chars  # Submission boundary; never refund ambiguity.
            response = self.client._request("/v2/translate", body)
            rows = response.get("translations") if isinstance(response, dict) else None
            if not isinstance(rows, list) or len(rows) != len(ids):
                raise Refused("INVALID_RESPONSE")
            result = []
            for ident, row in zip(ids, rows):
                if not isinstance(row, dict) or not isinstance(row.get("text"), str) or not row["text"].strip() or len(row["text"]) > 6000:
                    raise Refused("INVALID_RESPONSE")
                if row.get("detected_source_language") != body["source_lang"]:
                    raise Refused("LANGUAGE_MISMATCH")
                result.append({"id": ident, "text": row["text"]})
            return {"requestId": request_id, "translations": result}
        finally:
            body.clear()
            self.lock.release()


def handler_for(relay):
    class Handler(BaseHTTPRequestHandler):
        def setup(self):
            super().setup()
            self.connection.settimeout(5)

        def log_message(self, *args):
            pass  # No URLs, headers, payloads, provider responses or tracebacks.

        def do_POST(self):
            status, result = 400, {"error": "INVALID_REQUEST"}
            try:
                if self.path not in ("/translate","/cancel") or self.headers.get("Transfer-Encoding") or self.headers.get("Content-Type") != "application/json":
                    raise Refused("INVALID_REQUEST")
                lengths = self.headers.get_all("Content-Length", [])
                if len(lengths) != 1 or not lengths[0].isdigit() or not 0 < int(lengths[0]) <= MAX_BODY:
                    raise Refused("PAGE_TOO_LARGE")
                auth = self.headers.get("Authorization", "")
                if len(auth) > 128 or not hmac.compare_digest(auth, "Bearer " + relay.token):
                    raise Refused("UNAUTHORIZED")
                raw = self.rfile.read(int(lengths[0]))
                if len(raw) != int(lengths[0]):
                    raise Refused("INVALID_REQUEST")
                payload = json.loads(raw, object_pairs_hook=unique_pairs)
                def connected():
                    readable,_,_=select.select([self.connection],[],[],0)
                    return not readable or bool(self.connection.recv(1,socket.MSG_PEEK))
                if self.path == "/cancel":
                    if not isinstance(payload,dict) or set(payload)!={"requestId"}: raise Refused("INVALID_REQUEST")
                    result=relay.cancel(auth,payload["requestId"])
                else:
                    result = relay.translate(auth, payload, connected)
                status = 200
            except Refused as e:
                result = {"error": str(e)}
            except DeepLError:
                status, result = 502, {"error": "PROVIDER_FAILED_NO_RETRY"}
            except Exception:
                status, result = 400, {"error": "INVALID_REQUEST"}
            data = json.dumps(result, ensure_ascii=False).encode()
            if len(data) > MAX_BODY:
                status, data = 502, b'{"error":"RESPONSE_TOO_LARGE"}'
            try:
                self.send_response(status)
                self.send_header("Content-Type", "application/json; charset=utf-8")
                self.send_header("Cache-Control", "no-store")
                self.send_header("Connection", "close")
                self.send_header("Content-Length", str(len(data)))
                self.end_headers()
                self.wfile.write(data)
            except OSError:
                pass
    return Handler


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--port", type=int, default=PORT, choices=[PORT])
    args = parser.parse_args()
    key = load_key()
    if not key.endswith(":fx"):
        raise SystemExit("Only an API Free key is supported by this development relay.")
    token = secrets.token_hex(32)
    # A short-lived relay token, never the provider key. Private provisioning reads
    # it without displaying it. Replace atomically and delete on normal exit.
    import os
    TOKEN_FILE.parent.mkdir(mode=0o700, parents=True, exist_ok=True)
    temp = TOKEN_FILE.with_name("relay-token-" + secrets.token_hex(8))
    fd = os.open(temp, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
    with os.fdopen(fd, "w") as stream:
        stream.write(token)
    os.replace(temp, TOKEN_FILE)
    server = ThreadingHTTPServer(("127.0.0.1", args.port), handler_for(Relay(DeepLClient(key), token)))
    server.daemon_threads = True
    print("USB translation relay ready on localhost; waiting for a phone-confirmed public page.", flush=True)
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        pass
    finally:
        server.server_close()
        if TOKEN_FILE.is_file() and not TOKEN_FILE.is_symlink() and hmac.compare_digest(TOKEN_FILE.read_text(), token):
            TOKEN_FILE.unlink()


if __name__ == "__main__":
    main()
