"""Frozen Qwen3.5 host candidate. No Android, screen access, or remote inference."""
import collections
import hashlib
import json
import re
from translation_probe import ROOT,load_cases
from translation_probe_v2 import request_body as previous_request,validate as previous_validate

MODEL='qwen3.5:2b-q4_K_M'
MANIFEST='124a03c347777e8e4e5955c33610ae01d9d90d8c2a718bfba069c498d5c7f3c9'
FIXTURES={
    'translation-holdout-v2.json':'e3446777b269cd3fa2f8f5fe8bdc9080079b23e047b24c2d8f41a154f9229955',
    'translation-holdout-v3.json':'6bef7db7a270c44d90cf45d72ed54cf139e5d71eb91a1d25560a74b4b4715bc6',
}

def cases():
    result=load_cases()
    for name,digest in FIXTURES.items():
        p=ROOT/'docs/fixtures'/name
        if hashlib.sha256(p.read_bytes()).hexdigest()!=digest:raise ValueError('Fixture changed')
        data=json.loads(p.read_text())
        if not data.get('synthetic'):raise ValueError('Synthetic pages only')
        result+=data['cases']
    return result

def request_body(case, full_context, thinking=False):
    r=previous_request(case,full_context)
    r['model']=MODEL;r['think']=thinking
    if thinking:
        r['options'].update(temperature=0.6,top_p=0.95,top_k=20,min_p=0.0,presence_penalty=0.0,repeat_penalty=1.0,num_predict=4096)
    return r

def literal_issues(source, translated):
    if translated is None:return []
    def numbers(text):
        # Integer zero-padding only. Decimal separators and fractions are deliberately not normalized.
        return collections.Counter(str(int(x)) if x.isdigit() else x for x in re.findall(r'\d+(?:[.,]\d+)*',text))
    missing=numbers(source)-numbers(translated)
    symbols={c for c in source if c in '$€£¥'}-{c for c in translated if c in '$€£¥'}
    return (['missing-source-number'] if missing else [])+(['missing-currency-symbol'] if symbols else [])

def validate(case,raw):
    result=previous_validate(case,raw)
    for b in result:
        b['previousLiteralIssues']=b['literalIssues']
        b['literalIssues']=literal_issues(b['source']['text'],b['text'])
        b['status']='uncertain' if b['text'] is None else 'rejected-literal' if b['literalIssues'] else 'candidate'
    return result
