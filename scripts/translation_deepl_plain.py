"""One-variable, synthetic-only DeepL context diagnosis. Default: no network."""
import argparse
import hashlib
import json
import time
from pathlib import Path
from deepl_credentials import load_key
from translation_deepl import DeepLClient, DeepLError, MAX_BYTES, bind_response, remaining_characters, request_body
from translation_online_contract import case_by_id
from translation_probe import ROOT

# Selected before this experiment: ambiguous short labels plus date/currency controls.
# No expected answers, disambiguating prose, or inferred intent are sent to DeepL.
TARGETS = (("p001", "b005"), ("p002", "b004"), ("p101", "b003"), ("p102", "b004"),
           ("p004", "b004"), ("p005", "b004"), ("p103", "b003"), ("p104", "b004"),
           ("p201", "b004"), ("p202", "b003"), ("p203", "b003"), ("p204", "b003"),
           ("p106", "b004"), ("p003", "b004"))
PACE_SECONDS = 2.0


def plain_request(page_id, target_id):
    body = request_body(page_id, target_id, full_context=False)
    blocks = sorted(case_by_id(page_id)["blocks"], key=lambda b: b["readingOrder"])
    parts = []
    for index, block in enumerate(blocks):
        if index:
            parts.append("\n\n" if blocks[index - 1]["groupId"] != block["groupId"] else "\n")
        parts.append(block["text"])
    body["context"] = "".join(parts)
    if len(json.dumps(body, ensure_ascii=False).encode()) > MAX_BYTES:
        raise ValueError("Synthetic request too large")
    return body


def plan():
    rows = [{"page": page, "target": target, "mode": "plain-full",
             "request": plain_request(page, target)} for page, target in TARGETS]
    return {"syntheticOnly": True, "contextRepresentation": "whole-page-source-lines-v1",
            "purpose": "selected development diagnosis; not a blind holdout",
            "calls": len(rows), "sourceCharacters": sum(len(r["request"]["text"][0]) for r in rows),
            "paceSeconds": PACE_SECONDS, "retries": 0, "qualityAccepted": False, "rows": rows}


def save(path, value):
    path.write_text(json.dumps(value, ensure_ascii=False, indent=2) + "\n")


def execute(out, client, sleep=time.sleep):
    frozen = plan()
    completed = 0
    attempted_chars = 0
    try:
        usage = client.usage()
        save(out / "usage-before.json", usage)
        left = remaining_characters(usage)
        if usage["character_limit"] > 1000000: raise DeepLError("PLAN_LIMIT_NEEDS_REVIEW")
        if left < frozen["sourceCharacters"]: raise DeepLError("INSUFFICIENT_QUOTA")
        for index, row in enumerate(frozen["rows"]):
            sleep(PACE_SECONDS)  # Pace new requests; do not resume/retry the interrupted old run.
            start = time.monotonic()
            attempted_chars += len(row["request"]["text"][0])
            response = client._request("/v2/translate", row["request"])
            bound = bind_response(row["page"], row["target"], response)
            meta = {k: response["translations"][0][k] for k in
                    ("detected_source_language", "billed_characters", "model_type_used")
                    if k in response["translations"][0]}
            save(out / f"response-{index:03}.json", {**row, "bound": bound,
                 "responseMetadata": meta, "seconds": time.monotonic() - start})
            completed += 1
            print(json.dumps({"completed": completed, "of": frozen["calls"]}), flush=True)
        save(out / "usage-after.json", client.usage())
        status = {"status": "completed", "completed": completed, "attemptedSourceCharacters": attempted_chars,
                  "qualityAccepted": False, "requiresSemanticReview": True}
    except (DeepLError, ValueError, OSError) as error:
        status = {"status": "stopped", "completed": completed, "attemptedSourceCharacters": attempted_chars,
                  "error": str(error) if isinstance(error, DeepLError) else type(error).__name__,
                  "retried": False, "qualityAccepted": False}
    save(out / "status.json", status)
    return status


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--out", type=Path, required=True)
    parser.add_argument("--execute-synthetic", action="store_true")
    args = parser.parse_args()
    frozen = plan()
    if frozen["sourceCharacters"] > 500: parser.error("diagnostic budget exceeds 500 characters")
    args.out.mkdir(parents=True, exist_ok=False)
    save(args.out / "plan.json", frozen)
    # Hash all transitive fixture readers and the fixed fixture files, without reading any secret.
    names = ["translation_deepl_plain.py", "translation_deepl.py", "deepl_credentials.py",
             "translation_online_contract.py", "translation_candidate_c.py", "translation_candidate_v3.py",
             "translation_probe.py", "translation_probe_v2.py"]
    paths = [ROOT / "scripts" / n for n in names]
    paths += [ROOT / "docs/fixtures" / n for n in ["translation-pages-v1.json",
              "translation-holdout-v2.json", "translation-holdout-v3.json", "translation-holdout-v4.json"]]
    save(args.out / "source-hashes.json", {str(p.relative_to(ROOT)): hashlib.sha256(p.read_bytes()).hexdigest() for p in paths})
    print(json.dumps({"plannedCalls": frozen["calls"], "sourceCharacters": frozen["sourceCharacters"],
                      "execute": args.execute_synthetic}), flush=True)
    if not args.execute_synthetic: return 0
    try:
        client = DeepLClient(load_key())
    except (ValueError, OSError):
        save(args.out / "status.json", {"status": "stopped", "completed": 0, "error": "CREDENTIAL_UNAVAILABLE"})
        return 1
    status = execute(args.out, client)
    print(json.dumps(status), flush=True)
    return 0 if status["status"] == "completed" else 1


if __name__ == "__main__": raise SystemExit(main())
