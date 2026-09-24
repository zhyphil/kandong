#!/usr/bin/env python3
"""Freeze actual DB intermediate geometry and bounded stress inputs; no inference.

Calls pinned RapidOCR methods through tracing overrides; no alternate box algorithm.
Old fixtures are authenticated and never replaced. Output stays outside the repo.
"""
from pathlib import Path
import argparse, gzip, hashlib, importlib.metadata, inspect, io, json, os, re

ROOT = Path(__file__).resolve().parents[1]
PARENT = ROOT / 'docs/fixtures/detector-geometry-v1'
PARENT_SHA = '1aa14f2efb50e62c5a2d95b45b7d5b5044d4ec90afa47a976e8dea50bbe466a6'
VERSIONS = {'rapidocr': '3.9.2', 'numpy': '2.5.3', 'opencv-python': '5.0.0.93',
            'pyclipper': '1.4.0', 'shapely': '2.1.2', 'onnxruntime': '1.30.0'}
CONFIG = dict(thresh=.3, box_thresh=.5, max_candidates=1000,
              unclip_ratio=1.6, use_dilation=True, score_mode='fast')
def sha(b): return hashlib.sha256(b).hexdigest()
def encoded(v): return (json.dumps(v, ensure_ascii=False, indent=2, allow_nan=False)+'\n').encode()

def main():
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument('--output', type=Path, required=True)
    out = ap.parse_args().output.resolve()
    assert out != ROOT and ROOT not in out.parents
    assert {k: importlib.metadata.version(k) for k in VERSIONS} == VERSIONS
    out.mkdir(parents=True, exist_ok=True)
    assert not any(out.iterdir()), 'Use a new empty output directory'
    os.chdir(out)
    import onnxruntime as ort
    ort.disable_telemetry_events()
    import cv2
    import numpy as np
    import pyclipper
    from shapely.geometry import Polygon
    from rapidocr.ch_ppocr_det.utils import DBPostProcess
    from rapidocr.ch_ppocr_det.main import TextDetector
    cv2.setNumThreads(1)
    raw = (PARENT/'manifest.json').read_bytes()
    assert sha(raw) == PARENT_SHA
    parent = json.loads(raw)
    assert parent['configuration'] == CONFIG
    for cls in [DBPostProcess, TextDetector]:
        path = Path(inspect.getsourcefile(cls))
        key = str(path).split('/site-packages/')[-1]
        assert sha(path.read_bytes()) == parent['sourceHashes'][key]
    def parent_bytes(name):
        m = parent['files'][name]
        assert re.fullmatch(r'[a-z0-9.-]+', name) and 0 < m['bytes'] <= 2*1024*1024
        raw = (PARENT/name).read_bytes()
        assert len(raw) == m['bytes'] and sha(raw) == m['sha256']
        if name.endswith('f32z'):
            assert 0 < m['decodedBytes'] <= 4*1024*1024
            with gzip.GzipFile(fileobj=io.BytesIO(raw)) as stream: decoded = stream.read(m['decodedBytes']+1)
            assert len(decoded) == m['decodedBytes'] and sha(decoded) == m['decodedSha256']
            return decoded
        return raw
    files, cases = {}, []
    def save(name, raw, compress=True):
        assert re.fullmatch(r'[a-z0-9.-]+', name) and name not in files
        assert len(raw) <= 8*1024*1024
        b = gzip.compress(raw, mtime=0) if compress else raw
        assert len(b) <= 2*1024*1024
        (out/name).write_bytes(b)
        files[name] = dict(bytes=len(b), sha256=sha(b), decodedBytes=len(raw), decodedSha256=sha(raw))
        return name
    class Trace(DBPostProcess):
        def __init__(self):
            super().__init__(**CONFIG)
            self.rows = []
            self.after_unclip = False
        def get_mini_boxes(self, contour):
            q, side = super().get_mini_boxes(contour)
            if self.after_unclip:
                self.rows[-1].update(expandedQuad=q.tolist(), expandedMinimumSide=float(side))
                self.after_unclip = False
            else:
                self.rows.append(dict(contourIndex=len(self.rows), contour=np.asarray(contour).reshape(-1,2).tolist(),
                    preUnclipQuad=q.tolist(), minimumSide=float(side)))
            return q, side
        def box_score_fast(self, bitmap, box):
            score = super().box_score_fast(bitmap, box)
            self.rows[-1]['score'] = float(score)
            return score
        def unclip(self, box):
            # Also save actual pre-unclip paths, rather than reusing final clipped quads.
            expanded = super().unclip(box)
            polygon = Polygon(box)
            distance = polygon.area * self.unclip_ratio / polygon.length
            offset = pyclipper.PyclipperOffset()
            offset.AddPath(box, pyclipper.JT_ROUND, pyclipper.ET_CLOSEDPOLYGON)
            paths = offset.Execute(distance)
            assert np.array_equal(np.asarray(paths).reshape(-1,1,2), expanded)
            self.rows[-1].update(distance=float(distance), integerInput=np.asarray(box).astype(np.int64).tolist(),
                expandedPaths=paths)
            self.after_unclip = True
            return expanded
        def boxes_from_bitmap(self, pred, bitmap, dest_width, dest_height):
            raw_boxes, raw_scores = super().boxes_from_bitmap(pred, bitmap, dest_width, dest_height)
            raw_index = 0
            for row in self.rows:
                if row['minimumSide'] < 3: row['disposition'] = 'minimum-side'
                elif row['score'] < .5: row['disposition'] = 'box-score'
                elif row['expandedMinimumSide'] < 5: row['disposition'] = 'expanded-minimum-side'
                else:
                    row.update(rawBoxIndex=raw_index, rawBox=raw_boxes[raw_index].tolist())
                    assert row['score'] == raw_scores[raw_index]
                    box, score = super().filter_det_res(raw_boxes[raw_index:raw_index+1].copy(),
                        raw_scores[raw_index:raw_index+1], dest_height, dest_width)
                    row.update(disposition='accepted' if len(box) else 'final-size',
                        finalBox=box.tolist(), finalScore=score)
                    raw_index += 1
            assert raw_index == len(raw_boxes) == len(raw_scores)
            self.raw_boxes, self.raw_scores = raw_boxes.tolist(), raw_scores
            return raw_boxes, raw_scores
    def add(case_id, probability, source_hw, old=None, expectation=None):
        assert probability.dtype == np.float32 and probability.ndim == 4 and probability.shape[:2] == (1,1)
        assert 0 < probability.size <= 1_048_576 and np.isfinite(probability).all()
        assert probability.min() >= 0 and probability.max() <= 1
        h, w = probability.shape[2:]
        assert h <= 4096 and w <= 4096 and all(0 < d <= 4096 for d in source_hw)
        mask = (probability[0,0] > np.float32(.3)).astype(np.uint8)
        dilated = cv2.dilate(mask, np.ones((2,2), dtype=np.uint8))
        contours, _ = cv2.findContours(dilated*255, cv2.RETR_LIST, cv2.CHAIN_APPROX_SIMPLE)
        run_bound = int(h + np.count_nonzero(dilated[:,1:] != dilated[:,:-1]))
        trace = Trace()
        boxes, scores = trace(probability.copy(), source_hw)
        plain_boxes, plain_scores = DBPostProcess(**CONFIG)(probability.copy(), source_hw)
        assert np.array_equal(boxes, plain_boxes) and scores == plain_scores
        assert len(trace.rows) == min(len(contours), 1000)
        accepted = [row for row in trace.rows if row['disposition'] == 'accepted']
        assert len(accepted) == len(boxes)
        for index, row in enumerate(accepted):
            row['finalBoxIndex'] = index
            assert row['finalBox'] == [boxes[index].tolist()] and row['finalScore'] == [scores[index]]
        order = []
        if len(boxes):
            y_order = np.argsort(boxes[:,0,1], kind='stable')
            lines = np.concatenate([[0], np.cumsum((np.diff(boxes[y_order,0,1])>=10).astype(np.int32))])
            order = y_order[np.lexsort((boxes[y_order,0,0], lines))].tolist()
            assert np.array_equal(boxes[order], TextDetector.sorted_boxes(boxes))
        status = ('INCOMPLETE_CONTOUR_BUDGET' if run_bound > 8192 else
                  'INCOMPLETE_CANDIDATE_LIMIT' if len(contours) > 1000 else 'COMPLETE')
        if old:
            assert boxes.tolist() == old['boxesBeforeSort'] and scores == old['scoresBeforeSort']
            assert order == [r['originalIndex'] for r in old['boxes']]
            assert len(contours) == old['contourCount']
        if expectation:
            assert len(contours) == expectation['contours'], (case_id, len(contours))
            assert len(boxes) == expectation['boxes'], (case_id, len(boxes))
            assert status == expectation['status'], (case_id, status)
        result = dict(id=case_id, rows=trace.rows, rawBoxes=trace.raw_boxes, rawScores=trace.raw_scores,
            boxes=boxes.tolist(), scores=scores, pairedReadingOrder=order,
            contourCount=len(contours), processedCandidates=len(trace.rows), rowRunBound=run_bound,
            upstreamTruncated=len(contours)>1000, expectedSafeStatus=status,
            safePolicy='Do not report partial upstream output as complete; no crop/recognition on incomplete status')
        prob = dict(parentFile=old['probability']) if old else dict(file=save(case_id+'-probability.f32z', probability.astype('<f4').tobytes()))
        cases.append(dict(id=case_id, origin='frozen-parent' if old else 'hand-authored-no-neural-inference',
            probability=prob, shape=list(probability.shape), sourceHeight=source_hw[0], sourceWidth=source_hw[1],
            trace=save(case_id+'-trace.json.gz', encoded(result)), contourCount=len(contours),
            processedCandidates=len(trace.rows), rowRunBound=run_bound, expectedSafeStatus=status,
            acceptedUpstreamBoxes=len(boxes), actualPreUnclipCount=sum('distance' in row for row in trace.rows)))
    for c in parent['cases']:
        m = parent['files'][c['source']]
        p = np.frombuffer(parent_bytes(c['probability']), dtype='<f4').reshape(c['probabilityShape'])
        add(c['id'], p, (m['height'], m['width']), old=c)
    for count in [1000, 1001, 4096]:
        side = 192 if count==4096 else 128
        cols = 64 if count==4096 else 32
        stride = 3 if count==4096 else 4
        p = np.zeros((1,1,side,side), dtype=np.float32)
        for i in range(count): p[0,0,(i//cols)*stride,(i%cols)*stride] = .95
        add(f'candidate-count-{count}', p, (side,side), expectation=dict(contours=count, boxes=0,
            status='COMPLETE' if count==1000 else 'INCOMPLETE_CANDIDATE_LIMIT' if count==1001 else 'INCOMPLETE_CONTOUR_BUDGET'))
    for case_id, shape, level, boxes in [('score-equal', (32,32), .5, 1),
            ('score-below', (32,32), float(np.nextafter(np.float32(.5),np.float32(0))), 0),
            ('final-size-three', (4,4), .9, 0), ('minimum-side-two', (3,3), .9, 0),
            ('collinear-height-one', (1,16), .9, 0)]:
        p = np.full((1,1,*shape), level, dtype=np.float32)
        add(case_id, p, shape, expectation=dict(contours=1, boxes=boxes, status='COMPLETE'))
    assert len(cases) == 24
    manifest = dict(schema=1, scope='Synthetic DB intermediate trace and bounds; host reference only, not Android or OCR acceptance',
        parentManifestSha256=PARENT_SHA, versions=VERSIONS, sourceHashes=parent['sourceHashes'],
        scriptSha256=sha(Path(__file__).read_bytes()), configuration=CONFIG,
        bounds=dict(maxPixels=1048576, maxDimension=4096, rowRunBound=8192, candidateLimit=1000),
        limitations=['Old parent files are referenced and must be separately authenticated',
            'Reference upstream computes first 1000 even for budget stress; Android must stop earlier for row-run budget',
            'Incomplete cases must not expose truncated boxes as complete or enter cropping/recognition',
            'No Android execution, inference, OCR accuracy, screen capture or translation'],
        cases=cases, files=files)
    (out/'manifest.json').write_bytes(encoded(manifest))
    print(json.dumps(dict(cases=len(cases), files=len(files)+1, bytes=sum(m['bytes'] for m in files.values())+(out/'manifest.json').stat().st_size,
        manifestSha256=sha((out/'manifest.json').read_bytes()), actualPreUnclipCases=sum(c['actualPreUnclipCount'] for c in cases)), indent=2))

if __name__ == '__main__': main()
