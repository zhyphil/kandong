#!/usr/bin/env python3
"""Evaluate the v2 rule frozen at c5d61a2 and export test-only candidate fixtures."""
import argparse, hashlib, importlib.util, json
from pathlib import Path
ROOT=Path(__file__).resolve().parents[1]
PARENT='0003f8e56de7c364c04766226af7f9fb58b0ad51a03992bd2ddef1353048b2f1'
def sha(b): return hashlib.sha256(b).hexdigest()
def decide(ch, latin, old):
    if ch and ch==latin: return dict(origin='AGREED',raw=ch)
    result=old(ch,latin)
    return dict(origin={'ch':'CH','latin':'LATIN',None:'REVIEW'}[result['model']],raw=result['raw'])
def main():
    p=argparse.ArgumentParser(description=__doc__);p.add_argument('--output',type=Path,required=True)
    out=p.parse_args().output.resolve();assert out!=ROOT and ROOT not in out.parents and not out.exists()
    spec=importlib.util.spec_from_file_location('selection',ROOT/'scripts/screen-block-recognizer-selection.py')
    old=importlib.util.module_from_spec(spec);spec.loader.exec_module(old)
    data=(ROOT/'docs/evidence/shared-box-candidates/2026-09-24/shared-box-report.json').read_bytes();assert sha(data)==PARENT
    parent=json.loads(data);fixture=[];quality=[]
    for page in parent['results']:
        rows=[]
        for index,b in enumerate(page['blocks']):
            d=decide(b['ch'],b['latin'],old.choose)
            rows.append(dict(boxId=b['boxId'],readingOrder=index,quad=b['quad'],ch=b['ch'],latin=b['latin'],expected=d))
        raw='\n'.join(r['expected']['raw'] for r in rows) if all(r['expected']['raw'] is not None for r in rows) else None
        fixture.append(dict(id=f"{page['dataset']}/{page['id']}/{page['scale']}",pageId=page['pixelSha256'],rows=rows,expectedRaw=raw))
        quality.append(dict(dataset=page['dataset'],id=page['id'],scale=page['scale'],source=page['source'],raw=raw,
            textImage=page['textImage'],chExact=page['chExact'],selectedExact=raw is not None and old.audit.normalize(raw)==old.audit.normalize(page['source'])))
    groups=[]
    for name in ['baseline72','holdout124']:
        for scale in [1,2]:
            pages=[r for r in quality if r['dataset']==name and r['scale']==scale];text=[r for r in pages if r['textImage']]
            groups.append(dict(dataset=name,scale=scale,textImages=len(text),chExact=sum(r['chExact'] for r in text),selectedExact=sum(r['selectedExact'] for r in text),
                regressions=[r['id'] for r in text if r['chExact'] and not r['selectedExact']],review=[r['id'] for r in text if r['raw'] is None],
                remainingFailures=[r['id'] for r in text if not r['selectedExact']],blanksPassed=all(r['raw']=='' for r in pages if not r['textImage'])))
    controls=[('18:00.','18:00.','AGREED','18:00.'),('8','9','REVIEW',None),('','','REVIEW',None),
              ('€','€','AGREED','€'),('行李','','CH','行李'),('Ce bilet','Ce billet','LATIN','Ce billet'),
              ('税','稅','CH','税'),('e\u0301','é','LATIN','é')]
    for ch,latin,origin,raw in controls: assert decide(ch,latin,old.choose)==dict(origin=origin,raw=raw)
    assert len(fixture)==98 and sum(len(p['rows']) for p in fixture)==180
    result=dict(schema=1,rule='v2-agreement-before-glyph',ruleFrozenAt='c5d61a2',parentSha256=PARENT,
        scriptSha256=sha(Path(__file__).read_bytes()),cases=fixture,
        controls=[dict(ch=a,latin=b,expected=dict(origin=o,raw=r)) for a,b,o,r in controls])
    summary=dict(schema=1,scope='Same synthetic historical corpus; selection experiment only',parentSha256=PARENT,groups=groups,results=quality,
        qualityAccepted=False,eligibleForAndroidSelectionExperiment=all(not g['regressions'] and g['blanksPassed'] and g['selectedExact']>=g['chExact'] for g in groups),
        limitations=['No unseen data or additional fonts','Matching strings need not be correct','Known mixed-script and traditional glyph differences remain'])
    out.mkdir(parents=True)
    for file,obj in [('shared-candidates-v2.json',result),('quality-v2.json',summary)]:
        (out/file).write_text(json.dumps(obj,ensure_ascii=False,indent=2,allow_nan=False)+'\n')
    print(json.dumps(groups,ensure_ascii=False,indent=2));print('fixture',sha((out/'shared-candidates-v2.json').read_bytes()))
if __name__=='__main__':main()
