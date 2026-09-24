#!/usr/bin/env python3
"""Audit fixed host evidence, keeping execution, exact repeatability, usability and quality separate."""
import argparse
import gzip
import hashlib
import json
import math
from pathlib import Path
from lingua_candidate import route, validate_evidence

ROOT = Path(__file__).resolve().parents[1]
PINS = {
    'v1': ('language-routing-v1', '733f883e4e974f110b201ceb9307d648f53bc14b2e6b46719a5a097eb33d6e7c'),
    'v2': ('language-context-v2', 'fbc29c9fd6fa34680ad1ece4f86b74e4f57b6316548046f45b63b7efaac20d76'),
    'v3': ('language-lingua-v3', 'e2319026a3d8370c205e0c516aedfcb5e41d66f87f1c259c2f68b6c2dbddd59d'),
}


def read(path):
    data = path.read_bytes()
    return json.loads(gzip.decompress(data) if path.suffix == '.gz' else data)


def require(value, message):
    if not value: raise AssertionError(message)


def matches(decision, expected):
    return decision['action'] == expected['action'] and decision['language'] == expected.get('language')


def tally(cases):
    wrong = [c for c in cases if not matches(c['decision'], c['expected']) and c['decision']['action'] != 'review']
    return dict(total=len(cases), correctReady=sum(matches(c['decision'], c['expected']) and
        c['decision']['action'] in ('translate', 'keep') for c in cases),
        correctReview=sum(c['decision']['action'] == c['expected']['action'] == 'review' for c in cases),
        correctLiteral=sum(c['decision']['action'] == c['expected']['action'] == 'keep-literal' for c in cases),
        wrongAutomatic=len(wrong), missedReady=sum(c['decision']['action'] == 'review' and
        c['expected']['action'] in ('translate', 'keep') for c in cases),
        wrongCases=[dict(corpus=c['corpus'], id=c['id'], decision=c['decision'], expected=c['expected']) for c in wrong])


def differences(left, right):
    require(left['raw'] == right['raw'], 'repeated raw identity')
    lm = {c['language']: c['score'] for c in left['candidates']}
    rm = {c['language']: c['score'] for c in right['candidates']}
    require(lm.keys() == rm.keys(), 'candidate membership changed')
    return dict(exact=left == right, segmentExact=left['segments'] == right['segments'],
                candidateOrderExact=[c['language'] for c in left['candidates']] ==
                                    [c['language'] for c in right['candidates']],
                maxAbsoluteScoreDelta=max((abs(lm[k]-rm[k]) for k in lm), default=0))


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('--reports', type=Path, required=True)
    p.add_argument('--output', type=Path, required=True)
    args = p.parse_args(); folder = args.reports
    fixtures = {}
    for corpus, (name, digest) in PINS.items():
        path = ROOT / 'docs/fixtures' / (name + '.json')
        require(hashlib.sha256(path.read_bytes()).hexdigest() == digest, 'fixture hash')
        for case in read(path)['cases']:
            fixtures[corpus, case['id']] = case
    reports = [read(folder / f'report-{i}.json.gz') for i in (1, 2)]
    supervision = read(folder / 'supervision.json')
    all_observations = []
    for r in reports:
        require(r['executionPassed'] and r['status'] == 'complete' and not r['errors'], 'execution failed')
        require(r['offline'] == {'loopbackBindErrno': 1, 'osSandbox': 'deny network*'}, 'network guard')
        require(r['clientsOpened'] == r['clientsUnloaded'] == 1 and r['cacheCleared'], 'lifetime')
        require({(c['corpus'], c['id']) for c in r['cases']} == fixtures.keys() and len(r['cases']) == 114,
                'membership/count')
        raw_cache = {}
        for c in r['cases']:
            original = fixtures[c['corpus'], c['id']]
            expected_blocks = original.get('blocks') or [dict(id='target', groupId='single', text=original['text'], ocrConflict=False)]
            require(c['blocks'] == expected_blocks and c['expected'] == original['expected'], 'fixture payload')
            require(c['targetId'] == original.get('targetId', 'target'), 'target binding')
            payload = {k: c[k] for k in ('pageId', 'targetId', 'blocks')}
            validate_evidence(payload, c['observations'])
            require(route(payload, c['observations']) == c['decision'], 'decision replay')
            target = next(b for b in c['blocks'] if b['id'] == c['targetId'])
            require(c['decision']['raw'] == target['text'], 'raw preservation')
            require(matches(c['decision'], original['expected']) == c['match'], 'scoring after decision')
            for key, obs in c['observations'].items():
                require(set(x['language'] for x in obs['candidates']) <= set(r['runtime']['languages']), 'model language set')
                base = {k: obs[k] for k in ('raw', 'candidates', 'segments')}
                if obs['raw'] in raw_cache: require(raw_cache[obs['raw']] == base, 'within-round cache mismatch')
                raw_cache[obs['raw']] = base
        require(len(raw_cache) == r['uniqueInitialInferences'] == 242, 'unique inference count')
        warm = r['warmObservations']
        require(len(warm) == len(raw_cache) and {o['observation']['raw'] for o in warm} == raw_cache.keys(), 'warm membership')
        for w in warm:
            diff = differences(raw_cache[w['observation']['raw']], w['observation'])
            require(w['exactlyRepeated'] == diff['exact'], 'warm exact flag')
            all_observations.append(diff)
        require(r['warmOutputsIdentical'] == all(w['exactlyRepeated'] for w in warm), 'warm status')
        require(len(r['unicodeControls']) == 3, 'unicode controls')
        calls = r['measurements']['calls']
        require(len(calls) == 242*2+3, 'calls count')
        warm_times = sorted(c['totalMs'] for c in calls if c['phase'] == 'warm')
        require(r['measurements']['warmP95Ms'] == warm_times[math.ceil(len(warm_times)*.95)-1], 'p95')
    a, b = reports
    cross = []
    for ca, cb in zip(a['cases'], b['cases']):
        require((ca['id'], ca['corpus']) == (cb['id'], cb['corpus']), 'case order')
        for key, oa in ca['observations'].items(): cross.append(differences(oa, cb['observations'][key]))
    old = [c for c in a['cases'] if c['corpus'] != 'v3']
    new = [c for c in a['cases'] if c['corpus'] == 'v3']
    metrics = {}
    for name, cases in [('old82', old), ('new32', new)]:
        pure = [c for c in cases if c['category'] in ('en', 'fr', 'zh', 'pure')]
        short = [c for c in cases if c['category'] == 'short' and c['expected']['action'] != 'review']
        metrics[name] = dict(overall=tally(cases), pure={lang: tally([c for c in pure if c['expected']['language'] == lang])
                                                     for lang in ('en', 'fr', 'zh')}, contextualShort=tally(short))
        metrics[name]['pureRawTop1Correct'] = sum(
            bool(c['observations']['block:' + c['targetId']]['candidates']) and
            c['observations']['block:' + c['targetId']]['candidates'][0]['language'] == c['expected']['language'] for c in pure)
        metrics[name]['pureRawTop1Total'] = len(pure)
    baseline = read(ROOT / 'docs/evidence/language-context-v2/2026-09-24/report-1.json.gz')
    baseline_by_id = {(('v1' if c['corpus'] == 'legacy' else 'v2'), c['id']): c for c in baseline['cases']}
    regressions = []; gains = []
    baseline_cases = []
    for c in old:
        prev = baseline_by_id[c['corpus'], c['id']]
        require(prev['raw'] == c['decision']['raw'] and prev['expected'] == c['expected'], 'ML Kit alignment')
        decision = prev['decisions']['B'] if 'decisions' in prev else prev['B']
        baseline_cases.append(dict(c, decision=decision))
        was_ready = matches(decision, c['expected']) and decision['action'] in ('translate', 'keep')
        now_ready = c['match'] and c['decision']['action'] in ('translate', 'keep')
        if was_ready and not now_ready: regressions.append(dict(corpus=c['corpus'], id=c['id'], raw=c['decision']['raw']))
        if now_ready and not was_ready: gains.append(dict(corpus=c['corpus'], id=c['id'], raw=c['decision']['raw']))
    measurements = []
    for r, s in zip(reports, supervision['runs']):
        require(s['exitCode'] == 0 and s['hardStop'] is None, 'supervisor completion')
        require(s['processLifetimePeakRssBytes'] >= r['measurements']['peakRssBytes'], 'whole process peak')
        measurements.append(dict(processPeakMiB=s['processLifetimePeakRssBytes']/1024**2,
            beforeDetectorPeakMiB=r['measurements']['memory'][0]['peakRssBytes']/1024**2,
            afterInitialPeakMiB=r['measurements']['memory'][1]['peakRssBytes']/1024**2,
            warmP95Ms=r['measurements']['warmP95Ms'], processSeconds=s['elapsedSeconds'],
            userCpuSeconds=s['userCpuSeconds'], systemCpuSeconds=s['systemCpuSeconds']))
    quality_gates = dict(zeroWrongOld=metrics['old82']['overall']['wrongAutomatic'] == 0,
                        zeroWrongNew=metrics['new32']['overall']['wrongAutomatic'] == 0,
                        oldPure=all(v['correctReady'] >= 8 for v in metrics['old82']['pure'].values()),
                        newPure=all(v['correctReady'] >= 3 for v in metrics['new32']['pure'].values()),
                        oldShort=metrics['old82']['contextualShort']['correctReady'] >= 6,
                        newShort=metrics['new32']['contextualShort']['correctReady'] >= 4)
    result = dict(schema=1, scope='synthetic fixed Mac host screening; not OCR, Android or translation acceptance',
        auditPassed=True, executionPassed=True, metrics=metrics, resources=measurements,
        qualityGates=quality_gates, qualityPassed=all(quality_gates.values()),
        resourcePassed=all(m['processPeakMiB'] <= 256 and m['warmP95Ms'] <= 250 for m in measurements),
        repeatability=dict(exactNonMeasurementReports={k:v for k,v in a.items() if k != 'measurements'} ==
                                                     {k:v for k,v in b.items() if k != 'measurements'},
            crossObservationCount=len(cross), crossExact=sum(d['exact'] for d in cross),
            crossSegmentsExact=all(d['segmentExact'] for d in cross),
            crossCandidateOrderExact=all(d['candidateOrderExact'] for d in cross),
            crossMaxAbsoluteScoreDelta=max(d['maxAbsoluteScoreDelta'] for d in cross),
            decisionsExact=[c['decision'] for c in a['cases']] == [c['decision'] for c in b['cases']],
            warmObservationCount=len(all_observations), warmExact=sum(d['exact'] for d in all_observations),
            warmMaxAbsoluteScoreDelta=max(d['maxAbsoluteScoreDelta'] for d in all_observations)),
        mlKitBComparison=dict(baseline=tally(baseline_cases), candidate=tally(old), readyRegressions=regressions, readyGains=gains))
    result['eligibleForAndroid'] = result['qualityPassed'] and result['resourcePassed'] and result['repeatability']['exactNonMeasurementReports']
    args.output.write_text(json.dumps(result, ensure_ascii=False, indent=2, sort_keys=True)+'\n')
    print(json.dumps(result, ensure_ascii=False, indent=2))


if __name__ == '__main__': main()
