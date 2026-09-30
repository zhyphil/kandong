"""Read synthetic reports only. Never mutates an input or selects OCR by expected text."""
from pathlib import Path
import argparse
import collections
import json

p = argparse.ArgumentParser()
p.add_argument('--reports', type=Path, required=True)
p.add_argument('--baseline', type=Path, required=True)
p.add_argument('--output', type=Path, required=True)
a = p.parse_args()

def load(path):
    return json.loads(path.read_text())

summary = load(a.reports / 'full-page-ocr-summary.json')
assert summary['technicalPassed'] and summary['threadsRestored'] and summary['scopeSucceeded']
assert len(summary['reports']) == summary['expectedPages'] == summary['technicalPassedPages']
assert summary['sessionsOpened'] == summary['sessionsClosed']
assert summary['ortCleanup']['opened'] == summary['ortCleanup']['closed'] == summary['ortCleanup']['closeAttempts']
assert not summary['ortCleanup']['uncertain']
counts = collections.Counter()
pages = []
for name in summary['reports']:
    assert Path(name).name == name
    r = load(a.reports / name)
    old = load(a.baseline / name)
    ass = r['association']
    assert r['runId'] == summary['runId'] and r['technicalPassed']
    assert r['associationFieldsPreserved'] and r['associationMembersComplete']
    assert r['frameCloseAttempts'] == r['frameClosed'] == 1 and r['pageResourcesBalanced']
    assert ass['published'] and ass['rejection'] is None
    identity = ass['identity']
    assert identity['sourceBatch'] == r['runId'] and identity['version'] == r['version']
    assert identity['pageFixtureId'] == r['pageFixtureId']
    assert identity['model'] == dict(id=r['modelId'], sha256=r['modelSha256'], vocabulary=r['vocabulary'])
    assert identity['detectorSha256'] == r['detectorModelSha256']
    assert identity['dictionarySha256'] == r['modelGroupSessions']['dictionarySha256']
    raw = r['rawCandidates']; by_id = {c['id']: c for c in raw}
    assert len(by_id) == len(raw) == ass['candidateCount'] == r['recognitionInvocations']
    assert sorted(by_id) == sorted(ass['rawCandidateIds'])
    assert len(ass['groups']) == ass['groupCount'] and len(ass['edges']) == ass['edgeCount']
    members = [m for g in ass['groups'] for m in g['memberIds']]
    assert sorted(members) == sorted(by_id) and len(members) == len(set(members))
    for c in raw:
        assert c['version'] == r['version'] and c['modelId'] == r['modelId']
        assert c['pageFixtureId'] == r['pageFixtureId']
        s = r['strips'][c['stripIndex']]
        assert s['index'] == c['stripIndex'] and s['read'] == c['read'] and s['core'] == c['core']
        assert s['status'] == 'COMPLETE'
    for e in ass['edges']:
        assert e['leftId'] in by_id and e['rightId'] in by_id and e['leftId'] != e['rightId']
    for g in ass['groups']:
        texts = {by_id[i]['rawText'] for i in g['memberIds']}
        agreed = g['text'] == 'IDENTICAL_NONEMPTY' and g['geometry'] == 'UNAMBIGUOUS_PAIR'
        if agreed:
            assert len(texts) == 1 and '' not in texts and g['agreedRaw'] in texts
            counts['agreedGroups'] += 1
        else:
            assert g['agreedRaw'] is None
        if g['text'] == 'DIFFERENT_RAW':
            assert len(texts) > 1
            counts['conflictGroups'] += 1
        if any('稅' in t for t in texts) and any('税' in t for t in texts):
            assert g['text'] == 'DIFFERENT_RAW' and g['agreedRaw'] is None
            counts['hantTaxConflictGroups'] += 1
    # Only fresh session/version and their candidate-ID prefix differ between runs.
    def normalize(items):
        return [dict({k: v for k, v in c.items() if k not in ('id', 'version')},
                     id=c['id'].split('/', 1)[1]) for c in items]
    same = normalize(raw) == normalize(old['rawCandidates'])
    pages.append(dict(file=name, candidates=len(raw), sameRawFieldsAsHistoricalBaseline=same,
                      strictExactPage=r['quality']['strictExactPage']))
    counts.update(pages=1, candidates=len(raw), edges=len(ass['edges']), groups=len(ass['groups']),
                  empty=sum(c['rawText'] == '' for c in raw),
                  noncore=sum(not c['ownsCoreCenter'] for c in raw),
                  strictExactPages=int(r['quality']['strictExactPage']),
                  sameRawFieldsAsHistoricalBaseline=int(same))
assert counts['candidates'] == summary['associationCandidateCount']
assert counts['groups'] == summary['associationGroupCount']
assert counts['edges'] == summary['associationEdgeCount']
result = dict(runId=summary['runId'], counts=dict(counts), pages=pages,
              note='Field preservation is independently asserted inside the same-inference Android test. Historical equality excludes only version/ID prefix, and is recorded without using expected OCR answers.')
a.output.write_text(json.dumps(result, ensure_ascii=False, indent=2) + '\n')
print(json.dumps(result['counts'], ensure_ascii=False))
