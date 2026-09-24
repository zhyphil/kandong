#!/usr/bin/env python3
"""Fixed Mac-only Lingua screening. Parent enforces offline sandbox and per-process budgets."""
import argparse
import errno
import gzip
import hashlib
import importlib.metadata
import json
import math
import os
from pathlib import Path
import platform
import resource
import socket
import subprocess
import sys
import time
import traceback
import zipfile
from lingua_candidate import (ObservationSession, page_id, queries, route, validate_evidence,
                              segment_language)

ROOT = Path(__file__).resolve().parents[1]
WHEEL = 'lingua_language_detector-2.2.0-cp314-cp314-macosx_11_0_arm64.whl'
WHEEL_SHA = '066b56ca4e3bd324b4c76a861ab2b747d2d8d4e6eda0a4cf06291c6c039b90f4'
FIXTURES = {
    'v1': ('language-routing-v1.json', '733f883e4e974f110b201ceb9307d648f53bc14b2e6b46719a5a097eb33d6e7c'),
    'v2': ('language-context-v2.json', 'fbc29c9fd6fa34680ad1ece4f86b74e4f57b6316548046f45b63b7efaac20d76'),
    'v3': ('language-lingua-v3.json', 'e2319026a3d8370c205e0c516aedfcb5e41d66f87f1c259c2f68b6c2dbddd59d'),
}
MAX_BYTES = 1024**3
MAX_SECONDS = 600


def check(condition, message):
    if not condition: raise RuntimeError(message)


def sha(path):
    with open(path, 'rb') as f: return hashlib.file_digest(f, 'sha256').hexdigest()


def save(path, value, zipped=False):
    encoded = json.dumps(value, ensure_ascii=False, sort_keys=True, indent=2, allow_nan=False).encode()
    tmp = path.with_name(path.name + '.tmp')
    tmp.write_bytes(gzip.compress(encoded, mtime=0) if zipped else encoded)
    tmp.replace(path)


def peak_rss_bytes():
    # Darwin ru_maxrss is bytes. Current RSS is sampled by the external supervisor.
    return resource.getrusage(resource.RUSAGE_SELF).ru_maxrss


def load_cases():
    cases = []
    for corpus, (name, digest) in FIXTURES.items():
        path = ROOT / 'docs/fixtures' / name
        check(sha(path) == digest, 'fixture changed: ' + name)
        for original in json.loads(path.read_text())['cases']:
            c = dict(original, corpus=corpus)
            if corpus == 'v1':
                c['blocks'] = [dict(id='target', groupId='single', text=c.pop('text'), ocrConflict=False)]
                c.update(pageId=page_id(c['blocks']), targetId='target', category=c.pop('group'))
            queries(c)
            cases.append(c)
    check(len(cases) == 114, 'case count')
    return cases


def match(decision, expected):
    return decision['action'] == expected['action'] and decision['language'] == expected.get('language')


def summary(cases):
    result = {}
    for corpus in FIXTURES:
        selected = [c for c in cases if c['corpus'] == corpus]
        result[corpus] = dict(total=len(selected), matching=sum(c['match'] for c in selected),
            wrongAutomatic=sum(not c['match'] and c['decision']['action'] != 'review' for c in selected),
            correctReview=sum(c['match'] and c['decision']['action'] == 'review' for c in selected),
            correctReady=sum(c['match'] and c['decision']['action'] in ('keep', 'translate') for c in selected),
            correctLiteral=sum(c['match'] and c['decision']['action'] == 'keep-literal' for c in selected),
            missedReady=sum(c['decision']['action'] == 'review' and c['expected']['action'] in ('keep', 'translate')
                            for c in selected))
    return result


def worker(args):
    report = dict(schema=1, configuration='all75-high-accuracy-lazy-rayon1-policyC-frozen26c176c',
                  fixtureSha256={c: h for c, (_, h) in FIXTURES.items()}, cases=[], unicodeControls=[],
                  executionPassed=False, status='running', errors=[], clientsOpened=0, clientsUnloaded=0,
                  measurements=dict(calls=[], memory=[]))
    output = args.output / ('report-' + args.worker + '.json.gz')
    started = time.monotonic()
    session = None
    try:
        check(platform.system() == 'Darwin' and platform.machine() == 'arm64', 'fixed Mac ARM64 host only')
        check(os.environ.get('RAYON_NUM_THREADS') == '1', 'thread configuration')
        check(sha(args.intake / WHEEL) == WHEEL_SHA, 'wheel identity')
        check(importlib.metadata.version('lingua-language-detector') == '2.2.0', 'installed version')
        # A failed loopback bind with EPERM checks that OS deny-network is effective. No remote request.
        with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as sock:
            try:
                sock.bind(('127.0.0.1', 0))
            except OSError as error:
                check(error.errno == errno.EPERM, 'unexpected sandbox network error')
                report['offline'] = dict(osSandbox='deny network*', loopbackBindErrno=error.errno)
            else:
                raise RuntimeError('OS network sandbox was not active')
        import lingua
        native = next(Path(lingua.__file__).parent.glob('lingua*.so'))
        with zipfile.ZipFile(args.intake / WHEEL) as z:
            member = next(n for n in z.namelist() if n.endswith('.so'))
            with z.open(member) as binary: expected_native = hashlib.file_digest(binary, 'sha256').hexdigest()
        check(sha(native) == expected_native, 'installed native binary differs from fixed wheel')
        languages = sorted(l.iso_code_639_1.name.lower() for l in lingua.Language.all())
        check(len(languages) == 75, 'all languages')
        report['runtime'] = dict(python=platform.python_version(), os=platform.platform(), machine=platform.machine(),
                                 packageVersion='2.2.0', wheelSha256=WHEEL_SHA, nativeSha256=expected_native,
                                 languages=languages, indexUnit='Unicode code points (Python binding)')
        report['measurements']['memory'].append(dict(stage='before-detector', peakRssBytes=peak_rss_bytes()))
        detector = lingua.LanguageDetectorBuilder.from_all_languages().build()
        report['clientsOpened'] = 1
        report['measurements']['initializationThroughBuildSeconds'] = time.monotonic() - started

        def convert(d, raw, phase='initial'):
            start = time.monotonic()
            confidence = d.compute_language_confidence_values(raw)
            middle = time.monotonic()
            segments = d.detect_multiple_languages_of(raw)
            end = time.monotonic()
            report['measurements']['calls'].append(dict(rawSha256=hashlib.sha256(raw.encode()).hexdigest(),
                phase=phase, confidenceMs=(middle-start)*1000, segmentationMs=(end-middle)*1000,
                totalMs=(end-start)*1000))
            check(resource.getrusage(resource.RUSAGE_SELF).ru_maxrss <= MAX_BYTES, '1GiB hard stop')
            check(time.monotonic()-started <= MAX_SECONDS, '10min hard stop')
            return dict(raw=raw,
                candidates=[dict(language=v.language.iso_code_639_1.name.lower(), score=v.value) for v in confidence],
                segments=[dict(start=s.start_index, end=s.end_index, wordCount=s.word_count,
                    language=s.language.iso_code_639_1.name.lower(), raw=raw[s.start_index:s.end_index]) for s in segments])

        session = ObservationSession(detector, convert)
        with session:
            for case in load_cases():
                # Only the validated synthetic page payload crosses the model/policy boundary.
                payload = {k: case[k] for k in ('pageId', 'targetId', 'blocks')}
                observations = session.observe(payload)
                validate_evidence(payload, observations)
                decision = route(payload, observations)
                report['cases'].append(dict(case, observations=observations, decision=decision,
                                            match=match(decision, case['expected'])))
                if len(report['cases']) % 10 == 0:
                    save(output, report, True)
                    print('completed', len(report['cases']), 'pages', flush=True)
            report['uniqueInitialInferences'] = len(session.cache)
            report['measurements']['memory'].append(dict(stage='after-initial', peakRssBytes=peak_rss_bytes()))
            # Real repeat calls bypass the cache, after every used model has already been queried.
            report['warmObservations'] = []
            for raw, expected in session.cache.items():
                actual = convert(detector, raw, 'warm')
                from lingua_candidate import validate_observation
                validate_observation(actual, raw)
                report['warmObservations'].append(dict(observation=actual, exactlyRepeated=actual == expected))
            report['warmOutputsIdentical'] = all(o['exactlyRepeated'] for o in report['warmObservations'])
            for raw in ['🙂漢字', '🙂Échéance non remboursable', '🙂请勿重复付款']:
                observation = convert(detector, raw, 'unicode-control')
                from lingua_candidate import validate_observation
                validate_observation(observation, raw)
                check(segment_language(observation)[1] != 'uncovered-letters', 'unicode letters missing')
                check(all(0 <= s['start'] < s['end'] <= len(raw) for s in observation['segments'])
                      and max((s['end'] for s in observation['segments']), default=0) == len(raw),
                      'Python binding unicode offset check')
                report['unicodeControls'].append(observation)
            report['measurements']['memory'].append(dict(stage='before-unload', peakRssBytes=peak_rss_bytes()))
        report['status'] = 'complete'
        report['executionPassed'] = True
    except Exception:
        report['status'] = 'failed'
        report['errors'].append(traceback.format_exc())
    finally:
        if session is not None:
            session.close()
            report['clientsUnloaded'] = session.unloaded
            report['cacheCleared'] = not session.cache
        report['measurements']['wallSeconds'] = time.monotonic() - started
        report['measurements']['peakRssBytes'] = resource.getrusage(resource.RUSAGE_SELF).ru_maxrss
        report['measurements']['memory'].append(dict(stage='after-unload', peakRssBytes=peak_rss_bytes()))
        warm = sorted(c['totalMs'] for c in report['measurements']['calls'] if c['phase'] == 'warm')
        report['measurements']['warmP95Ms'] = warm[math.ceil(len(warm)*.95)-1] if warm else None
        report['summary'] = summary(report['cases'])
        report['resourceEligible'] = bool(warm) and report['measurements']['peakRssBytes'] <= 256*1024**2 \
            and report['measurements']['warmP95Ms'] <= 250
        save(output, report, True)
        print(json.dumps({k: report[k] for k in ('status', 'executionPassed', 'resourceEligible', 'summary', 'errors')},
                         ensure_ascii=False), flush=True)
    return 0 if report['executionPassed'] else 1


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('--intake', type=Path, required=True)
    p.add_argument('--output', type=Path, required=True)
    p.add_argument('--worker', choices=['1', '2'])
    args = p.parse_args()
    args.intake = args.intake.resolve(); args.output = args.output.resolve()
    args.output.mkdir(parents=True, exist_ok=True)
    if args.worker: return worker(args)
    check(platform.system() == 'Darwin', 'macOS sandbox required')
    python = args.intake / 'venv/bin/python'
    profile = '(version 1)(allow default)(deny network*)'
    runs = []
    for number in ('1', '2'):
        cmd = ['/usr/bin/sandbox-exec', '-p', profile, str(python), '-B', str(Path(__file__).resolve()),
               '--intake', str(args.intake), '--output', str(args.output), '--worker', number]
        with (args.output / ('run-' + number + '.txt')).open('w') as log:
            process = subprocess.Popen(cmd, stdout=log, stderr=subprocess.STDOUT,
                                       env=dict(os.environ, RAYON_NUM_THREADS='1', PYTHONDONTWRITEBYTECODE='1'))
            start = time.monotonic(); peak = 0; stop = None; samples = []
            usage = None
            while True:
                ended, status, usage = os.wait4(process.pid, os.WNOHANG)
                if ended:
                    process.returncode = os.waitstatus_to_exitcode(status)
                    break
                measured = subprocess.run(['/bin/ps', '-o', 'rss=', '-p', str(process.pid)],
                                          text=True, capture_output=True)
                if measured.returncode == 0 and measured.stdout.strip():
                    rss = int(measured.stdout.strip())*1024
                    peak = max(peak, rss)
                    samples.append(dict(elapsedSeconds=time.monotonic()-start, rssBytes=rss))
                if peak > MAX_BYTES or time.monotonic()-start > MAX_SECONDS:
                    stop = 'rss>1GiB' if peak > MAX_BYTES else 'time>10min'
                    process.kill()
                    _, status, usage = os.wait4(process.pid, 0)
                    process.returncode = os.waitstatus_to_exitcode(status)
                    break
                time.sleep(.25)
        runs.append(dict(round=number, exitCode=process.returncode, externalSampledPeakRssBytes=peak,
                         elapsedSeconds=time.monotonic()-start, hardStop=stop, samples=samples,
                         processLifetimePeakRssBytes=usage.ru_maxrss,
                         userCpuSeconds=usage.ru_utime, systemCpuSeconds=usage.ru_stime))
        save(args.output / 'supervision.json', dict(profile=profile, pollSeconds=.25, runs=runs))
        print(json.dumps({k: v for k, v in runs[-1].items() if k != 'samples'}), flush=True)
    return 0 if all(r['exitCode'] == 0 for r in runs) else 1


if __name__ == '__main__':
    sys.exit(main())
