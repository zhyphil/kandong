"""Replay only archived synthetic translations against the current limited gate. No network."""
import argparse
import gzip
import hashlib
import json
from collections import Counter
from pathlib import Path
from translation_critical_checks import guarded_display, VERSION
from translation_deepl_regression import case_by_id
from translation_probe import ROOT

INPUTS = (
    ('deepl-json','docs/evidence/deepl-translation/2026-09-24/json-context-run1','semantic-review.json'),
    ('deepl-plain','docs/evidence/deepl-translation/2026-09-24/plain-context-run1','semantic-review.json'),
    ('local-C','docs/evidence/translation-next/2026-09-24/C','manual-review.json'),
    ('local-D','docs/evidence/translation-next/2026-09-24/D','manual-review.json'),
    ('deepl-full','docs/evidence/translation-critical/2026-09-24/live','../v2/semantic-review.json'),
)


def replay():
    rows=[];hashes={}
    for name,path,review_name in INPUTS:
        folder=ROOT/path
        archive=folder/'responses.jsonl.gz';review_file=(folder/review_name).resolve()
        for f in [archive,review_file]:hashes[str(f.relative_to(ROOT))]=hashlib.sha256(f.read_bytes()).hexdigest()
        review=json.loads(review_file.read_text())
        prior={(r['page'],r.get('target',r.get('id'))):r for r in review['rows'] if r.get('round',1)==1}
        records=[json.loads(l) for l in gzip.decompress(archive.read_bytes()).splitlines()]
        for r in records:
            if r.get('round',1)!=1 or r.get('mode','full') not in ('full','plain-full'):continue
            c=case_by_id(r['page'])
            if c['sourceLanguage'].startswith('zh'):continue
            values=r.get('bound',[])
            if isinstance(values,dict):values=[values]
            for b in values:
                source=next(v for v in c['blocks'] if v['id']==b['id'])
                assert b['source']==source and b['pageId']==c['id']
                reviewed=prior[(c['id'],b['id'])]
                expected_text=reviewed.get('translation',reviewed.get('plainContext'))
                assert b['text']==expected_text
                result=guarded_display(b,c['sourceLanguage'])
                rows.append({'run':name,'page':c['id'],'target':b['id'],'source':b['source']['text'],
                             'translation':b['text'],'previousJudgment':reviewed['judgment'],
                             'state':result['state'],'reasons':result['check']['reasons'],
                             'display':result})
    summary={}
    for name,_,_ in INPUTS:
        data=[r for r in rows if r['run']==name]
        summary[name]={'targets':len(data),'matrix':dict(Counter(r['previousJudgment']+'/'+r['state'] for r in data))}
    hashes['scripts/translation_critical_checks.py']=hashlib.sha256((ROOT/'scripts/translation_critical_checks.py').read_bytes()).hexdigest()
    return {'version':VERSION,'syntheticOnly':True,'networkCalls':0,'scope':'first-round complete source-bound targets only',
            'semanticAcceptance':False,'inputHashes':hashes,'summary':summary,'rows':rows}


if __name__=='__main__':
    p=argparse.ArgumentParser();p.add_argument('--out',type=Path,required=True);a=p.parse_args()
    data=replay()
    with a.out.open('x') as f:json.dump(data,f,ensure_ascii=False,indent=2);f.write('\n')
    print(json.dumps(data['summary'],ensure_ascii=False,indent=2))
