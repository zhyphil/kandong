from pathlib import Path
import json,hashlib,re,shutil
root=Path('/Users/haoyuzuo/Projects/KanDong')
raw=Path('/private/tmp/kandong-e2e-device-20260924')
old=root/'docs/evidence/crop-recognition-probe/2026-09-24/huawei'
evidence=root/'docs/evidence/end-to-end-ocr-probe/2026-09-24'
ignored={'runId','elapsedMs','resizeNs','elapsedNanosObservational','elapsedNanos'}
def read(p):return json.loads(p.read_bytes())
def stable(x):
 if isinstance(x,dict):return {k:stable(v) for k,v in x.items() if k not in ignored}
 if isinstance(x,list):return [stable(v) for v in x]
 return x
def passed(x):
 if isinstance(x,dict):
  if 'passed' in x:assert x['passed'] is True,x
  for k in ['differentBytes','differentFloatBits','changedChannels']:
   if k in x:assert x[k]==0,(k,x)
  for v in x.values():passed(v)
 elif isinstance(x,list):
  for v in x:passed(v)
for n,h in read(evidence/'source-sha256.json').items():assert hashlib.sha256((root/n).read_bytes()).hexdigest()==h,n
reports={};runids=[];times={}
for name in ['detector','original','geometry','polygon','boxes','recognition','e2e']:
 rounds=[read(raw/f'{name}-{i}-report.json') for i in [1,2]]
 assert stable(rounds[0])==stable(rounds[1]),name
 if name!='e2e':assert stable(rounds[0])==stable(read(old/f'{name}-1-report.json')),name
 for item in rounds:passed(item);runids.append(item['runId'])
 reports[name]=rounds
assert len(runids)==len(set(runids))==14
for group,count in [('regression',7),('geometry',1),('polygon',1),('boxes',1),('recognition',1),('e2e',2)]:
 times[group]=[]
 for i in [1,2]:
  text=(raw/f'{group}-{i}-instrumentation.txt').read_text()
  assert f'OK ({count} test' in text and 'INSTRUMENTATION_CODE: -1' in text
  assert 'FAILURES' not in text and 'INSTRUMENTATION_FAILED' not in text
  times[group].append(float(re.search(r'Time: ([\d.]+)',text)[1]))
expected={'cases':10,'detectorInferences':10,'empty':5,'crops':9,'cropChannels':116691,'resizedChannels':570960,
 'tensors':5,'floats':631728,'recognitionInferences':10,'rawRows':18,'bindings':18}
fixture=read(root/'docs/fixtures/crop-recognition-v1/manifest.json')
sourceIdentity={c['id']:c for c in read(evidence/'source-identity-diagnosis.json')['cases']}
for report in reports['e2e']:
 assert report['status']=='passed' and report['api']==31 and report['device']=='LIO-AN00'
 assert report['recognitionFixtureSha256']=='bbbfd058777e51e4fb7769764d2b49dac7618c784b8e0ef652af84d973f64f6a'
 assert report['counts']==report['expectedCounts']==expected and report['errors']==[]
 for kind,key in [('cases','expectedCaseIds'),('models','expectedModelIds')]:
  assert [x['id'] for x in report[kind]]==report[key] and len(report[kind])==10
  assert len(set(report[key]))==10 and all(x['status']=='completed' for x in report[kind])
 assert sum(c.get('noRecognitionWork',False) for c in report['cases'])==5
 assert sum(len(c['crops']) for c in report['cases'])==9
 for c in report['cases']:
  identity=sourceIdentity[c['id']]
  assert c['sourcePngSha256']==identity['originalPngSha256']
  assert c['geometrySourcePngSha256']==identity['geometryPngSha256']
  assert c['sourceBgrSha256']==identity['bgrSha256'] and identity['sameBgrPixels']
  assert c['detectorPixelsExact'] and c['detectorInputExact'] and c['detector']['numericParityPassed']
  assert c['detector']['maskFlips']==0
  assert c['scoreBudget']['sameInputAbsoluteTolerance']==1e-7
  assert c['scoreBudget']['measuredInputMaxAbsoluteError']==c['detector']['maxAbsoluteError']
  assert c['scoreBudget']['totalAbsoluteTolerance']==1e-7+c['detector']['maxAbsoluteError']
  for row in c['trace']['rowChecks']:
   if 'score' in row['actual']:assert row['comparisons']['scoreMaskFootprint']==1
  if c['crops']:assert c['tensorExact'] and c['tensorShapeExact'] and c['mappingExact']
 g=report['geometryCleanup'];o=report['ortCleanup']
 assert g['balanced'] and g['matOpened']>0 and g['bitmapOpened']>0
 assert g['matAcquired']==g['matReleaseAttempts']==g['matReleased']
 assert g['bitmapOpened']==g['bitmapRecycleAttempts']==g['bitmapRecycled']
 assert o=={'opened':46,'closeAttempts':46,'closed':46,'uncertain':False,'balanced':True}
 for result in report['models']:
  model,caseid=result['id'].split('/')
  case=next(c for c in fixture['cases'] if c['id']==caseid)
  ref=next(m for m in case['models'] if m['model']==model)
  assert result['raw']==ref['expectedHostRaw'] and result['textMatches'] and result['argmaxMatches'] and result['readingOrderMatches']
  for b,row in zip(result['bindings'],case['rows'],strict=True):
   for k in ['boxId','originalIndex','readingOrder','quad']:assert b[k]==row[k],(result['id'],k)
   caseReport=next(c for c in report['cases'] if c['id']==caseid)
   assert abs(b['detectorScore']-row['detectorScore'])<=1e-7+caseReport['detector']['maxAbsoluteError']
   assert b['tensorRow']==row['tensorRow'] and b['raw']==ref['readingOrderRaw'][row['readingOrder']]
identity=read(evidence/'apk-verification.json');assert read(raw/'installed.json')==identity
summary=dict(status='accepted-synthetic-original-to-ocr-on-target-Huawei',device='LIO-AN00/API31',rounds=2,junitTestsPerRound=13,
 countsPerRound=expected,runIds=runids,allSevenReportsNonTimingRepeat=True,oldSixReportsMatchAcceptedBaseline=True,
 geometryCleanup=reports['e2e'][0]['geometryCleanup'],ortCleanup=reports['e2e'][0]['ortCleanup'],
 excludedComparisonFields=sorted(ignored),instrumentationSecondsObservational=times,
 limitations=['Synthetic inputs only, not trilingual quality acceptance','Known wrong raw text retained','No real screen collection, translation or product integration',
 'Target Huawei only; API36 SIGILL unresolved','Resource counts do not prove long-term memory or battery behavior'])
(raw/'acceptance-summary.json').write_text(json.dumps(summary,ensure_ascii=False,indent=2)+'\n')
dest=evidence/'huawei';dest.mkdir(exist_ok=True)
for p in raw.iterdir():assert p.is_file();shutil.copy2(p,dest/p.name)
print(json.dumps(summary,ensure_ascii=False,indent=2))
