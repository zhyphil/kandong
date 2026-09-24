"""Verify archived real model evidence against pre-inference labels and source bindings."""
import gzip, hashlib, json, xml.etree.ElementTree as E
from pathlib import Path
out = Path(__file__).resolve().parent
root = out.parents[3]
def sha(p): return hashlib.sha256(p.read_bytes()).hexdigest()
def read(p): return json.loads(gzip.decompress(p.read_bytes()) if p.suffix == '.gz' else p.read_bytes())
paths = {'new':root/'docs/fixtures/language-context-v2.json','legacy':root/'docs/fixtures/language-routing-v1.json'}
hashes = {'new':'fbc29c9fd6fa34680ad1ece4f86b74e4f57b6316548046f45b63b7efaac20d76','legacy':'733f883e4e974f110b201ceb9307d648f53bc14b2e6b46719a5a097eb33d6e7c'}
fixtures = {key:{c['id']:c for c in read(p)['cases']} for key,p in paths.items()}
assert len(fixtures['new']) == 44 and len(fixtures['legacy']) == 38
for key,p in paths.items():
    assert sha(p) == hashes[key]
    assert p.read_bytes() == (root/'modelprobe/src/androidTest/assets'/p.name).read_bytes()
reports = [read(out/f'report-{n}.json.gz') for n in (1,2)]
old = {c['id']:c for c in read(root/'docs/evidence/language-routing/2026-09-24/report-1.json')['cases']}
assert reports[0]['runId'] != reports[1]['runId']
stable = lambda r:[{k:v for k,v in c.items() if k != 'elapsedMs'} for c in r['cases']]
assert stable(reports[0]) == stable(reports[1])
for n,r in enumerate(reports,1):
    assert r['status'] == 'complete' and r['technicalPassed'] and not r['errors'] and r['api'] == 37
    assert r['clientsOpened'] == r['clientsClosed'] == 1
    assert r['fixtureSha256'] == hashes and all(not p['granted'] for p in r['permissions'])
    log = (out/f'android-{n}.txt').read_text()
    assert 'OK (24 tests)' in log and 'INSTRUMENTATION_CODE: -1' in log and 'FAILURES' not in log
    cases = r['cases']; assert len(cases) == len({(c['corpus'],c['id']) for c in cases}) == 82
    query_count = 0; unique = {}; normalized = {}
    for c in cases:
        f = fixtures[c['corpus']][c['id']]
        if c['corpus'] == 'new':
            blocks = [{**b,'raw':b['text']} for b in f['blocks']]
            for b in blocks: del b['text']
            expected_sha = hashlib.sha256(json.dumps(f['blocks'],ensure_ascii=False,sort_keys=True,separators=(',',':')).encode()).hexdigest()
            assert c['pageId'] == f['pageId'] == expected_sha and c['targetId'] == f['targetId']
            category = f['category']
        else:
            blocks = [{'id':'target','groupId':'card','raw':f['text'],'ocrConflict':False}]
            assert c['pageId'] == hashlib.sha256(f['text'].encode()).hexdigest()
            category = f['group']
        assert c['blocks'] == blocks and c['category'] == category and c['expected'] == f['expected']
        by_id = {b['id']:b for b in blocks}; assert c['raw'] == by_id[c['targetId']]['raw']
        obs = c['observations']; assert len(obs) == len({o['key'] for o in obs})
        for o in obs:
            query_count += 1; assert o['pageId'] == c['pageId']
            if o['key'] == 'page':assert o['raw'] == '\n'.join(b['raw'] for b in blocks)
            elif o['key'].startswith('group:'):
                group = o['key'].removeprefix('group:');assert o['raw'] == '\n'.join(b['raw'] for b in blocks if b['groupId'] == group)
            elif o['start'] is not None:
                b = by_id[o['blockId']]
                assert o['key'] == f"span:{b['id']}:{o['start']}:{o['end']}"
                assert 0 <= o['start'] < o['end'] <= len(b['raw'].encode('utf-16-le'))//2
                assert o['raw'] == b['raw'].encode('utf-16-le')[o['start']*2:o['end']*2].decode('utf-16-le')
            else:assert o['raw'] == by_id[o['blockId']]['raw'] and o['key'] == 'block:'+o['blockId']
            if o['raw'] in unique: assert unique[o['raw']] == o['candidates']
            unique[o['raw']] = o['candidates']
        if c['corpus'] == 'legacy':
            assert c['decisions']['v1'] == old[c['id']]['decision']
            assert next(o for o in obs if o['key']=='block:target')['candidates'] == old[c['id']]['candidates']
        for v,d in c['decisions'].items():
            assert d['raw'] == c['raw']
            correct = d['action'] == f['expected']['action'] and d.get('language') == f['expected'].get('language')
            ready = d['action'] != 'review'
            normalized[(c['corpus'],c['id'],v)] = (correct,ready)
    assert query_count == r['evidenceQueries'] == 2013
    assert len(unique) == r['uniqueModelInferences'] == 1204
    def stats(subset,v):
        values = [normalized[(c['corpus'],c['id'],v)] for c in subset]
        return {'cases':len(values),'matching':sum(a for a,b in values),'correctReady':sum(a and b for a,b in values),
                'correctReview':sum(a and not b for a,b in values),'wrongAutomatic':sum(not a and b for a,b in values),
                'reviewInsteadOfReady':sum(not a and not b for a,b in values)}
    for corpus in ('new','legacy'):
        subset = [c for c in cases if c['corpus']==corpus]
        for v in ('v1','A','B'):
            actual = stats(subset,v)
            actual['categories'] = {cat:stats([c for c in subset if c['category']==cat],v) for cat in {c['category'] for c in subset}}
            assert actual == r['stats'][corpus][v]
    gates = r['gates']
    assert gates == {'newNoWrongAutomatic':False,'newPureAtLeast10':True,'newShortAtLeast6':True,
        'newShortImprovesOverV1':True,'literalConflictAllCorrect':True,'legacyNoWrongAutomatic':True,'legacyPureAtLeast15':True}
    assert not r['qualityAccepted']
identity = read(out/'apk-identity.json'); prior = read(root/'docs/evidence/language-routing/2026-09-24/apk-identity.json')
assert identity['main'] == prior['main']
assert all(identity['test']['assetsAndNative'][k] == v for k,v in prior['test']['assetsAndNative'].items())
assert set(identity['test']['assetsAndNative']) - set(prior['test']['assetsAndNative']) == {'assets/language-context-v2.json'}
suites = [E.parse(p).getroot() for p in (out/'junit').glob('TEST-*.xml')]
assert sum(int(s.get('tests')) for s in suites) == 97
assert all(s.get('failures') == s.get('errors') == s.get('skipped') == '0' for s in suites)
for p,digest in read(out/'source-hashes.json').items(): assert sha(root/p) == digest
print(json.dumps({'status':'evaluation-complete-candidate-B-not-adopted','technicalPassed':True,'qualityAccepted':False,
 'jvmTests':97,'rounds':2,'technicalTestsPerRound':24,'newPages':44,'knownRegressions':38,
 'uniqueInferencesPerRound':1204,'evidenceQueriesPerRound':2013,'scoresExactlyRepeat':True,
 'oldV1EvidenceExactlyRepeated':True,'newStats':reports[0]['stats']['new'],'legacyStats':reports[0]['stats']['legacy']},indent=2))
