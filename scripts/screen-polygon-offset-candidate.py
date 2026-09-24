#!/usr/bin/env python3
"""Host-only, offline Clipper candidate comparison. Does not change Android dependencies.
Authenticates an existing upstream source checkout and compiles temporary variants.
Exact output vertices are compared modulo cyclic start, winding and path order.
"""
from pathlib import Path
import argparse
import hashlib
import importlib.metadata
import json
import math
import re
import subprocess


def digest(raw):
    return hashlib.sha256(raw).hexdigest()


def canonical(path):
    points = [tuple(p) for p in path]
    assert points
    return min(tuple(q[i:] + q[:i]) for q in [points, points[::-1]] for i in range(len(q)))


DRIVER = r'''import de.lighti.clipper.*;
import de.lighti.clipper.Point.LongPoint;
import java.io.*;
public class Candidate {
  public static void main(String[] args) throws Exception {
    BufferedReader reader = new BufferedReader(new InputStreamReader(System.in, "UTF-8"));
    String line;
    while ((line = reader.readLine()) != null) {
      String[] fields = line.split("\\|");
      System.out.print(fields[0] + "|");
      try {
        ClipperOffset offset = new ClipperOffset(2.0, 0.25);
        for (String shape : fields[2].split("/")) {
          Path path = new Path();
          for (String point : shape.split(";")) {
            String[] xy = point.split(",");
            path.add(new LongPoint((long)Double.parseDouble(xy[0]), (long)Double.parseDouble(xy[1])));
          }
          offset.addPath(path, Clipper.JoinType.ROUND, Clipper.EndType.CLOSED_POLYGON);
        }
        Paths result = new Paths();
        offset.execute(result, Double.parseDouble(fields[1]));
        boolean firstPath = true;
        for (Path path : result) {
          if (!firstPath) System.out.print("/");
          firstPath = false;
          boolean firstPoint = true;
          for (LongPoint point : path) {
            if (!firstPoint) System.out.print(";");
            firstPoint = false;
            System.out.print(point.getX() + "," + point.getY());
          }
        }
      } catch (RuntimeException error) { System.out.print("ERROR:" + error.getClass().getName()); }
      System.out.println();
    }
  }
}
'''


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--root', type=Path, required=True)
    parser.add_argument('--source', type=Path, required=True)
    parser.add_argument('--jdk', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    root, source, out = args.root.resolve(), args.source.resolve(), args.output.resolve()
    assert out != root and root not in out.parents and out != source and source not in out.parents
    out.mkdir(parents=True, exist_ok=True)
    assert not any(out.iterdir()), 'Never overwrite a prior run'
    versions = {'numpy': '2.5.3', 'pyclipper': '1.4.0', 'shapely': '2.1.2'}
    assert {k: importlib.metadata.version(k) for k in versions} == versions
    import numpy as np
    import pyclipper
    from shapely.geometry import Polygon

    provenance = json.loads((root/'docs/evidence/polygon-offset/2026-09-24/provenance.json').read_bytes())
    assert provenance['commit'] == '5ef8c0a467023c495e44e582e9cbd8ca7308a590'
    names = {Path(x['path']).name for x in provenance['sourceFiles']}
    assert len(names) == 11 and {p.name for p in source.glob('*.java')} == names
    upstream = {}
    for meta in provenance['sourceFiles']:
        name = Path(meta['path']).name
        raw = (source/name).read_bytes()
        assert len(raw) == meta['bytes'] and digest(raw) == meta['sha256'], name
        upstream[name] = raw.decode('utf-8')
    raw = (root/'docs/fixtures/detector-geometry-v1/manifest.json').read_bytes()
    assert digest(raw) == '1aa14f2efb50e62c5a2d95b45b7d5b5044d4ec90afa47a976e8dea50bbe466a6'
    manifest = json.loads(raw)
    cases = []

    def add(identifier, points, category):
        points = np.asarray(points, dtype=np.float32)
        assert points.shape == (4, 2) and np.isfinite(points).all() and np.max(np.abs(points)) < 8192
        polygon = Polygon(points)
        integer_polygon = Polygon(points.astype(np.int64))
        assert polygon.is_valid and polygon.area > 0 and polygon.length > 0
        assert integer_polygon.is_valid and integer_polygon.area > 0
        cases.append(dict(id=identifier, category=category, paths=[points.tolist()],
                          distance=polygon.area * 1.6 / polygon.length))

    for kernel in manifest['unclipKernels']:
        add(kernel['id'], kernel['input'], 'frozen-micro')
    for case in manifest['cases']:
        for box in case['boxes']:
            add(box['id'], box['quad'], 'frozen-final-quad-not-pre-unclip')
    for i in range(1, 17):
        side = (2 * i + 1) * 1.25
        for n, (x, y) in enumerate([(0, 0), (0.5, 0.5), (-8.5, -8.5), (4090, 4090)]):
            square = [[x, y], [x+side, y], [x+side, y+side], [x, y+side]]
            for reverse in [False, True]:
                add(f'half-{i}-{n}-{int(reverse)}', square[::-1] if reverse else square, 'half-tie-squares')
    rng = np.random.default_rng(20260924)
    for i in range(512):
        a = float(rng.uniform(0, math.pi))
        w, h = float(rng.uniform(4, 512)), float(rng.uniform(4, 128))
        cx, cy = [(0, 0), (2.5, 3.5), (2048, 2048), (4090, 4090)][i % 4]
        q = [[cx+x*math.cos(a)-y*math.sin(a), cy+x*math.sin(a)+y*math.cos(a)]
             for x, y in [(-w/2, -h/2), (w/2, -h/2), (w/2, h/2), (-w/2, h/2)]]
        q = q[i % 4:] + q[:i % 4]
        add(f'rotated-{i}', q[::-1] if i % 2 else q, 'seeded-rotated-rectangles')
    for i, width in enumerate([1.01, 1.49, 1.99, 2.01, 2.49, 2.99, 3.01]):
        for j, height in enumerate([1.01, 3.01, 16.25, 1024.0]):
            add(f'small-{i}-{j}', [[0, 0], [width, 0], [width, height], [0, height]], 'small-or-thin')
    # Generic offset probes, explicitly separate from the single-rectangle OCR contract.
    square = [[0, 0], [10, 0], [10, 10], [0, 10]]
    for i, shift in enumerate([0, 5, 10, 11, 16, 32]):
        paths = [square, [[x+shift, y] for x, y in square]]
        cases.append(dict(id=f'multi-{i}', category='general-multipath-offset', paths=paths, distance=2.5))
    assert len({x['id'] for x in cases}) == len(cases) == 689
    input_lines = []
    for case in cases:
        offset = pyclipper.PyclipperOffset(2.0, 0.25)
        offset.AddPaths(case['paths'], pyclipper.JT_ROUND, pyclipper.ET_CLOSEDPOLYGON)
        case['expected'] = offset.Execute(case['distance'])
        encoded = '/'.join(';'.join(f'{x},{y}' for x, y in path) for path in case['paths'])
        input_lines.append(f"{case['id']}|{case['distance']}|{encoded}")
    frozen = (json.dumps(cases, indent=2)+'\n').encode()
    (out/'cases.json').write_bytes(frozen)  # Frozen before any candidate execution.
    (out/'Candidate.java').write_text(DRIVER)
    all_results = {}
    for variant in ['original', 'offset-round', 'all-round']:
        folder = out/variant
        src, classes = folder/'source', folder/'classes'
        src.mkdir(parents=True); classes.mkdir()
        edits = {}
        for name, original in upstream.items():
            text = original
            targeted = variant == 'all-round' or (variant == 'offset-round' and name == 'ClipperOffset.java')
            count = len(re.findall(r'Math\s*\.\s*round\(', text)) if targeted else 0
            if count:
                text = re.sub(r'Math\s*\.\s*round\(', 'roundAway(', text)
                pattern = r'((?:public )?class \w+(?: extends \w+)? \{)'
                text, inserted = re.subn(pattern, r'\1\n    private static long roundAway(double x) { return (long)(x < 0 ? x - 0.5 : x + 0.5); }', text, count=1)
                assert inserted == 1
                edits[name] = count
            (src/name).write_text(text)
        compile_result = subprocess.run([str(args.jdk/'bin/javac'), '--release', '8', '-sourcepath', str(classes),
            '-d', str(classes), *map(str, sorted(src.glob('*.java'))), str(out/'Candidate.java')],
            capture_output=True, text=True, timeout=30)
        (folder/'compile.txt').write_text(compile_result.stdout+compile_result.stderr)
        compile_result.check_returncode()
        run = subprocess.run([str(args.jdk/'bin/java'), '-Xmx128m', '-cp', str(classes), 'Candidate'],
            input='\n'.join(input_lines)+'\n', capture_output=True, text=True, timeout=30)
        (folder/'stdout.txt').write_text(run.stdout); (folder/'stderr.txt').write_text(run.stderr)
        run.check_returncode()
        actual = {}
        for line in run.stdout.splitlines():
            identifier, value = line.split('|')
            assert identifier not in actual
            actual[identifier] = value if value.startswith('ERROR:') else [
                [list(map(int, xy.split(','))) for xy in path.split(';')] for path in value.split('/') if path]
        assert actual.keys() == {x['id'] for x in cases}
        rows = []
        for case in cases:
            result = actual[case['id']]
            passed = isinstance(result, list) and sorted(map(canonical, result)) == sorted(map(canonical, case['expected']))
            rows.append(dict(id=case['id'], category=case['category'], actual=result, passed=passed))
        report = dict(variant=variant, changedRoundCalls=edits, casesSha256=digest(frozen), cases=rows,
                      sourceSha256={p.name:digest(p.read_bytes()) for p in sorted(src.glob('*.java'))})
        (folder/'comparison.json').write_text(json.dumps(report, indent=2)+'\n')
        categories = sorted({x['category'] for x in cases})
        all_results[variant] = {category:{'total':sum(x['category']==category for x in rows),
            'passed':sum(x['category']==category and x['passed'] for x in rows)} for category in categories}
    summary = dict(scope='Host-only synthetic candidate intake; not Android, OCR quality or library adoption',
                   versions=versions, caseCount=len(cases), casesSha256=digest(frozen), seed=20260924,
                   commit=provenance['commit'], variants=all_results)
    (out/'summary.json').write_text(json.dumps(summary, indent=2)+'\n')
    print(json.dumps(summary, indent=2))


if __name__ == '__main__':
    main()
