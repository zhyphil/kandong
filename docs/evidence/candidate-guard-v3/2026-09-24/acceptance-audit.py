from pathlib import Path
import json, hashlib, gzip, shutil, zipfile
from xml.etree import ElementTree as E

root = Path('/Users/haoyuzuo/Projects/KanDong')
raw = Path('/private/tmp/kandong-v3-device-20260924')
validation = Path('/private/tmp/kandong-v3-validation-20260924')
old = root / 'docs/evidence/candidate-selection-v2/2026-09-24'
out = root / 'docs/evidence/candidate-guard-v3/2026-09-24'
read = lambda p: json.loads(p.read_bytes())
sha = lambda data: hashlib.sha256(data).hexdigest()
ignore = {'runId', 'elapsedMs', 'resizeNs', 'elapsedNanosObservational', 'elapsedNanos',
          'freshE2eRunId', 'freshE2eReportSha256'}
def stable(x):
    if isinstance(x, dict): return {k: stable(v) for k, v in x.items() if k not in ignore}
    if isinstance(x, list): return [stable(v) for v in x]
    return x
def passed(x):
    if isinstance(x, dict):
        if 'passed' in x: assert x['passed'] is True
        for k in ['differentBytes', 'differentFloatBits', 'changedChannels']:
            if k in x: assert x[k] == 0
        for v in x.values(): passed(v)
    elif isinstance(x, list):
        for v in x: passed(v)

reports = {}; ids = []
for kind, count in [('e2e', 2), ('selection', 1)]:
    rounds = [read(raw / f'{kind}-{n}-report.json') for n in [1, 2]]
    assert stable(rounds[0]) == stable(rounds[1]), kind
    previous = json.loads(gzip.decompress((old / f'{kind}-1-report.json.gz').read_bytes()))
    current = dict(rounds[0])
    if kind == 'selection':
        assert current.pop('crossScriptConflictsReviewed') == 3
        assert current['configuration'] == 'shared-box-v3-agreement-guarded-latin'
        current['configuration'] = previous['configuration']
    assert stable(current) == stable(previous), kind
    for n, r in enumerate(rounds, 1):
        passed(r); ids.append(r['runId'])
        assert r['errors'] == [] and r['status'] == 'passed'
        log = (raw / f'{kind}-{n}-instrumentation.txt').read_text()
        assert f'OK ({count} test' in log and 'INSTRUMENTATION_CODE: -1' in log
        assert 'FAILURES' not in log and 'INSTRUMENTATION_FAILED' not in log
    reports[kind] = rounds
assert len(set(ids)) == 4
for i, r in enumerate(reports['selection']):
    assert (r['frozenCases'], r['frozenBlocks'], r['freshBlocks'], r['emptyLatinCandidatesRetained'],
            r['blankCases'], r['controls'], r['crossScriptConflictsReviewed']) == (98, 180, 9, 27, 4, 8, 3)
    assert len(r['cases']) == 98 and len(r['freshCases']) == 5
    e2e = reports['e2e'][i]
    assert r['freshE2eRunId'] == e2e['runId']
    for case in r['freshCases']:
        for model in ['ch', 'latin']:
            src = next(m for m in e2e['models'] if m['id'] == model + '/' + case['id'])
            for actual, orig in zip(case['blocks'], src['bindings'], strict=True):
                for key in ['boxId', 'readingOrder', 'quad']: assert actual[key] == orig[key]
                assert actual[model] == orig['raw']
                page = next(s for s in e2e['cases'] if s['id'] == case['id'])
                assert actual['pageId'] == page['sourceBgrSha256']
latest_raw = (raw / 'e2e-2-device-raw.json').read_bytes()
assert sha(latest_raw) == reports['selection'][1]['freshE2eReportSha256']
assert json.loads(latest_raw) == reports['e2e'][1]

units = list((root / 'modelprobe/build/test-results/testDebugUnitTest').glob('TEST-*.xml'))
unit_roots = [E.parse(f).getroot() for f in units]
assert sum(int(u.get('tests')) for u in unit_roots) == 70
assert all(u.get('failures') == u.get('errors') == '0' for u in unit_roots)
red = E.parse(validation / 'red-test.xml').getroot()
assert red.get('tests') == red.get('failures') == '1'
assert 'conflictingPunctuationDigitsAndBlankCannotTurnIntoLetters' in (validation / 'red-test.xml').read_text()
identity = read(validation / 'apk-identity.json')
assert read(raw / 'installed.json') == identity
for name, key in [('apk/debug/modelprobe-debug.apk', 'mainApkSha256'),
                  ('apk/androidTest/debug/modelprobe-debug-androidTest.apk', 'testApkSha256')]:
    assert sha((root / 'modelprobe/build/outputs' / name).read_bytes()) == identity[key]
fixture = root / 'modelprobe/src/androidTest/assets/shared-candidates-v2.json'
assert sha(fixture.read_bytes()) == reports['selection'][0]['fixtureSha256']
assets = read(old / 'baseline-asset-native-hashes.json')
assets['assets/shared-candidates-v2.json'] = sha(fixture.read_bytes())
with zipfile.ZipFile(root / 'modelprobe/build/outputs/apk/androidTest/debug/modelprobe-debug-androidTest.apk') as z:
    actual = {n: sha(z.read(n)) for n in z.namelist() if n.startswith(('assets/', 'lib/')) and not n.endswith('/')}
assert actual == assets and len(actual) == 292
assert '0 errors, 6 warnings' in (root / 'modelprobe/build/reports/lint-results-debug.txt').read_text()
replay_path = Path('/private/tmp/kandong-v3-replay-final-20260924/guard-replay.json')
replay = read(replay_path)
assert replay['pages'] == 222 and replay['blocks'] == 423 and replay['reviewPages'] == 1
assert replay['readyCharacterRegressionsVsCh'] == replay['knownCorrectChPagesLost'] == []
assert replay['readyOutputsIdenticalToV2'] is True
assert sum(b['v3']['origin'] == 'REVIEW' for r in replay['results'] for b in r['blocks']) == 1
assert replay['scriptSha256'] == sha((root / 'scripts/replay-candidate-guard-v3.py').read_bytes())
paths = ['scripts/replay-candidate-guard-v3.py', 'scripts/run-candidate-emulator.py',
         'modelprobe/src/testShared/java/com/kandong/modelprobe/SharedCandidates.kt',
         'modelprobe/src/test/java/com/kandong/modelprobe/SharedCandidatesTest.kt',
         'modelprobe/src/androidTest/java/com/kandong/modelprobe/CandidateSelectionProbeTest.kt',
         'modelprobe/src/androidTest/assets/shared-candidates-v2.json']
sources = {p: sha((root / p).read_bytes()) for p in paths}
out.mkdir(parents=True, exist_ok=False)
archived = {}
for p in list(raw.iterdir()) + [replay_path]:
    if p.suffix == '.json':
        target = out / (p.name + '.gz'); target.write_bytes(gzip.compress(p.read_bytes(), mtime=0))
        archived[p.name] = dict(file=target.name, sha256=sha(p.read_bytes()))
    else: shutil.copy2(p, out / p.name)
for name in ['red-test.xml', 'apk-identity.json']: shutil.copy2(validation / name, out / name)
for source, name in [('/private/tmp/kandong-v3-red-20260924.txt', 'red-output.txt'),
                     ('/private/tmp/kandong-v3-build-20260924.txt', 'build-output.txt'),
                     ('/private/tmp/kandong-run-v3-20260924.py', 'executed-runner.py')]:
    shutil.copy2(source, out / name)
(out / 'junit').mkdir()
for f in units: shutil.copy2(f, out / 'junit' / f.name)
shutil.copy2(root / 'modelprobe/build/reports/lint-results-debug.txt', out / 'lint-results.txt')
shutil.copy2(__file__, out / 'acceptance-audit.py')
summary = dict(status='accepted-conservative-candidate-guard-experiment', unitTests=70, rounds=2,
    targetedJUnitPerRound=3, fullFourteenTestSuiteRerun=False, api=37, pageSize=16384,
    frozenPages=98, frozenBlocks=180, freshPages=5, freshBlocks=9, conflictControls=3,
    e2eUnchangedFromV2=True, priorCandidateDecisionsUnchanged=True, twoReportsRepeat=True,
    replayPages=222, replayBlocks=423, reviewPages=1, reviewBlocks=1,
    readyCharacterRegressionsVsCh=0, knownCorrectChPagesLost=0, readyOutputsUnchanged=True,
    runIds=ids, excludedFields=sorted(ignore), apk=identity, sourceSha256=sources, rawReports=archived,
    qualityAccepted=False, limits=['Replay is not new inference or a blind quality test',
    'REVIEW preserves both candidates; the period is not corrected or accepted',
    'One review block currently withholds aggregate pageText; block records remain available',
    'Both E2E input digests checked on device; latest raw digest additionally checked in this audit',
    'Frozen rows are not 98 new Android inferences; five fresh pages and nine boxes only',
    'No product, real-screen, translation integration, physical-phone testing or publication'])
(out / 'acceptance-summary.json').write_text(json.dumps(summary, ensure_ascii=False, indent=2) + '\n')
print(json.dumps({k: summary[k] for k in ['status','unitTests','rounds','targetedJUnitPerRound','replayPages','replayBlocks','reviewBlocks','e2eUnchangedFromV2']}, indent=2))
