#!/usr/bin/env python3
"""Dry-run by default; one bounded DeepL synthetic comparison when explicitly selected."""
import argparse
import hashlib
import json
import time
from pathlib import Path
from translation_candidate_c import cases
from translation_probe import ROOT, preserve_chinese
from translation_deepl import DeepLClient, DeepLError, request_body, remaining_characters
from deepl_credentials import load_key


def save(path, value): path.write_text(json.dumps(value, ensure_ascii=False, indent=2) + "\n")


def main():
    p = argparse.ArgumentParser()
    p.add_argument("--out", type=Path, required=True)
    p.add_argument("--execute-synthetic", action="store_true")
    p.add_argument("--rounds", type=int, choices=(1, 2), default=2)
    p.add_argument("--max-source-characters", type=int, default=10000)
    args = p.parse_args()
    if not 1 <= args.max_source_characters <= 10000: p.error("character budget must be 1..10000")
    rows = []
    for n in range(1, args.rounds + 1):
        for c in cases():
            if c["sourceLanguage"].startswith("zh"): continue
            for target in c["targetIds"]:
                for mode in ("full", "target-only"):
                    body = request_body(c["id"], target, mode == "full")
                    rows.append({"round": n, "page": c["id"], "target": target, "mode": mode,
                                 "request": body, "sourceCharacters": len(body["text"][0])})
    chars = sum(r["sourceCharacters"] for r in rows)
    if chars > args.max_source_characters: p.error("planned source characters exceed budget")
    args.out.mkdir(parents=True, exist_ok=False)
    plan = {"syntheticOnly": True, "calls": len(rows), "sourceCharacters": chars, "rows": rows,
            "qualityAccepted": False, "contextRepresentation": "whole-page-json-v1-one-target-per-request"}
    save(args.out / "plan.json", plan)
    save(args.out / "chinese-local.json", [preserve_chinese(c) for c in cases() if c["sourceLanguage"].startswith("zh")])
    save(args.out / "source-hashes.json", {n: hashlib.sha256((ROOT / "scripts" / n).read_bytes()).hexdigest() for n in
        ["translation_deepl.py", "run-translation-deepl.py", "translation_online_contract.py", "translation_candidate_c.py",
         "translation_candidate_v3.py", "translation_probe.py", "translation_probe_v2.py"]})
    print(json.dumps({"plannedCalls": len(rows), "sourceCharacters": chars, "execute": args.execute_synthetic}), flush=True)
    if not args.execute_synthetic: return 0
    completed = 0; attempted_chars = 0; client = None
    try:
        client = DeepLClient(load_key())
        usage = client.usage(); save(args.out / "usage-before.json", usage)
        # User selected the free Developer plan. Refuse an unbounded/unknown paid-plan limit.
        remaining = remaining_characters(usage)
        if usage["character_limit"] > 1000000: raise DeepLError("PLAN_LIMIT_NEEDS_REVIEW")
        if remaining < chars: raise DeepLError("INSUFFICIENT_QUOTA")
        for i, row in enumerate(rows):
            start = time.monotonic(); attempted_chars += row["sourceCharacters"]
            # No automatic retry: a timeout may already have consumed this request's quota.
            item = client.translate(row["page"], row["target"], row["mode"] == "full")
            save(args.out / f"response-{i:03}.json", {**row, **item, "seconds": time.monotonic() - start})
            completed += 1
            print(json.dumps({"completed": completed, "of": len(rows), "seconds": round(time.monotonic() - start, 2)}), flush=True)
        save(args.out / "usage-after.json", client.usage())
        save(args.out / "status.json", {"status": "completed", "completed": completed, "attemptedSourceCharacters": attempted_chars,
                                       "qualityAccepted": False, "requiresSemanticReview": True})
        return 0
    except (DeepLError, ValueError, OSError) as error:
        code = str(error) if isinstance(error, DeepLError) else type(error).__name__
        save(args.out / "status.json", {"status": "stopped", "completed": completed,
            "attemptedSourceCharacters": attempted_chars, "error": code, "retried": False, "qualityAccepted": False})
        print("Stopped: " + code, flush=True)
        return 1

if __name__ == "__main__": raise SystemExit(main())
