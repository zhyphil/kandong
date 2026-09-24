#!/usr/bin/env python3
"""Read-only KanDong observation. Saves whitelisted numbers, never raw device dumps."""
import argparse
import datetime as dt
import json
import math
import os
from pathlib import Path
import re
import statistics
import subprocess
import time

PACKAGE = 'com.kandong.compat'
SERVICE = PACKAGE + '/.ProjectionMagnifierService'


def number(pattern, text, integer=False):
    match = re.search(pattern, text, re.M)
    if not match:
        return None
    value = float(match.group(1))
    return (int(value) if integer else value) if math.isfinite(value) else None


def boolean(pattern, text):
    match = re.search(pattern, text, re.M)
    return match.group(1) == 'true' if match else None


def service_data(text):
    match = re.search(r'\buiState=(EXPANDED|COLLAPSED|MENU|TERMINAL)\b', text)
    result = {'state': match.group(1) if match else 'unavailable'}
    for key in ['capturing', 'surfaceAttached', 'clarityEnabled', 'gpuOpen']:
        result[key] = boolean(r'\b' + key + r'=(true|false)\b', text)
    for key in ['copyAttempts', 'renderedFrames', 'enhancedFrames', 'clarityFailures',
                'gpuCleanupFailures', 'virtualDisplayCreates', 'progress']:
        result[key] = number(r'\b' + key + r'=(\d+)\b', text, True)
    result['scale'] = number(r'\bscale=([\d.]+)\b', text)
    # Only dimensions, not source coordinates or UI labels.
    for key in ['viewport', 'enhancedBitmap', 'bitmap']:
        match = re.search(r'\b' + key + r'=(\d+),(\d+)\b', text)
        result[key] = [int(x) for x in match.groups()] if match else None
    # Used only in memory to detect a new session, never persisted.
    match = re.search(r'\bsession=([a-f0-9-]{36})\b', text)
    return result, match.group(1) if match else None


def battery_data(text):
    powered = [boolean(r'^\s*' + name + r': (true|false)', text)
               for name in ['AC powered', 'USB powered', 'Wireless powered']]
    temp = number(r'^\s*temperature:\s*(-?\d+)', text)
    level = number(r'^\s*level:\s*(\d+)', text)
    scale = number(r'^\s*scale:\s*(\d+)', text)
    return {
        'externalPower': True if True in powered else False if all(v is False for v in powered) else None,
        'temperatureC': temp / 10 if temp is not None else None,
        'levelPercent': level * 100 / scale if level is not None and scale else None,
        'status': number(r'^\s*status:\s*(\d+)', text, True),
        'updatesStopped': 'UPDATES STOPPED' in text.upper(),
    }


def thermal_data(text):
    return {'status': number(r'^\s*Thermal Status:\s*([0-6])\b', text, True),
            'halReady': boolean(r'^\s*HAL Ready:\s*(true|false)', text),
            'overridden': boolean(r'^\s*IsStatusOverride:\s*(true|false)', text)}


def memory_data(text):
    pss = number(r'\bTOTAL PSS:\s*(\d+)', text, True)
    if pss is None:
        pss = number(r'^\s*TOTAL\s+(\d+)\b', text, True)
    return {'pssKb': pss, 'rssKb': number(r'\bTOTAL RSS:\s*(\d+)', text, True),
            'swapPssKb': number(r'\bTOTAL SWAP PSS:\s*(\d+)', text, True)}


def active(sample):
    s = sample['service']
    return s['state'] == 'EXPANDED' and s['capturing'] is True and s['clarityEnabled'] is True


def aggregate(samples, interval):
    active_seconds = 0.0
    quiet_intervals = 0
    paused_intervals = 0
    paused_endpoint_activity = 0
    counter_regressions = 0
    errors = 0
    for left, right in zip(samples, samples[1:]):
        gap = right['elapsedSeconds'] - left['elapsedSeconds']
        if gap > interval * 1.8 or left['sessionIndex'] is None or left['sessionIndex'] != right['sessionIndex']:
            continue
        a, b = left['service'], right['service']
        if active(left) and active(right):
            active_seconds += gap
            if a['enhancedFrames'] is not None and b['enhancedFrames'] == a['enhancedFrames']:
                quiet_intervals += 1 # Static content is possible; NOT labelled a dropped frame.
        if a['state'] == b['state'] and a['state'] in ['MENU', 'COLLAPSED']:
            paused_intervals += 1
            # Equal endpoint modes do not prove uninterrupted pause: the user can
            # resume and collapse again between these sparse samples.
            if a['copyAttempts'] is not None and b['copyAttempts'] is not None and b['copyAttempts'] != a['copyAttempts']:
                paused_endpoint_activity += 1
        for key in ['copyAttempts', 'enhancedFrames']:
            if a[key] is not None and b[key] is not None and b[key] < a[key]:
                counter_regressions += 1
        for key in ['clarityFailures', 'gpuCleanupFailures']:
            if a[key] is not None and b[key] is not None:
                errors += max(0, b[key] - a[key])
    def values(group, key):
        return [s[group][key] for s in samples if s[group][key] is not None]
    def span(values):
        return {'first': values[0], 'last': values[-1], 'min': min(values), 'max': max(values)} if values else None
    temps = [s['battery']['temperatureC'] for s in samples
             if s['battery']['temperatureC'] is not None and not s['battery']['updatesStopped']]
    thermal = [s['thermal']['status'] for s in samples if s['thermal']['status'] is not None
               and s['thermal']['halReady'] is True and s['thermal']['overridden'] is False]
    return {'sampleCount': len(samples), 'activeObservedSeconds': round(active_seconds, 2),
            'activeSamples': sum(active(s) for s in samples),
            'unavailableServiceSamples': sum(s['service']['state'] == 'unavailable' for s in samples),
            'newRenderOrCleanupErrors': errors, 'counterRegressionsWithinSession': counter_regressions,
            'pausedIntervals': paused_intervals, 'pausedEndpointsWithActivityBetween': paused_endpoint_activity,
            'pausedSnapshotsWithGpuOpen': sum(s['service']['state'] in ['MENU', 'COLLAPSED'] and s['service']['gpuOpen'] is True for s in samples),
            'activeIntervalsWithoutNewEnhancedFrame': quiet_intervals,
            'batteryTemperatureC': span(temps), 'pssKb': span(values('memory', 'pssKb')),
            'maxReportedThermalStatus': max(thermal) if thermal else None,
            'externalPowerSamples': sum(s['battery']['externalPower'] is True for s in samples),
            'levelPercent': span(values('battery', 'levelPercent')),
            'energyVerdict': 'not_measured_no_app_energy_attribution',
            'observedSessions': len({s['sessionIndex'] for s in samples if s['sessionIndex'] is not None})}


def self_test():
    tests = 0
    def check(condition):
        nonlocal tests
        assert condition
        tests += 1
    s, session = service_data('uiState=COLLAPSED session=00000000-0000-0000-0000-000000000001 '
                              'clarityEnabled=true gpuOpen=false enhancedBitmap=0,0 secret=PRIVATE')
    check(s['progress'] is None and s['state'] == 'COLLAPSED' and s['gpuOpen'] is False)
    check('PRIVATE' not in json.dumps(s) and session is not None)
    check(service_data('No services match')[0]['capturing'] is None)
    b = battery_data('AC powered: true\n USB powered: false\n Wireless powered: false\n temperature: 280\n level: 86\n scale: 100')
    check(b['externalPower'] is True and b['temperatureC'] == 28 and b['levelPercent'] == 86)
    check(battery_data('permission denied')['temperatureC'] is None)
    check(battery_data('USB powered: false')['externalPower'] is None)
    check(battery_data('UPDATES STOPPED')['updatesStopped'])
    check(memory_data('TOTAL 44164 10044\nTOTAL PSS: 45000 TOTAL RSS: 98000')['pssKb'] == 45000)
    check(memory_data(' TOTAL 44164 10044')['pssKb'] == 44164)
    check(thermal_data('Thermal Status: 0\nHAL Ready: false')['halReady'] is False)
    def sample(t, count, mode='EXPANDED', index=1):
        service, _ = service_data('uiState=' + mode + ' capturing=true clarityEnabled=true gpuOpen=false '
                                 'copyAttempts=' + str(count) + ' enhancedFrames=' + str(count) +
                                 ' clarityFailures=0 gpuCleanupFailures=0')
        return {'elapsedSeconds': t, 'sessionIndex': index, 'service': service,
                'battery': b, 'memory': memory_data('TOTAL PSS: 45000'), 'thermal': thermal_data('')}
    summary = aggregate([sample(0, 10), sample(15, 20), sample(30, 0, index=2)], 15)
    check(summary['activeObservedSeconds'] == 15 and summary['counterRegressionsWithinSession'] == 0)
    check(summary['maxReportedThermalStatus'] is None and summary['energyVerdict'].startswith('not_measured'))
    check(aggregate([sample(0, 10), sample(40, 20)], 15)['activeObservedSeconds'] == 0)
    check(aggregate([sample(0, 10, 'COLLAPSED'), sample(15, 10, 'COLLAPSED')], 15)['pausedEndpointsWithActivityBetween'] == 0)
    check(aggregate([sample(0, 10, 'COLLAPSED'), sample(15, 11, 'COLLAPSED')], 15)['pausedEndpointsWithActivityBetween'] == 1)
    print(f'{tests} offline parser/aggregation checks passed')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--self-test', action='store_true')
    parser.add_argument('--serial')
    parser.add_argument('--adb', default=os.environ.get('KANDONG_ADB', str(Path.home() / 'Library/Android/sdk/platform-tools/adb')))
    parser.add_argument('--expect-model', default='LIO-AN00')
    parser.add_argument('--minutes', type=float, default=15)
    parser.add_argument('--interval', type=float, default=15)
    parser.add_argument('--out', type=Path)
    args = parser.parse_args()
    if args.self_test:
        self_test(); return
    if not args.serial or args.out is None:
        parser.error('--serial and --out are required')
    if not (0 <= args.minutes <= 30 and 5 <= args.interval <= 60):
        parser.error('minutes must be0..30 and interval5..60 seconds')
    if args.out.exists():
        parser.error('Output already exists; choose a fresh report path')
    def shell(*parts):
        try:
            return subprocess.check_output([args.adb, '-s', args.serial, 'shell', *parts],
                                           text=True, stderr=subprocess.DEVNULL, timeout=12)
        except (subprocess.SubprocessError, OSError):
            return '' # Unknown stays unknown. Do not persist raw error/device identifiers.
    model = shell('getprop', 'ro.product.model').strip()
    if model != args.expect_model:
        raise SystemExit('Expected test device is unavailable or model does not match')
    package = shell('dumpsys', 'package', PACKAGE)
    version = re.search(r'versionName=([\w.\-]+)', package)
    samples, sessions = [], {}
    report = {'schema': 1, 'model': model, 'api': number(r'^(\d+)', shell('getprop', 'ro.build.version.sdk'), True),
              'appVersion': version.group(1) if version else None,
              'startedAtUtc': dt.datetime.now(dt.timezone.utc).isoformat(),
              'requestedSeconds': args.minutes * 60, 'intervalSeconds': args.interval,
              'state': 'running', 'samples': samples,
              'limits': ['Observer overhead; sparse samples cannot prove absence of all crashes or leaks',
                         'Battery temperature is not skin temperature or app-attributed heat',
                         'Frame counter differences are not display FPS or capture-to-display latency',
                         'No energy/power estimate; external charging and other apps are confounders',
                         'No phone interaction, screen capture, UI text, raw dump, serial, or settings changes']}
    def save():
        report['summary'] = aggregate(samples, args.interval)
        args.out.parent.mkdir(parents=True, exist_ok=True)
        temporary = args.out.with_suffix('.partial')
        temporary.write_text(json.dumps(report, ensure_ascii=False, indent=2, allow_nan=False) + '\n')
        temporary.replace(args.out)
    start = time.monotonic()
    try:
        while True:
            began = time.monotonic()
            state, identity = service_data(shell('dumpsys', 'activity', 'service', SERVICE))
            if identity is not None and identity not in sessions:
                sessions[identity] = len(sessions) + 1
            sample = {'elapsedSeconds': round(began - start, 3),
                      'atUtc': dt.datetime.now(dt.timezone.utc).isoformat(),
                      'sessionIndex': sessions.get(identity), 'service': state,
                      'battery': battery_data(shell('dumpsys', 'battery')),
                      'thermal': thermal_data(shell('dumpsys', 'thermalservice')),
                      'memory': memory_data(shell('dumpsys', 'meminfo', PACKAGE))}
            sample['queryDurationSeconds'] = round(time.monotonic() - began, 3)
            samples.append(sample); save()
            print(json.dumps({'elapsed': sample['elapsedSeconds'], 'state': state['state'],
                              'enhancedFrames': state['enhancedFrames'], 'batteryC': sample['battery']['temperatureC'],
                              'pssKb': sample['memory']['pssKb'], 'thermal': sample['thermal']['status']}, ensure_ascii=False), flush=True)
            if sample['thermal']['status'] is not None and sample['thermal']['status'] >= 3 and sample['thermal']['halReady'] is True and sample['thermal']['overridden'] is False:
                report['state'] = 'thermal_attention'; break
            if sample['elapsedSeconds'] >= args.minutes * 60:
                report['state'] = 'completed'; break
            time.sleep(max(0, min(args.interval, args.minutes * 60 - (time.monotonic() - start))))
    except KeyboardInterrupt:
        report['state'] = 'interrupted'
    finally:
        report['finishedAtUtc'] = dt.datetime.now(dt.timezone.utc).isoformat()
        save()
    print(json.dumps(report['summary'], ensure_ascii=False), flush=True)
    if report['state'] != 'completed':
        raise SystemExit(2)


if __name__ == '__main__':
    main()
