"""Independent archive audit: raw results vs pre-inference labels; no relabeling or retuning."""
import hashlib, json, xml.etree.ElementTree as E
from pathlib import Path
out = Path(__file__).resolve().parent
root = out.parents[3]
sha = lambda p: hashlib.sha256(p.read_bytes()).hexdigest()
fixture = root / 'docs/fixtures/language-routing-v1.json'
assert sha(fixture) == '733f883e4e974f110b201ceb9307d648f53bc14b2e6b46719a5a097eb33d6e7c'
assert fixture.read_bytes() == (root / 'modelprobe/src/androidTest/assets/language-routing-v1.json').read_bytes()
expected = {c['id']: c for c in json.loads(fixture.read_text())['cases']}
assert len(expected) == 38
reports = [json.loads((out / f'report-{n}.json').read_text()) for n in (1, 2)]
assert reports[0]['runId'] != reports[1]['runId']
results = []
for n, report in enumerate(reports, 1):
    assert report['technicalPassed'] and report['status'] == 'complete' and not report['errors']
    assert report['clientsOpened'] == report['clientsClosed'] == 1
    assert report['fixtureSha256'] == sha(fixture)
    assert report['api'] == 37 and all(not p['granted'] for p in report['permissions'])
    log = (out / f'android-{n}.txt').read_text()
    assert 'OK (15 tests)' in log and 'INSTRUMENTATION_CODE: -1' in log and 'FAILURES' not in log
    cases = report['cases']; assert len(cases) == len({c['id'] for c in cases}) == 38
    correct = wrong = reviewed = 0
    for c in cases:
        f = expected[c['id']]; d = c['decision']
        assert c['raw'] == d['raw'] == f['text'] and c['expected'] == f['expected'] and c['group'] == f['group']
        equal = d['action'] == f['expected']['action'] and d['language'] == f['expected'].get('language')
        assert equal == c['qualityCorrect']
        automatic_error = not equal and d['action'] != 'review'
        assert automatic_error == c['wrongAutomaticRoute']
        correct += equal; wrong += automatic_error; reviewed += d['action'] == 'review'
    assert correct == report['qualityCorrectCount'] == 36
    assert wrong == report['wrongAutomaticRoutes'] == 2
    assert reviewed == report['reviewCount'] == 14
    assert not report['qualityAccepted']
    results.append([{k:v for k,v in c.items() if k != 'inferenceMs'} for c in cases])
assert results[0] == results[1]
identity = json.loads((out / 'apk-identity.json').read_text())
assert identity == json.loads((out / 'installed.json').read_text())
assert identity['main']['sha256'] == '7f9ff2550d8618a03903eaaccdb8bf98aa3e0ca631663cd1773563b4185a6aea'
baseline = json.loads((root / 'docs/evidence/candidate-selection-v2/2026-09-24/baseline-asset-native-hashes.json').read_text())
baseline['assets/shared-candidates-v2.json'] = sha(root / 'modelprobe/src/androidTest/assets/shared-candidates-v2.json')
assert len(baseline) == 292
assert all(identity['test']['assetsAndNative'][name] == value for name, value in baseline.items())
assert set(identity['test']['assetsAndNative']) - set(baseline) == {
    'assets/language-routing-v1.json', 'assets/tflite_langid.tflite.jpg',
    *('lib/' + abi + '/liblanguage_id_l2c_jni.so' for abi in ('arm64-v8a','armeabi-v7a','x86','x86_64'))}
for file, value in json.loads((out/'source-hashes.json').read_text()).items(): assert sha(root/file) == value
suites = [E.parse(p).getroot() for p in (out/'junit').glob('TEST-*.xml')]
assert sum(int(s.get('tests')) for s in suites) == 88
assert all(s.get('failures') == s.get('errors') == s.get('skipped') == '0' for s in suites)
print(json.dumps({'status':'evaluation-complete-rule-rejected', 'modelprobeJvmTests':88,
 'androidRounds':2,'technicalTestsPerRound':15,'frozenStrings':38,'matchingDecisions':36,
 'wrongAutomaticRoutes':2,'correctReviews':14,'exactlyRepeatable':True,'oldAssetsNativeUnchanged':292,
 'qualityAccepted':False,'formalTranslationIntegrated':False}, indent=2))
