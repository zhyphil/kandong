from pathlib import Path
import json, hashlib, re, shutil

root=Path('/Users/haoyuzuo/Projects/KanDong')
raw=Path('/private/tmp/kandong-box-pipeline-device-20260924')
old=root/'docs/evidence/polygon-offset-probe/2026-09-24/huawei'
evidence=root/'docs/evidence/box-pipeline-probe/2026-09-24'
ignored={'runId','elapsedMs','resizeNs','elapsedNanosObservational','elapsedNanos'}
def stable(x):
    if isinstance(x,dict):return {k:stable(v) for k,v in x.items() if k not in ignored}
    if isinstance(x,list):return [stable(v) for v in x]
    return x
def read(path):return json.loads(path.read_bytes())
def every_passed(x):
    if isinstance(x,dict):
        if 'passed' in x:assert x['passed'] is True,x
        for v in x.values():every_passed(v)
    elif isinstance(x,list):
        for v in x:every_passed(v)
for manifest in [Path('/private/tmp/kandong-box-pipeline-protected-20260924.json'),evidence/'source-sha256.json']:
    for name,sha in read(manifest).items():assert hashlib.sha256((root/name).read_bytes()).hexdigest()==sha,name
reports={};uuids=[]
for kind in ['detector','original','geometry','polygon','boxes']:
    rounds=[read(raw/f'{kind}-{n}-report.json') for n in [1,2]]
    assert stable(rounds[0])==stable(rounds[1]),kind
    if kind!='boxes':assert stable(rounds[0])==stable(read(old/f'{kind}-1-report.json')),kind
    for item in rounds:
        every_passed(item);uuids.append(item['runId'])
    reports[kind]=rounds
assert len(uuids)==len(set(uuids))==10
times={}
for group,count in [('regression',7),('geometry',1),('polygon',1),('boxes',1)]:
    times[group]=[]
    for n in [1,2]:
        text=(raw/f'{group}-{n}-instrumentation.txt').read_text()
        assert f'OK ({count} test' in text and 'INSTRUMENTATION_CODE: -1' in text
        assert 'FAILURES' not in text and 'INSTRUMENTATION_FAILED' not in text
        times[group].append(float(re.search(r'Time: ([\d.]+)',text)[1]))
expected={'completeCases':22,'budgetCases':2,'rows':1020,'contourVertices':4229,'scores':17,'distances':15,
 'expandedPaths':15,'expandedVertices':232,'postQuads':15,'rawBoxes':15,'finalBoxes':14,'maskCases':16,'crops':13,'cropChannels':139875}
maximums={}
for report in reports['boxes']:
    assert report['status']=='passed' and report['api']==31 and report['device']=='LIO-AN00'
    assert report['totals']['actual']==report['totals']['expected']==expected
    assert len(report['cases'])==24 and len(report['guards'])==46
    assert [x['id'] for x in report['cases']]==report['expectedCaseIds']
    assert [x['id'] for x in report['guards']]==report['expectedGuardIds']
    assert len(set(report['expectedCaseIds']))==24 and len(set(report['expectedGuardIds']))==46
    assert all(x['status']=='completed' for x in report['cases']+report['guards'])
    cleanup=report['cleanup']
    assert cleanup['balanced'] and cleanup['matOpened']==5612 and cleanup['bitmapOpened']==25
    assert cleanup['matAcquired']==cleanup['matReleaseAttempts']==cleanup['matReleased']
    assert cleanup['bitmapOpened']==cleanup['bitmapRecycleAttempts']==cleanup['bitmapRecycled']
    def walk(x):
        if isinstance(x,dict):
            if 'mismatches' in x:assert x['mismatches']==[]
            if 'changedChannels' in x:assert x['changedChannels']==0
            if 'maxFiniteAbs' in x:
                for k,v in x['maxFiniteAbs'].items():maximums[k]=max(maximums.get(k,0),v)
            for v in x.values():walk(v)
        elif isinstance(x,list):
            for v in x:walk(v)
    walk(report)
    for case in report['cases']:
        assert case['cleanup']['balanced']
        if case['trace']['status']!='COMPLETE':
            assert case['trace']['usableBoxes']==0 and case['trace']['processedCandidates']==0
            assert not case.get('crops')
summary=dict(status='accepted-bounded-synthetic-target-device',device='HUAWEI LIO-AN00/API31',
    rounds=2,junitTestsPerRound=10,boxes=expected,guards=46,cropChangedChannels=0,
    maximumFiniteAbsoluteDifferences=maximums,matBuffersPerRound=5612,bitmapsPerRound=25,
    nonTimingRoundsEqual=True,oldFourReportsMatchAcceptedBaseline=True,
    excludedComparisonFields=sorted(ignored),runIds=uuids,instrumentationSecondsObservational=times,
    sourceAndBuildFingerprintsUnchanged=True,protectedFiles=907,
    limitations=['Synthetic frozen probabilities; not fresh detector-to-recognition inference',
      'No EN/FR/ZH quality or real-screen acceptance','Only target Huawei; API36 native SIGILL unresolved',
      'Mat headers finalizer-owned; UNION internals and long-term memory not bounded by these counts'])
(raw/'acceptance-summary.json').write_text(json.dumps(summary,ensure_ascii=False,indent=2)+'\n')
dest=evidence/'huawei';dest.mkdir(exist_ok=True)
for path in raw.iterdir():
    assert path.is_file();shutil.copy2(path,dest/path.name)
shutil.copy2(__file__,evidence/'acceptance-check.py')
shutil.copy2('/private/tmp/kandong-run-box-pipeline-device-20260924.py',evidence/'device-runner.py')
print(json.dumps(summary,ensure_ascii=False,indent=2))
