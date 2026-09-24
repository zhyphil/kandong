from pathlib import Path
import json,hashlib,re,shutil
root=Path('/Users/haoyuzuo/Projects/KanDong')
raw=Path('/private/tmp/kandong-crop-recognition-device-20260924')
old=root/'docs/evidence/box-pipeline-probe/2026-09-24/huawei'
evidence=root/'docs/evidence/crop-recognition-probe/2026-09-24'
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
for path in [evidence/'source-sha256.json',Path('/private/tmp/kandong-crop-recognition-android-protected-20260924.json')]:
    for n,h in read(path).items():assert hashlib.sha256((root/n).read_bytes()).hexdigest()==h,n
reports={};runids=[];times={}
for name in ['detector','original','geometry','polygon','boxes','recognition']:
    rounds=[read(raw/f'{name}-{i}-report.json') for i in [1,2]]
    assert stable(rounds[0])==stable(rounds[1]),name
    if name!='recognition':assert stable(rounds[0])==stable(read(old/f'{name}-1-report.json')),name
    for item in rounds:passed(item);runids.append(item['runId'])
    reports[name]=rounds
assert len(runids)==len(set(runids))==12
for group,count in [('regression',7),('geometry',1),('polygon',1),('boxes',1),('recognition',1)]:
    times[group]=[]
    for i in [1,2]:
        text=(raw/f'{group}-{i}-instrumentation.txt').read_text()
        assert f'OK ({count} test' in text and 'INSTRUMENTATION_CODE: -1' in text
        assert 'FAILURES' not in text and 'INSTRUMENTATION_FAILED' not in text
        times[group].append(float(re.search(r'Time: ([\d.]+)',text)[1]))
expected={'cases':16,'complete':16,'empty':8,'crops':13,'cropChannels':139875,'resizes':13,
 'resizedChannels':631008,'tensors':8,'floats':816048,'inferences':16,'rawRows':26,'bindings':26}
fixtures=read(root/'docs/fixtures/crop-recognition-v1/manifest.json')
for report in reports['recognition']:
    assert report['status']=='passed' and report['api']==31 and report['device']=='LIO-AN00'
    assert report['fixtureSha256']=='bbbfd058777e51e4fb7769764d2b49dac7618c784b8e0ef652af84d973f64f6a'
    assert report['counts']==report['expectedCounts']==expected and report['errors']==[]
    for kind,ids,count in [('cases','expectedCaseIds',16),('models','expectedModelIds',16),('guards','expectedGuardIds',15)]:
        actual=report[kind];assert len(actual)==count and [x['id'] for x in actual]==report[ids]
        assert len(set(report[ids]))==count and all(x['status']=='completed' for x in actual)
    g=report['geometryCleanup'];o=report['ortCleanup']
    assert g['balanced'] and g['matOpened']>0 and g['bitmapOpened']>0
    assert g['matAcquired']==g['matReleaseAttempts']==g['matReleased']
    assert g['bitmapOpened']==g['bitmapRecycleAttempts']==g['bitmapRecycled']
    assert o=={'opened':38,'closeAttempts':38,'closed':38,'uncertain':False}
    assert sum(c.get('noInference',False) for c in report['cases'])==8
    assert sum(len(c['crops']) for c in report['cases'])==13
    for model in report['models']:
        name,caseid=model['id'].split('/')
        fixture=next(c for c in fixtures['cases'] if c['id']==caseid)
        reference=next(m for m in fixture['models'] if m['model']==name)
        assert model['raw']==reference['expectedHostRaw']
        assert model['textMatches'] and model['argmaxMatches'] and model['readingOrderMatches']
        for b,row in zip(model['bindings'],fixture['rows'],strict=True):
            for k in ['boxId','originalIndex','readingOrder','quad','detectorScore']:
                assert b[k]==row[k],(model['id'],k)
            assert b['tensorRow']==row['tensorRow'] and b['raw']==reference['readingOrderRaw'][row['readingOrder']]
summary=dict(status='accepted-bounded-synthetic-crop-recognition-on-target-Huawei',device='HUAWEI LIO-AN00/API31',
 rounds=2,junitTestsPerRound=11,countsPerRound=expected,guardsPerRound=15,
 geometryCleanup=reports['recognition'][0]['geometryCleanup'],ortCleanup=reports['recognition'][0]['ortCleanup'],
 allSixReportsNonTimingRepeat=True,oldFiveReportsMatchAcceptedBaseline=True,excludedComparisonFields=sorted(ignored),
 runIds=runids,instrumentationSecondsObservational=times,protectedFiles=1018,sourceFingerprintsMatchBuildAndReview=True,
 installationNote='adb command returned empty failure after waiting; device package hashes prove requested APK installed; no reinstall',
 limitations=['Fixed probability input; fresh detector inference not yet connected','Wrong raw text and nontext guesses retained; no trilingual quality acceptance',
 'No real-screen collection or translation','Huawei only; API36 OpenCV SIGILL unresolved','Resource counts do not establish long-term native memory or battery performance'])
(raw/'acceptance-summary.json').write_text(json.dumps(summary,ensure_ascii=False,indent=2)+'\n')
dest=evidence/'huawei';dest.mkdir(exist_ok=True)
for p in raw.iterdir():assert p.is_file();shutil.copy2(p,dest/p.name)
shutil.copy2(__file__,evidence/'acceptance-check.py')
print(json.dumps(summary,ensure_ascii=False,indent=2))
