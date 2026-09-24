#!/usr/bin/env python3
"""Run only the frozen synthetic language probe on KanDong's dedicated emulator."""
import argparse, hashlib, json, subprocess
from pathlib import Path

root = Path(__file__).resolve().parents[1]
p = argparse.ArgumentParser()
p.add_argument('--out', type=Path, required=True, help='new evidence directory')
args = p.parse_args()
identity = json.loads((root / 'docs/evidence/language-routing/2026-09-24/apk-identity.json').read_text())
adb = ['/Users/haoyuzuo/Library/Android/sdk/platform-tools/adb', '-s', 'emulator-5582']
def run(*parts, binary=False, timeout=30):
    return subprocess.check_output(adb + list(parts), text=not binary, timeout=timeout)
def guard():
    assert run('emu', 'avd', 'name').splitlines()[0] == 'KanDong_OCR_API37_16K'
    assert run('shell', 'getprop', 'ro.build.version.sdk').strip() == '37'
    assert run('shell', 'getconf', 'PAGE_SIZE').strip() == '16384'
guard()
for kind, package in [('main', 'com.kandong.modelprobe'), ('test', 'com.kandong.modelprobe.test')]:
    paths = run('shell', 'pm', 'path', package).strip().splitlines()
    assert len(paths) == 1 and paths[0].startswith('package:/data/app/')
    apk = run('exec-out', 'cat', paths[0].removeprefix('package:'), binary=True)
    assert hashlib.sha256(apk).hexdigest() == identity[kind]['sha256'], 'APK_IDENTITY_MISMATCH'
args.out.mkdir(parents=True, exist_ok=False)
(args.out / 'installed.json').write_text(json.dumps(identity, indent=2) + '\n')
classes = ','.join('com.kandong.modelprobe.' + c for c in
                   ['LanguageRouteTest', 'OnDemandTranslationTest', 'LanguageIdentificationProbeTest'])
reports = []
try:
    for n in (1, 2):
        guard()
        run('shell', 'am', 'force-stop', 'com.kandong.modelprobe')
        # Delete only our previous synthetic report to prevent accepting stale output.
        run('shell', 'run-as', 'com.kandong.modelprobe', 'rm', '-f', 'files/language-routing-probe-report.json')
        log = run('shell', 'am', 'instrument', '-w', '-r', '-e', 'class', classes,
                  'com.kandong.modelprobe.test/androidx.test.runner.AndroidJUnitRunner', timeout=180)
        (args.out / f'android-{n}.txt').write_text(log)
        raw = run('exec-out', 'run-as', 'com.kandong.modelprobe', 'cat', 'files/language-routing-probe-report.json', binary=True)
        (args.out / f'report-{n}.json').write_bytes(raw)
        report = json.loads(raw)
        assert 'OK (15 tests)' in log and 'FAILURES!!!' not in log, 'INSTRUMENTATION_FAILED'
        assert report['technicalPassed'] and report['status'] == 'complete'
        assert report['clientsOpened'] == report['clientsClosed'] == 1
        assert len(report['cases']) == 38 and not report['errors']
        assert all(not item['granted'] for item in report['permissions'])
        if reports: assert report['runId'] != reports[0]['runId']
        reports.append(report)
        print(json.dumps({'round': n, 'technicalPassed': True, 'qualityAccepted': report['qualityAccepted'],
                          'qualityCorrect': report['qualityCorrectCount'], 'wrongAutomaticRoutes': report['wrongAutomaticRoutes']}, ensure_ascii=False), flush=True)
    def stable(report):
        return [{k: v for k, v in row.items() if k != 'inferenceMs'} for row in report['cases']]
    assert stable(reports[0]) == stable(reports[1]), 'NONDETERMINISTIC_LANGUAGE_EVIDENCE'
    summary = {'rounds': 2, 'testsPerRound': 15, 'casesPerRound': 38, 'evidenceExactlyRepeatable': True,
               'qualityAccepted': reports[0]['qualityAccepted'], 'qualityCorrect': reports[0]['qualityCorrectCount'],
               'wrongAutomaticRoutes': reports[0]['wrongAutomaticRoutes']}
    (args.out / 'run-summary.json').write_text(json.dumps(summary, indent=2) + '\n')
finally:
    run('shell', 'am', 'force-stop', 'com.kandong.modelprobe')
