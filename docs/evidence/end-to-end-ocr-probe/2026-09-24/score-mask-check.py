import json,gzip,math,numpy as np
from pathlib import Path
root=Path('/Users/haoyuzuo/Projects/KanDong')
evidence=root/'docs/evidence/end-to-end-ocr-probe/2026-09-24'
p=evidence/'score-budget-diagnosis.json';diag=json.loads(p.read_bytes());report=json.loads((evidence/'score-budget-failure/e2e-1-report.json').read_bytes());manifest=json.loads((root/'docs/fixtures/detector-box-trace-v1/manifest.json').read_bytes())
def footprint(q,w,h):
 xs=[p[0] for p in q];ys=[p[1] for p in q]
 a=max(0,min(w-1,math.floor(min(xs))));b=max(0,min(w-1,math.ceil(max(xs))))
 c=max(0,min(h-1,math.floor(min(ys))));d=max(0,min(h-1,math.ceil(max(ys))))
 return [a,b,c,d]+[v for x,y in q for v in [int(np.float32(x)-np.float32(a)),int(np.float32(y)-np.float32(c))]]
rows=[]
for case in report['cases']:
 ref=json.loads(gzip.decompress((root/'docs/fixtures/detector-box-trace-v1'/f"{case['id']}-trace.json.gz").read_bytes()))
 meta=next(c for c in manifest['cases'] if c['id']==case['id']);h,w=meta['shape'][2:]
 for actual,expected in zip(case['trace']['rowChecks'],ref['rows'],strict=True):
  a=actual['actual']
  if 'score' in a:
   x=footprint(a['preUnclipQuad'],w,h);y=footprint(expected['preUnclipQuad'],w,h);assert x==y
   rows.append(dict(case=case['id'],contour=a['contourIndex'],scoreMaskFootprint=x,exact=True))
diag['scoreMaskFootprints']=rows;assert len(rows)==9
p.write_text(json.dumps(diag,indent=2)+'\n');print('All 9 scoring ROI/polygon footprints equal before applying any propagated bound.')
