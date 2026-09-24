"""Offline export of recorded SYNTHETIC outcomes; never invokes a translator or reads keys."""
import argparse
import gzip
import hashlib
import json
from pathlib import Path
from translation_deepl_protected import cases
from translation_probe import ROOT

EVIDENCE = 'docs/evidence/translation-protected/2026-09-24/audit.json.gz'
EVIDENCE_SHA = '390ed92b09a22157dca0782dde2906a51907f3ba8bada6929e36be0b2bae9772'
PAGE_IDS = ('p303', 'p106', 'p304', 'p007', 'p008')


def fixture():
    raw = (ROOT/EVIDENCE).read_bytes()
    if hashlib.sha256(raw).hexdigest() != EVIDENCE_SHA: raise ValueError('Evidence changed')
    audit = json.loads(gzip.decompress(raw))
    assert audit['syntheticOnly'] and not audit['qualityAccepted']
    rows = {(r['display']['pageId'], r['display']['id']): r['display'] for r in audit['rows'] if r['round'] == 1}
    pages = {c['id']: c for c in cases()}
    result = []
    for identifier in PAGE_IDS:
        page = pages[identifier]; answers = []
        for block in page['blocks']:
            key = (identifier, block['id'])
            outcome = dict(id=block['id'], chinese=None, kind='KEEP_ORIGINAL', origin='SOURCE', reason='NO_RECORDED_RESULT')
            if page['sourceLanguage'].startswith('zh'):
                outcome['reason'] = 'ALREADY_CHINESE'
            elif key in rows:
                row = rows[key]; assert row['source'] == block and not row['check']['semanticVerified']
                if row['state'] == 'original-with-warning':
                    assert row['translation'] is None and row['displayText'] == block['text']
                    outcome['reason'] = 'CHECK_UNVERIFIED'
                else:
                    kind, origin = ('LOCAL_DATE', 'LOCAL_DATE_RULE') if row['state'] == 'local-date-rendered' else ('CANDIDATE', 'RECORDED_DEEPL')
                    outcome.update(chinese=row['displayText'], kind=kind, origin=origin, reason=None)
            answers.append(outcome)
        result.append(dict(source=page, answers=answers))
    return dict(schema=1, syntheticOnly=True, qualityAccepted=False, evidence=EVIDENCE,
                evidenceSha256=EVIDENCE_SHA, pages=result)


if __name__ == '__main__':
    parser = argparse.ArgumentParser(); parser.add_argument('--out', type=Path, required=True)
    args = parser.parse_args()
    raw = (json.dumps(fixture(), ensure_ascii=False, indent=2)+'\n').encode()
    with args.out.open('xb') as stream: stream.write(raw)
    print(json.dumps(dict(bytes=len(raw), sha256=hashlib.sha256(raw).hexdigest(), pages=len(PAGE_IDS))))
