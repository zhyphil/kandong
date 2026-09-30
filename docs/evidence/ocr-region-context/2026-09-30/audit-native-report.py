from pathlib import Path
import json,math,argparse
p=argparse.ArgumentParser();p.add_argument('--report',type=Path,required=True);p.add_argument('--baseline',type=Path,required=True);p.add_argument('--output',type=Path,required=True);a=p.parse_args()
r=json.loads(a.report.read_text());base=json.loads(a.baseline.read_text())
assert r['passed'] and len(r['pages'])==2 and r['fixtureSha256']==base['fixtureSha256']
def short(s):return s.split('/',1)[1]
def intersect(a,b):
 c=[max(a[0],b[0]),max(a[1],b[1]),min(a[2],b[2]),min(a[3],b[3])]
 return c if c[2]>c[0] and c[3]>c[1] else None
def close(a,b):return len(a)==len(b) and all(math.isclose(x,y,rel_tol=0,abs_tol=1e-6) for x,y in zip(a,b))
raw={short(c['id']):c for c in base['rawCandidates']}
groups={short(i):{short(m) for m in g['memberIds']} for g in base['association']['groups'] for i in g['memberIds']}
checks=[]
for page in r['pages']:
 assert page['passed'] and page['cleanupBalanced'] and page['scopeSucceeded'] and page['threadsRestored']
 assert page['frameCloseAttempts']==page['frameClosed']==1
 assert page['sessionsOpened']==page['sessionsClosed']==2 and page['liveSessions']==0 and page['peakSimultaneousSessions']==2
 assert page['ortResourcesOpened']==page['ortCloseAttempts']==page['ortResourcesClosed']
 assert page['allProvenanceFieldsPreserved'] and page['spatialMembersComplete'] and page['noProjectionInference']
 assert page['ttlMillis']==60000
 for name in ['projection','pannedProjection','movedProjection']:
  v=page[name];roi=v['roi'];scale=v['scale'];w,h=v['mirrorSize'];px,py=v['panSourcePixels']
  assert 1<=scale<=5 and 0<=px<=max(0,roi[2]-roi[0]-w/scale) and 0<=py<=max(0,roi[3]-roi[1]-h/scale)
  if page['pageFixtureId']=='blank':
   assert all(v[k]==[] for k in ['selectedIds','contextIds','unsupportedIds','mirror']);continue
  selected=[];mirrors={};contexts=set()
  for cid,c in raw.items():
   q=c['pageQuad'];box=[min(x[0] for x in q),min(x[1] for x in q),max(x[0] for x in q),max(x[1] for x in q)]
   cut=intersect(box,roi)
   if cut is None:continue
   selected.append(cid);contexts.update(groups[cid])
   mapped=[(cut[0]-roi[0]-px)*scale,(cut[1]-roi[1]-py)*scale,(cut[2]-roi[0]-px)*scale,(cut[3]-roi[1]-py)*scale]
   mirror=intersect(mapped,[0,0,w,h])
   if mirror:mirrors[cid]=(box,cut,mirror)
  assert set(selected)=={short(i) for i in v['selectedIds']}
  assert contexts=={short(i) for i in v['contextIds']}
  assert set(mirrors)=={short(i['id']) for i in v['mirror']}
  for m in v['mirror']:
   box,cut,mapped=mirrors[short(m['id'])]
   assert close(box,m['sourceEnvelope']) and close(cut,m['sourceIntersection']) and close(mapped,m['mirrorRect'])
  checks.append({'projection':name,'selected':len(selected),'visible':len(mirrors),'spatialContextMembers':len(contexts)})
 if page['pageFixtureId']=='blank':
  assert page['blankComplete'] and page['expiryRevoked'] and page['expiredAtCheckMillis']-page['acquiredAtMillis']>=60000
 else:
  assert page['pageFixtureId']=='hant-seam' and page['sourceCandidateCount']==len(raw)==16
  assert page['rawTaxConflictRetained'] and page['clearRevoked']
assert r['ortCleanup']['opened']==r['ortCleanup']['closed']==r['ortCleanup']['closeAttempts']==56
assert r['transportCleanup']['imagesAcquired']==r['transportCleanup']['imagesClosed']==2
out={'passed':True,'runId':r['runId'],'projectionCoordinateChecks':checks,'fixedFrames':2,'detectorInvocations':sum(p['detectorInvocations'] for p in r['pages']),'recognitionInvocations':sum(p['recognitionInvocations'] for p in r['pages']),'originalExpiryObservedMillis':r['pages'][1]['expiredAtCheckMillis']-r['pages'][1]['acquiredAtMillis'],'note':'Coordinate/ROI/group check uses previously accepted identical fixed geometry as independent reference. Same-inference all-field equality is asserted in native test. No real capture, UI or translation quality claim.'}
a.output.write_text(json.dumps(out,ensure_ascii=False,indent=2)+'\n');print(json.dumps(out,ensure_ascii=False))
