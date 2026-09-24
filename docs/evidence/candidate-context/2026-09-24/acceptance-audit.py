from pathlib import Path
from xml.etree import ElementTree as E
import gzip, hashlib, json, shutil, subprocess, zipfile

root = Path('/Users/haoyuzuo/Projects/KanDong')
raw = Path('/private/tmp/kandong-context-device-20260924')
out = root / 'docs/evidence/candidate-context/2026-09-24'
old = root / 'docs/evidence/candidate-guard-v3/2026-09-24'
sha = lambda b: hashlib.sha256(b).hexdigest()
read = lambda p: json.loads(p.read_bytes())
ignored = {'runId','elapsedMs','resizeNs','elapsedNanosObservational','elapsedNanos','freshE2eRunId','freshE2eReportSha256'}
def stable(x):
    if isinstance(x, dict): return {k:stable(v) for k,v in x.items() if k not in ignored}
    if isinstance(x, list): return [stable(v) for v in x]
    return x
def passed(x):
    if isinstance(x, dict):
        if 'passed' in x: assert x['passed'] is True
        if 'errors' in x: assert x['errors'] == []
        for v in x.values(): passed(v)
    elif isinstance(x, list):
        for v in x: passed(v)
reports = {}; ids = []
for group, count in [('e2e',2),('selection',1),('context',5)]:
    rounds = [read(raw/f'{group}-{n}-report.json') for n in [1,2]]
    assert stable(rounds[0]) == stable(rounds[1]), group
    if group != 'context':
        previous = json.loads(gzip.decompress((old/f'{group}-1-report.json.gz').read_bytes()))
        assert stable(rounds[0]) == stable(previous), group
    for n, r in enumerate(rounds,1):
        passed(r); assert r['status'] == 'passed'; ids.append(r['runId'])
        log = (raw/f'{group}-{n}-instrumentation.txt').read_text()
        assert f'OK ({count} test' in log and 'INSTRUMENTATION_CODE: -1' in log
        assert 'FAILURES' not in log and 'INSTRUMENTATION_FAILED' not in log
    reports[group] = rounds
assert len(set(ids)) == 6
for i, context in enumerate(reports['context']):
    e2e = reports['e2e'][i]; selection = reports['selection'][i]
    original = (raw/f'e2e-{i+1}-device-raw.json').read_bytes()
    assert json.loads(original) == e2e
    for dependent in [selection,context]:
        assert dependent['freshE2eRunId'] == e2e['runId']
        assert dependent['freshE2eReportSha256'] == sha(original)
    assert context['freshPages'] == 5 and context['freshBlocks'] == 9 and len(context['pages']) == 5
    assert sum(p['sourceCount'] for p in context['pages']) == 9
    for p in context['pages']:
        source = next(s for s in e2e['cases'] if s['id'] == p['id'])
        chosen = next(s for s in selection['freshCases'] if s['id'] == p['id'])
        assert p['manuallyGrouped'] is True and p['versionAndExpiryPassed'] is True
        assert len(p['blocks']) == p['sourceCount'] == len(chosen['blocks'])
        for b, c in zip(p['blocks'],chosen['blocks'],strict=True):
            assert b['id'] == c['boxId'] and b['pageId'] == source['sourceBgrSha256']
            for key in ['readingOrder','quad','ch','latin','raw']: assert b[key] == c[key]
            assert b['language'] == 'und'
            assert b['selectedModelIds'] == {'AGREED':['ch','latin'],'CH':['ch'],'LATIN':['latin']}[c['origin']]
            for model in ['ch','latin']:
                m = next(m for m in e2e['models'] if m['id'] == model+'/'+p['id'])
                binding = next(v for v in m['bindings'] if v['boxId'] == b['id'])
                assert b[model] == binding['raw'] and b['quad'] == binding['quad']
units = {}
for module, expected in [('ocrlab',49),('modelprobe',74)]:
    files = list((root/module/'build/test-results/testDebugUnitTest').glob('TEST-*.xml'))
    suites = [E.parse(p).getroot() for p in files]
    assert sum(int(s.get('tests')) for s in suites) == expected
    assert all(s.get('failures') == s.get('errors') == '0' for s in suites)
    units[module] = expected
    dest = out/'junit'/module; dest.mkdir(parents=True,exist_ok=True)
    for p in files: shutil.copy2(p,dest/p.name)
red = E.parse('/private/tmp/kandong-context-evidence-red.xml').getroot()
assert red.get('tests') == '5' and red.get('failures') == '3' and red.get('errors') == '0'
identity = read(out/'apk-identity.json'); assert identity == read(raw/'installed.json')
main = root/'modelprobe/build/outputs/apk/debug/modelprobe-debug.apk'
test = root/'modelprobe/build/outputs/apk/androidTest/debug/modelprobe-debug-androidTest.apk'
assert sha(main.read_bytes()) == identity['mainApkSha256'] and sha(test.read_bytes()) == identity['testApkSha256']
assert identity['mainApkSha256'] == read(old/'apk-identity.json')['mainApkSha256']
baseline = read(root/'docs/evidence/candidate-selection-v2/2026-09-24/baseline-asset-native-hashes.json')
baseline['assets/shared-candidates-v2.json'] = sha((root/'modelprobe/src/androidTest/assets/shared-candidates-v2.json').read_bytes())
with zipfile.ZipFile(test) as z:
    current = {n:sha(z.read(n)) for n in z.namelist() if n.startswith(('assets/','lib/')) and not n.endswith('/')}
assert current == baseline and len(current) == 292
with zipfile.ZipFile(main) as z:
    dex = b''.join(z.read(n) for n in z.namelist() if n.endswith('.dex'))
    assert b'Lcom/kandong/ocrlab/context/' not in dex and b'Lcom/kandong/modelprobe/CandidateContext' not in dex
tools = Path('/Users/haoyuzuo/Library/Android/sdk/build-tools/35.0.0')
checks = []
for args in [[str(tools/'zipalign'),'-c','-P','16','4',str(test)], [str(tools/'apksigner'),'verify',str(test)]]:
    result = subprocess.run(args,capture_output=True,text=True,timeout=30)
    assert result.returncode == 0
    checks.append(dict(tool=Path(args[0]).name,returncode=result.returncode,stdout=result.stdout,stderr=result.stderr))
sources = [root/'modelprobe/build.gradle.kts',root/'ocrlab/build.gradle.kts',root/'scripts/run-candidate-emulator.py',
    root/'modelprobe/src/testShared/java/com/kandong/modelprobe/CandidateContext.kt',
    root/'modelprobe/src/testShared/java/com/kandong/modelprobe/SharedCandidates.kt',
    root/'modelprobe/src/contextTest/java/com/kandong/modelprobe/CandidateContextTest.kt',
    root/'modelprobe/src/androidTest/java/com/kandong/modelprobe/CandidateContextProbeTest.kt',
    root/'ocrlab/src/test/java/com/kandong/ocrlab/context/OcrContextEvidenceTest.kt']
sources += list((root/'ocrlab/src/contextShared/java/com/kandong/ocrlab/context').glob('*.kt'))
hashes = {str(p.relative_to(root)):sha(p.read_bytes()) for p in sources}
# The moved geometry source is identical to the accepted implementation, not a rewrite.
geometry = subprocess.check_output(['git','show','8fc9ec3:ocrlab/src/main/java/com/kandong/ocrlab/context/ContextGeometry.kt'],cwd=root)
assert geometry == (root/'ocrlab/src/contextShared/java/com/kandong/ocrlab/context/ContextGeometry.kt').read_bytes()
archives = {}
for p in raw.iterdir():
    if p.suffix == '.json':
        name = p.name+'.gz'; (out/name).write_bytes(gzip.compress(p.read_bytes(),mtime=0))
        archives[p.name] = dict(file=name,sha256=sha(p.read_bytes()))
    else: shutil.copy2(p,out/p.name)
for path, name in [('/private/tmp/kandong-context-evidence-red.xml','red-test.xml'),
    ('/private/tmp/kandong-context-evidence-red.txt','red-output.txt'),
    ('/private/tmp/kandong-context-evidence-green.txt','contract-green-output.txt'),
    ('/private/tmp/kandong-candidate-context-build.txt','build-output.txt')]: shutil.copy2(path,out/name)
for module, warnings in [('ocrlab',17),('modelprobe',6)]:
    p = root/module/'build/reports/lint-results-debug.txt'
    assert f'0 errors, {warnings} warnings' in p.read_text()
    shutil.copy2(p,out/f'{module}-lint.txt')
shutil.copy2(root/'scripts/run-candidate-emulator.py',out/'executed-runner.py')
shutil.copy2(__file__,out/'acceptance-audit.py')
summary = dict(status='accepted-synthetic-candidate-context-contract',baselineCommit='8fc9ec3',
    unitTests=units,totalUnitTests=123,rounds=2,targetedJUnitPerRound=8,fullSuiteRerun=False,
    freshPagesPerRound=5,freshBlocksPerRound=9,api=37,pageSize=16384,unchangedReports=['e2e','selection'],
    originalGeometrySourceUnchanged=True,threeReportsRepeat=True,allRawInputDigestsIndependentlyVerified=True,
    runIds=ids,excludedFields=sorted(ignored),apk=identity,packageChecks=checks,sourceSha256=hashes,rawReports=archives,
    qualityAccepted=False,limits=['All inputs are synthetic, no real screens or phone actions',
    'Phrase/card ownership is explicitly hand declared; no automatic semantic grouping',
    'OCR block language is undetermined; no language identification or translation model',
    'Human placeholder response tests delivery only, not translation quality',
    'Fresh five pages contain no REVIEW blocks; conflict gating is separately exercised in shared JVM/Android synthetic cases',
    'Original quad is retained; ROI selection uses its axis-aligned bounding rectangle',
    'No product integration, ocrlab UI acceptance, push, release or publication'])
(out/'acceptance-summary.json').write_text(json.dumps(summary,ensure_ascii=False,indent=2)+'\n')
print(json.dumps({k:summary[k] for k in ['status','totalUnitTests','rounds','targetedJUnitPerRound','freshPagesPerRound','freshBlocksPerRound','unchangedReports']},indent=2))
