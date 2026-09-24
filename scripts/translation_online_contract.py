"""Provider-neutral, text-only export of PINNED SYNTHETIC pages; no network or keys.

This is not a real-screen sanitizer or a translation provider. Android snapshots
must never be passed here: callers select an opaque fixture ID, not arbitrary text.
"""
import copy
import json
from translation_candidate_c import SYSTEM, cases
from translation_probe import bind, reject_duplicate_keys, preserve_chinese

MAX_RESPONSE_BYTES = 32768

def case_by_id(page_id):
    matches = [c for c in cases() if c["id"] == page_id]
    if len(matches) != 1:
        raise ValueError("Unknown synthetic page")
    return matches[0]


def prepare(page_id, full_context=True):
    case = case_by_id(page_id)
    if case["sourceLanguage"].startswith("zh"):
        return {"action": "preserve-local", "bound": preserve_chinese(case)}
    # Copy only specified public test fields, never raw OCR evidence or an arbitrary snapshot.
    fields = ("id", "text", "role", "groupId", "bounds", "readingOrder")
    blocks = [{k: copy.deepcopy(b[k]) for k in fields} for b in case["blocks"]
              if full_context or b["id"] in case["targetIds"]]
    ids = {b["id"] for b in blocks}
    groups = [{"id": g["id"], "blockIds": [i for i in g["blockIds"] if i in ids]}
              for g in case["groups"] if any(i in ids for i in g["blockIds"])]
    targets = {b["id"]: b["text"] for b in case["blocks"] if b["id"] in case["targetIds"]}
    data = {"targets": targets, "screen": {
        "sourceLanguage": case["sourceLanguage"], "targetLanguage": "zh-CN",
        "viewport": copy.deepcopy(case["viewport"]), "blocks": blocks,
        "groups": groups, "targetIds": list(case["targetIds"]),
    }}
    schema = {"type": "object", "properties": {i: {"type": ["string", "null"]} for i in targets},
              "required": list(targets), "additionalProperties": False}
    return {"action": "candidate-request", "system": SYSTEM, "data": data, "responseSchema": schema}


def accept(page_id, raw):
    case = case_by_id(page_id)
    if case["sourceLanguage"].startswith("zh"):
        raise ValueError("Chinese remains local")
    if not isinstance(raw, str) or len(raw.encode("utf-8")) > MAX_RESPONSE_BYTES:
        raise ValueError("Invalid response size")
    obj = json.loads(raw, object_pairs_hook=reject_duplicate_keys)
    if not isinstance(obj, dict) or set(obj) != set(case["targetIds"]):
        raise ValueError("Unknown or missing target IDs")
    # Source text, geometry, and card membership always come from the frozen input.
    # A structurally accepted candidate has NOT passed semantic quality evaluation.
    return bind(case, json.dumps({"translations": [{"id": i, "text": t} for i, t in obj.items()]}, ensure_ascii=False))


def plan():
    rows = []
    for case in cases():
        for mode in (["original"] if case["sourceLanguage"].startswith("zh") else ["full", "target-only"]):
            rows.append({"page": case["id"], "mode": mode, "prepared": prepare(case["id"], mode == "full")})
    return {"syntheticOnly": True, "networkCalls": 0, "provider": None,
            "purpose": "development regression; no new blind holdout or semantic pass", "rows": rows}


if __name__ == "__main__":
    import argparse
    from pathlib import Path
    parser = argparse.ArgumentParser(description="Prepare a local synthetic request plan; NEVER calls a service")
    parser.add_argument("--out", type=Path, required=True)
    args = parser.parse_args()
    # Refuse to overwrite prior evidence.
    with args.out.open("x") as output:
        json.dump(plan(), output, ensure_ascii=False, indent=2)
        output.write("\n")
