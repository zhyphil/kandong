from pathlib import Path
import json,hashlib,gzip,re,shutil
from xml.etree import ElementTree as E
root=Path('/Users/haoyuzuo/Projects/KanDong');raw=Path('/private/tmp/kandong-candidate-device-20260924')
validation=Path('/private/tmp/kandong-candidate-validation-20260924')
old=root/'docs/evidence/ocr-emulator/2026-09-24'
out=root/'docs/evidence/candidate-selection-v2/2026-09-24';out.mkdir(parents=True,exist_ok=True)
read=lambda p:json.loads(p.read_bytes())
ignore={'runId','elapsedMs','resizeNs','elapsedNanosObservational','elapsedNanos','freshE2eRunId','freshE2eReportSha256'}
def stable(x):
 if isinstance(x,dict):return {k:stable(v) for k,v in x.items() if k not in ignore}
 if isinstance(x,list):return [stable(v) for v in x]
 return x
def passed(x):
 if isinstance(x,dict):
  if 'passed' in x:assert x['passed'] is True
  for k in ['differentBytes','differentFloatBits','changedChannels']:
   if k in x:assert x[k]==0
  for v in x.values():passed(v)
 elif isinstance(x,list):
  for v in x:passed(v)
reports={};ids=[]
for kind in ['detector','original','geometry','polygon','boxes','recognition','e2e','selection']:
 rounds=[read(raw/f'{kind}-{n}-report.json') for n in [1,2]]
 assert stable(rounds[0])==stable(rounds[1]),kind
 if kind!='selection':assert stable(rounds[0])==stable(read(old/f'{kind}-1-report.json')),kind
 for r in rounds:passed(r);ids.append(r['runId'])
 reports[kind]=rounds
assert len(set(ids))==len(ids)==16
for group,count in [('e2e',2),('regression',7),('geometry',1),('polygon',1),('boxes',1),('recognition',1),('selection',1)]:
 for n in [1,2]:
  text=(raw/f'{group}-{n}-instrumentation.txt').read_text();assert f'OK ({count} test' in text and 'INSTRUMENTATION_CODE: -1' in text
  assert 'FAILURES' not in text and 'INSTRUMENTATION_FAILED' not in text
for i,r in enumerate(reports['selection']):
 assert r['status']=='passed' and r['errors']==[]
 assert (r['frozenCases'],r['frozenBlocks'],r['freshBlocks'],r['emptyLatinCandidatesRetained'],r['blankCases'],r['controls'])==(98,180,9,27,4,8)
 assert r['freshE2eRunId']==reports['e2e'][i]['runId']
 assert len(r['cases'])==98 and len(r['freshCases'])==5
 e2e=reports['e2e'][i]
 for case in r['freshCases']:
  for model in ['ch','latin']:
   source=next(m for m in e2e['models'] if m['id']==model+'/'+case['id'])
   for actual,orig in zip(case['blocks'],source['bindings'],strict=True):
    for key in ['boxId','readingOrder','quad']:assert actual[key]==orig[key]
    assert actual[model]==orig['raw']
    src=next(s for s in e2e['cases'] if s['id']==case['id']);assert actual['pageId']==src['sourceBgrSha256']
fixtures=root/'modelprobe/src/androidTest/assets/shared-candidates-v2.json'
assert hashlib.sha256(fixtures.read_bytes()).hexdigest()==reports['selection'][0]['fixtureSha256']
unit=[E.parse(f).getroot() for f in (root/'modelprobe/build/test-results/testDebugUnitTest').glob('TEST-*.xml')]
assert sum(int(u.get('tests')) for u in unit)==69 and all(u.get('failures')==u.get('errors')=='0' for u in unit)
identity=read(validation/'apk-identity.json');assert read(raw/'installed.json')==identity
source={str(p.relative_to(root)):hashlib.sha256(p.read_bytes()).hexdigest() for p in [
 root/'scripts/export-candidate-selection-v2.py',root/'modelprobe/src/testShared/java/com/kandong/modelprobe/SharedCandidates.kt',
 root/'modelprobe/src/test/java/com/kandong/modelprobe/SharedCandidatesTest.kt',root/'modelprobe/src/androidTest/java/com/kandong/modelprobe/CandidateSelectionProbeTest.kt',fixtures]}
archived={}
for p in raw.iterdir():
 if p.suffix=='.json':
  target=out/(p.name+'.gz');target.write_bytes(gzip.compress(p.read_bytes(),mtime=0))
  archived[p.name]=dict(file=target.name,sha256=hashlib.sha256(p.read_bytes()).hexdigest())
 else:shutil.copy2(p,out/p.name)
for name in ['red-test.log','red-test.xml','apk-identity.json','baseline-asset-native-hashes.json']:shutil.copy2(validation/name,out/name)
for name in ['green','build']:
 shutil.copy2(f'/private/tmp/kandong-candidate-{name}-20260924.log',out/f'{name}.log')
for f in (root/'modelprobe/build/test-results/testDebugUnitTest').glob('TEST-*.xml'):
 dest=out/'junit';dest.mkdir(exist_ok=True);shutil.copy2(f,dest/f.name)
shutil.copy2('/private/tmp/kandong-run-candidate-validation-20260924.py',out/'executed-runner.py')
shutil.copy2('/private/tmp/kandong-candidate-v2-a-20260924/quality-v2.json',out/'quality-v2.json')
summary=dict(status='accepted-independent-candidate-selection-experiment',rounds=2,junitPerRound=14,unitTests=69,
 fixedPages=98,fixedBlocks=180,retainedEmptyLatin=27,freshPages=5,freshBlocks=9,controls=8,api=37,
 priorSevenReportsUnchanged=True,eightReportTypesRepeat=True,runIds=ids,excludedFields=sorted(ignore),apk=identity,
 sourceSha256=source,rawReports=archived,qualityAccepted=False,
 limits=['Frozen candidates are not 98 new Android image inferences','Fresh nine boxes come from separately executed same-package E2E, bound by runId and on-device raw-report SHA check',
 'Same fonts and authored corpus; unseen/multifont data pending','No real screens, translation, product integration or phone actions'])
(out/'acceptance-summary.json').write_text(json.dumps(summary,ensure_ascii=False,indent=2)+'\n')
print(json.dumps({k:summary[k] for k in ['status','rounds','junitPerRound','unitTests','fixedBlocks','freshBlocks','priorSevenReportsUnchanged']},indent=2))
