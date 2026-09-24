#!/usr/bin/env python3
"""Exercise an already-started enhanced magnifier over KanDong's synthetic fixture only."""
import argparse
import json
import os
from pathlib import Path
import re
import subprocess
import time

PACKAGE = 'com.kandong.compat'
SERVICE = PACKAGE + '/.ProjectionMagnifierService'


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--serial', required=True)
    parser.add_argument('--out', type=Path, required=True)
    parser.add_argument('--cycles', type=int, default=20)
    args = parser.parse_args()
    # Refuse physical devices before making ANY adb call.
    if not re.fullmatch(r'emulator-\d+', args.serial):
        parser.error('Dedicated KanDong emulator required; physical devices are refused')
    if not 1 <= args.cycles <= 30 or args.out.exists():
        parser.error('cycles must be1..30 and output must not already exist')
    adb = os.environ.get('KANDONG_ADB', str(Path.home() / 'Library/Android/sdk/platform-tools/adb'))

    def call(*parts):
        return subprocess.check_output([adb, '-s', args.serial, *map(str, parts)],
                                       text=True, stderr=subprocess.DEVNULL, timeout=15)

    if call('emu', 'avd', 'name').splitlines()[0] != 'KanDong_Phase0_API36' or \
            call('shell', 'getprop', 'ro.kernel.qemu').strip() != '1':
        raise SystemExit('Wrong emulator; no interactions performed')

    def guard():
        lines = call('shell', 'dumpsys', 'activity', 'activities').splitlines()
        foreground = next((x for x in lines if 'ResumedActivity' in x), '')
        if 'com.kandong.fixture/' not in foreground:
            raise RuntimeError('Synthetic fixture must be the foreground activity')

    def state():
        raw = call('shell', 'dumpsys', 'activity', 'service', SERVICE)
        def value(name):
            found = re.search(r'(?<![\w])' + re.escape(name) + r'=([^\s]+)', raw)
            return found.group(1) if found else None
        if value('uiState') is None:
            return None
        result = {'state': value('uiState'), 'scale': float(value('scale'))}
        for key in ['crop', 'pan', 'bubble', 'viewport', 'bitmap', 'enhancedBitmap']:
            v = value(key)
            result[key] = list(map(float, v.split(','))) if v and v != 'none' else None
        for key in ['capturing', 'surfaceAttached', 'gpuOpen', 'clarityEnabled']:
            result[key] = value(key) == 'true'
        for key in ['copyAttempts', 'enhancedFrames', 'clarityFailures', 'gpuCleanupFailures']:
            result[key] = int(value(key))
        result['panelBottom'] = value('panelBottom') == 'true'
        result['tools'] = {m.group(1): list(map(int, m.group(2).split(',')))
                           for m in re.finditer(r'tool\[([^\]]+)\]=([\d,]+)', raw)}
        return result

    def wait(predicate, seconds=10):
        until = time.monotonic() + seconds
        while True:
            current = state()
            if current and predicate(current):
                return current
            if time.monotonic() >= until:
                raise RuntimeError('Timed out waiting for expected magnifier state')
            time.sleep(.1)

    def tap(box):
        guard()
        if not box or box[2] <= 0 or box[3] <= 0:
            raise RuntimeError('Invalid own control bounds')
        call('shell', 'input', 'tap', round(box[0]+box[2]/2), round(box[1]+box[3]/2))

    def tool(label):
        current = state()
        if current is None or current['state'] != 'EXPANDED':
            raise RuntimeError('Expanded own controls required')
        tap(current['tools'][label])

    def ready():
        return wait(lambda s: s['state'] == 'EXPANDED' and s['clarityEnabled'] and s['gpuOpen']
                    and s['capturing'] and s['enhancedBitmap'] == s['viewport']
                    and s['viewport'][0] > 0 and s['clarityFailures'] == s['gpuCleanupFailures'] == 0)

    def paused(mode):
        first = wait(lambda s: s['state'] == mode and not s['gpuOpen'] and not s['capturing']
                     and not s['surfaceAttached'] and s['bitmap'] == s['enhancedBitmap'] == [0, 0])
        time.sleep(1)  # The paused interval is itself the behavior being tested.
        last = state()
        if not last or last['state'] != mode or any(last[k] != first[k] for k in
                ['copyAttempts', 'enhancedFrames', 'gpuOpen', 'capturing', 'surfaceAttached',
                 'bitmap', 'enhancedBitmap', 'clarityFailures', 'gpuCleanupFailures']):
            raise RuntimeError('Paused resources or counters changed')
        return last

    def geometry(s):
        return {k: s[k] for k in ['crop', 'pan', 'scale', 'panelBottom']}

    report = {'scope': 'Dedicated API36 emulator, synthetic fixture only; not phone acceptance or energy',
              'state': 'running', 'requestedCycles': args.cycles, 'cycles': []}
    def save():
        args.out.parent.mkdir(parents=True, exist_ok=True)
        args.out.write_text(json.dumps(report, ensure_ascii=False, indent=2) + '\n')

    guard()  # No interaction unless the expected synthetic fixture is already visible.
    try:
        initial = geometry(ready())
        report['initialGeometry'] = initial
        for index in range(args.cycles):
            before = ready()['enhancedFrames']
            tool('收起放大镜'); snapshot = paused('COLLAPSED')
            tap(snapshot['bubble'])
            restored = ready()
            if geometry(restored) != initial:
                raise RuntimeError('Geometry changed across collapse')
            tool('打开菜单'); paused('MENU')
            guard(); call('shell', 'input', 'keyevent', '4')
            restored = ready()
            if geometry(restored) != initial or restored['enhancedFrames'] <= before:
                raise RuntimeError('Geometry or rendering did not recover after menu')
            memory = call('shell', 'dumpsys', 'meminfo', PACKAGE)
            match = re.search(r'TOTAL PSS:\s*(\d+)', memory)
            report['cycles'].append({'cycle': index+1, 'passed': True,
                                     'enhancedFrames': restored['enhancedFrames'],
                                     'pssKiB': int(match.group(1)) if match else None})
            save()
        tool('关闭放大镜')
        until = time.monotonic()+8
        while PACKAGE in call('shell', 'dumpsys', 'media_projection') or state() is not None:
            if time.monotonic() >= until:
                raise RuntimeError('Stop did not end projection/service')
            time.sleep(.1)
        windows = call('shell', 'dumpsys', 'window', 'windows')
        if any(title in windows for title in ['看懂悬浮球', '看懂菜单', '看懂取景', '看懂放大显示窗']):
            raise RuntimeError('An own overlay window remains after stop')
        report.update(state='completed', stopPassed=True)
    except Exception as error:
        report.update(state='failed', errorType=type(error).__name__)
        # Terminate only this own test service on the already-verified dedicated emulator.
        call('shell', 'am', 'stopservice', '-n', SERVICE)
        raise
    finally:
        save()
    print(json.dumps({'state': report['state'], 'cycles': len(report['cycles']),
                      'stopPassed': report.get('stopPassed', False)}))


if __name__ == '__main__':
    main()
