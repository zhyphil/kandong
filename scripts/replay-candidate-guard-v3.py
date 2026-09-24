#!/usr/bin/env python3
"""Replay fixed raw candidates with guarded Latin selection; no new OCR or raw correction."""
from pathlib import Path
import argparse,gzip,hashlib,importlib.util,json,unicodedata
ROOT=Path(__file__).resolve().parents[1]
def load(name,file):
 s=importlib.util.spec_from_file_location(name,ROOT/'scripts'/file);m=importlib.util.module_from_spec(s);s.loader.exec_module(m);return m
def distance(x,y):
 row=list(range(len(y)+1))
 for i,c in enumerate(x,1):
  nxt=[i]
  for k,z in enumerate(y,1):nxt.append(min(row[k]+1,nxt[k-1]+1,row[k-1]+(c!=z)))
  row=nxt
 return row[-1]
def guard(ch,latin,v2,old):
 before=v2.decide(ch,latin,old.choose)
 has_latin=any('LATIN' in unicodedata.name(c,'') and unicodedata.category(c).startswith('L') for c in ch)
 return dict(origin='REVIEW',raw=None) if before['origin']=='LATIN' and not has_latin else before

def main():
 p=argparse.ArgumentParser(description=__doc__);p.add_argument('--output',type=Path,required=True);a=p.parse_args();out=a.output.resolve()
 assert out!=ROOT and ROOT not in out.parents and not out.exists()
 old=load('old','screen-block-recognizer-selection.py');v2=load('v2','export-candidate-selection-v2.py');norm=old.audit.normalize
 data=[('historical',ROOT/'docs/evidence/shared-box-candidates/2026-09-24/shared-box-report.json','0003f8e56de7c364c04766226af7f9fb58b0ad51a03992bd2ddef1353048b2f1'),
       ('new-two-font',ROOT/'docs/evidence/candidate-holdout-v2/2026-09-24/quality-report.json.gz','78862bb839cda33d9bd11f3200c246812085bd5403dfd75a98cfd8f73330d082')]
 results=[];changed=[];regressions=[];known_good_lost=[];block_count=0
 for dataset,path,sha in data:
  raw=gzip.decompress(path.read_bytes()) if path.suffix=='.gz' else path.read_bytes();assert hashlib.sha256(raw).hexdigest()==sha
  for p in json.loads(raw)['results']:
   rows=[]
   for b in p['blocks']:
    before=v2.decide(b['ch'],b['latin'],old.choose);after=guard(b['ch'],b['latin'],v2,old)
    rows.append(dict(boxId=b['boxId'],ch=b['ch'],latin=b['latin'],v2=before,v3=after))
   def text(key):return '\n'.join(b[key]['raw'] for b in rows) if all(b[key]['raw'] is not None for b in rows) else None
   before,after=text('v2'),text('v3');ch='\n'.join(b['ch'] for b in rows);source=norm(p['source'])
   identity=dict(dataset=dataset,id=p['id'],scale=p['scale'],font=p.get('font'),fontPx=p.get('fontPx'))
   row=dict(**identity,source=p['source'],v2Raw=before,v3Raw=after,blocks=rows,
      chEdits=distance(source,norm(ch)),v2Edits=None if before is None else distance(source,norm(before)),
      v3Edits=None if after is None else distance(source,norm(after)),
      exact=after is not None and norm(after)==source,review=after is None)
   if before!=after:changed.append(identity)
   if after is not None and row['v3Edits']>row['chEdits']:regressions.append(identity)
   if norm(ch)==source and not row['exact']:known_good_lost.append(identity)
   block_count+=len(rows);results.append(row)
 assert len(results)==222
 assert len(changed)==1 and changed[0]==dict(dataset='new-two-font',id='hant-payment',scale=2,font='heiti-medium',fontPx=32)
 assert all(r['v3Raw']==r['v2Raw'] for r in results if not r['review'])
 assert known_good_lost==[] and regressions==[]
 assert block_count==423 and sum(r["review"] for r in results)==1
 report=dict(schema=1,scope='Replay of previously evaluated synthetic candidates, not another blind test or OCR inference',
  ruleFrozenAt='97dc3ef',scriptSha256=hashlib.sha256(Path(__file__).read_bytes()).hexdigest(),pages=len(results),blocks=block_count,
  changedPages=changed,reviewPages=sum(r['review'] for r in results),knownCorrectChPagesLost=known_good_lost,
  readyCharacterRegressionsVsCh=regressions,readyOutputsIdenticalToV2=True,results=results,
  limits=['One uncertain block is retained for review, not corrected into an accepted period','No new quality acceptance; remaining layout and glyph issues unchanged'])
 out.mkdir(parents=True);(out/'guard-replay.json').write_text(json.dumps(report,ensure_ascii=False,indent=2)+'\n')
 print(json.dumps({k:report[k] for k in ['pages','blocks','changedPages','reviewPages','readyCharacterRegressionsVsCh','knownCorrectChPagesLost']},ensure_ascii=False,indent=2))
if __name__=='__main__':main()
