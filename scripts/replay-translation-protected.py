"""Offline evidence/ownership audit. Reads only fixed synthetic artifacts, never keys."""
import argparse
import gzip
import hashlib
import json
from collections import Counter
from pathlib import Path
from translation_deepl_protected import cases, plan, bind_response
from translation_deepl_regression import request_body as previous_request
from translation_protected_facts import prepare, display
from translation_probe import ROOT, preserve_chinese


def read(path): return json.loads(path.read_text())


def read_lines(path):
    if path.exists():
        return [json.loads(line) for line in path.read_text().splitlines()]
    with gzip.open(str(path)+'.gz','rt') as stream:
        return [json.loads(line) for line in stream]


def audit(run):
    frozen=read(run/'plan.json'); assert frozen==plan(), 'Plan drift'
    hashes=read(run/'source-hashes.json')
    for name,digest in hashes.items():
        assert hashlib.sha256((ROOT/name).read_bytes()).hexdigest()==digest, f'Source drift: {name}'
    raw=read_lines(run/'responses.jsonl')
    stored=read_lines(run/'displays.jsonl')
    assert len(raw)==len(stored)==frozen['calls'], 'Incomplete live run'
    fresh={};billed=0
    for requested,row,saved in zip(frozen['rows'],raw,stored):
        assert all(row[k]==v for k,v in requested.items()), 'Request or order drift'
        result=bind_response(row['page'],row['target'],row['response'])
        assert saved=={'round':row['round'],'display':result}, 'Display drift'
        fresh[row['round'],row['page'],row['target']]=result
        billed+=row['response']['translations'][0]['billed_characters']
    before=read(run/'usage-before.json');after=read(run/'usage-after.json')
    assert after['character_count']-before['character_count']==billed, 'Usage differs from reported billing'
    assert read(run/'status.json')=={'status':'completed','completed':36,'attempted':36,'billedCharacters':billed,'qualityAccepted':False}
    assert read(run/'chinese-local.json')==[preserve_chinese(c) for c in cases() if c['sourceLanguage'].startswith('zh')]

    prior_path=ROOT/'docs/evidence/translation-critical/2026-09-24/live/responses.jsonl.gz'
    with gzip.open(prior_path,'rt') as stream:prior=[json.loads(line) for line in stream]
    prior_index={(r['round'],r['page'],r['target']):r for r in prior}
    assert len(prior_index)==len(prior)==112
    merged=[]
    for n in (1,2):
        for c in cases():
            if c['sourceLanguage'].startswith('zh'):continue
            for b in c['blocks']:
                if b['id'] not in c['targetIds']:continue
                route=prepare(b['text'],c['sourceLanguage'])['route']
                if route=='plain':
                    old=prior_index[n,c['id'],b['id']]
                    assert old['bound']['source']==b
                    assert old['request']==previous_request(c['id'],b['id'])
                    result=display(b,c['id'],c['sourceLanguage'],old['bound']['text'])
                    evidence='reused-prior-plain-response'
                elif route=='protected-money':
                    result=fresh[n,c['id'],b['id']];evidence='new-protected-money-response'
                else:
                    result=display(b,c['id'],c['sourceLanguage']);evidence='local-source-rule'
                assert result['source']==b and result['id']==b['id'] and result['pageId']==c['id']
                assert result['check']['semanticVerified'] is False
                merged.append({'round':n,'evidence':evidence,'cohort':'new16' if int(c['id'][1:])>=500 else 'prior56','display':result})
    counts={}
    for n in (1,2):
        counts[n]={}
        for cohort in ('prior56','new16'):
            counts[n][cohort]=dict(Counter(r['display']['state'] for r in merged if r['round']==n and r['cohort']==cohort))
    first=[r for r in merged if r['round']==1];second=[r for r in merged if r['round']==2]
    assert len(first)==len(second)==72
    return {'syntheticOnly':True,'qualityAccepted':False,'liveCalls':len(raw),'billedCharacters':billed,
            'priorEvidence':str(prior_path.relative_to(ROOT)),'priorSha256':hashlib.sha256(prior_path.read_bytes()).hexdigest(),
            'priorResponsesReused':80,'localDatesRendered':20,'unsupportedDatesRetained':8,
            'perRound':counts,'pairsWithSameDisplayText':sum(a['display']['displayText']==b['display']['displayText'] for a,b in zip(first,second)),
            'pairsWithSameState':sum(a['display']['state']==b['display']['state'] for a,b in zip(first,second)),
            'rows':merged}


def main():
    p=argparse.ArgumentParser();p.add_argument('--run-dir',type=Path,required=True);p.add_argument('--out',type=Path,required=True)
    args=p.parse_args();result=audit(args.run_dir)
    with args.out.open('x') as stream:json.dump(result,stream,ensure_ascii=False,indent=2);stream.write('\n')
    print(json.dumps({k:v for k,v in result.items() if k!='rows'},ensure_ascii=False))


if __name__=='__main__':main()
