#!/usr/bin/env python3
"""Freeze synthetic DB postprocessing/crop references; no inference or user images.
Calls pinned RapidOCR functions, retaining paired identities when sorting boxes.
Output must be a new, empty directory outside this repository.
"""
from pathlib import Path
import argparse, gzip, hashlib, importlib.metadata, inspect, io, json, os, re

ROOT = Path(__file__).resolve().parents[1]
SOURCE = ROOT / 'modelprobe/src/androidTest/assets/detector-v1'
MANIFEST_SHA = '74aa1e39c8d3187ee2388ad07bb228e3c876c0486ccd02c47208942d26f2c80a'
VERSIONS = {'rapidocr': '3.9.2', 'numpy': '2.5.3', 'opencv-python': '5.0.0.93',
            'pillow': '12.3.0', 'pyclipper': '1.4.0', 'shapely': '2.1.2', 'onnxruntime': '1.30.0'}
CONFIG = dict(thresh=0.3, box_thresh=0.5, max_candidates=1000,
              unclip_ratio=1.6, use_dilation=True, score_mode='fast')

def sha(raw):
    return hashlib.sha256(raw).hexdigest()

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--output', type=Path, required=True)
    out = parser.parse_args().output.resolve()
    assert out != ROOT and ROOT not in out.parents, 'Output must remain outside repository'
    assert {k: importlib.metadata.version(k) for k in VERSIONS} == VERSIONS
    out.mkdir(parents=True, exist_ok=True)
    assert not any(out.iterdir()), 'Use a new empty output directory; never overwrite evidence'
    os.chdir(out)  # Any dependency initialization artifacts remain outside the project.
    import onnxruntime as ort
    ort.disable_telemetry_events()
    import cv2
    import numpy as np
    from PIL import Image
    from rapidocr.ch_ppocr_det.utils import DBPostProcess
    from rapidocr.ch_ppocr_det.main import TextDetector
    from rapidocr.utils.process_img import get_rotate_crop_image
    cv2.setNumThreads(1)
    raw = (SOURCE / 'manifest.json').read_bytes()
    assert sha(raw) == MANIFEST_SHA
    manifest = json.loads(raw)
    files, cases = {}, []
    def write(name, data, **meta):
        assert re.fullmatch(r'[a-z0-9.-]+', name) and len(data) <= 2 * 1024 * 1024
        assert name not in files
        (out / name).write_bytes(data)
        files[name] = dict(bytes=len(data), sha256=sha(data), **meta)
        return name
    def zipped(name, data):
        return write(name, gzip.compress(data, mtime=0), decodedBytes=len(data), decodedSha256=sha(data))
    def png(name, bgr):
        assert bgr.dtype == np.uint8 and bgr.ndim == 3 and bgr.shape[2] == 3
        output = io.BytesIO(); Image.fromarray(bgr[:, :, ::-1]).save(output, format='PNG')
        return write(name, output.getvalue(), width=bgr.shape[1], height=bgr.shape[0], rawBgrSha256=sha(bgr.tobytes()))
    def authenticated(name):
        meta = manifest['files'][name]; data = (SOURCE / name).read_bytes()
        assert len(data) == meta['bytes'] and sha(data) == meta['sha256']
        return data
    def inflate(name):
        meta = manifest['files'][name]
        assert 0 < meta['decodedBytes'] <= 4 * 1024 * 1024
        with gzip.GzipFile(fileobj=io.BytesIO(authenticated(name))) as stream:
            data = stream.read(meta['decodedBytes'] + 1)
        assert len(data) == meta['decodedBytes'] and sha(data) == meta['decodedSha256']
        return data
    def paired_order(boxes):
        # Same geometric order as pinned TextDetector.sorted_boxes, carrying indices too.
        # Derivation/attribution: RapidOCR 3.9.2 ch_ppocr_det/main.py (Apache-2.0).
        if len(boxes) == 0:
            return np.empty(0, dtype=np.int64)
        by_y = np.argsort(boxes[:, 0, 1], kind='stable')
        lines = np.concatenate([[0], np.cumsum((np.diff(boxes[by_y, 0, 1]) >= 10).astype(np.int32))])
        order = by_y[np.lexsort((boxes[by_y, 0, 0], lines))]
        assert np.array_equal(boxes[order], TextDetector.sorted_boxes(boxes))
        return order
    pairing_boxes = np.array([[[20, 1], [30, 1], [30, 10], [20, 10]],
                              [[0, 1], [10, 1], [10, 10], [0, 10]]], dtype=np.float32)
    assert paired_order(pairing_boxes).tolist() == [1, 0]
    pairing = dict(originalIds=['right', 'left'], originalScores=[0.2, 0.9],
                   orderedIds=['left', 'right'], orderedScores=[0.9, 0.2],
                   order=paired_order(pairing_boxes).tolist())
    post = DBPostProcess(**CONFIG)
    def add_case(case_id, source, probability, origin, expected_boxes=None):
        h, w = source.shape[:2]
        assert 0 < h <= 4096 and 0 < w <= 4096 and h*w <= 1_048_576
        assert probability.dtype == np.float32 and probability.ndim == 4 and probability.shape[:2] == (1, 1)
        assert probability.size <= 1_048_576 and np.isfinite(probability).all()
        assert probability.min() >= 0 and probability.max() <= 1
        mask = (probability[0, 0] > np.float32(CONFIG['thresh'])).astype(np.uint8)
        dilated = cv2.dilate(mask, np.ones((2, 2), dtype=np.uint8))
        contours, _ = cv2.findContours(dilated * 255, cv2.RETR_LIST, cv2.CHAIN_APPROX_SIMPLE)
        boxes, scores = post(probability.copy(), (h, w))
        repeat_boxes, repeat_scores = post(probability.copy(), (h, w))
        assert np.array_equal(boxes, repeat_boxes) and scores == repeat_scores
        assert len(boxes) == len(scores) <= CONFIG['max_candidates']
        if expected_boxes is not None:
            assert len(boxes) == expected_boxes, (case_id, len(boxes), expected_boxes)
        if len(boxes):
            assert boxes.shape[1:] == (4, 2) and np.isfinite(boxes).all()
            assert (boxes[:, :, 0] >= 0).all() and (boxes[:, :, 0] < w).all()
            assert (boxes[:, :, 1] >= 0).all() and (boxes[:, :, 1] < h).all()
        assert all(np.isfinite(s) and 0 <= s <= 1 for s in scores)
        rows = []
        for rank, index in enumerate(paired_order(boxes).tolist()):
            box = boxes[index]
            before_w = int(max(np.linalg.norm(box[0]-box[1]), np.linalg.norm(box[2]-box[3])))
            before_h = int(max(np.linalg.norm(box[0]-box[3]), np.linalg.norm(box[1]-box[2])))
            assert 0 < before_w <= 4096 and 0 < before_h <= 4096 and before_w*before_h <= 1_048_576
            crop = get_rotate_crop_image(source, box.copy())
            again = get_rotate_crop_image(source, box.copy())
            assert np.array_equal(crop, again)
            rotate = before_h / before_w >= 1.5
            expected_shape = (before_w, before_h, 3) if rotate else (before_h, before_w, 3)
            assert crop.shape == expected_shape
            target = np.array([[0, 0], [before_w, 0], [before_w, before_h], [0, before_h]], dtype=np.float32)
            matrix = cv2.getPerspectiveTransform(box, target)
            assert np.isfinite(matrix).all() and np.isfinite(np.linalg.inv(matrix)).all()
            rows.append(dict(id=f'{case_id}.box-{index}', originalIndex=index, readingOrder=rank,
                             quad=box.tolist(), detectorScore=scores[index],
                             preRotationWidth=before_w, preRotationHeight=before_h,
                             rotateCounterClockwise90=rotate, sourceToPreRotationCrop=matrix.tolist(),
                             crop=png(f'{case_id}-crop-{rank:02d}.png', crop)))
        cases.append(dict(id=case_id, origin=origin, source=png(case_id+'-source.png', source),
                          probability=zipped(case_id+'-probability.f32z', probability.astype('<f4').tobytes()),
                          probabilityShape=list(probability.shape), mask=zipped(case_id+'-mask.u8z', mask.tobytes()),
                          dilatedMask=zipped(case_id+'-dilated.u8z', dilated.tobytes()),
                          positivePixels=int(mask.sum()), dilatedPositivePixels=int(dilated.sum()),
                          contourCount=len(contours), candidateLimit=1000, truncated=len(contours)>1000,
                          boxesBeforeSort=boxes.tolist(), scoresBeforeSort=scores, boxes=rows,
                          hostRepeatEqual=True))
    for case in manifest['cases']:
        source_raw = authenticated(case['source'])
        source = np.array(Image.open(io.BytesIO(source_raw)).convert('RGB'))[:, :, ::-1].copy()
        assert sha(source.tobytes()) == manifest['files'][case['source']]['rawBgrSha256']
        probability = np.frombuffer(inflate(case['output']), dtype='<f4').reshape(case['outputShape'])
        add_case(case['id'], source, probability, 'frozen_detector_v1_probability_no_new_inference',
                 0 if case['id'] in ['blank-negative-24', 'color-control', 'wide-959', 'wide-1499', 'wide-2001'] else None)
    y, x = np.indices((128, 128))
    patterned = np.stack([(x*17+y*3)%256, (x*7+y*19)%256, (x*11+y*5)%256], axis=2).astype(np.uint8)
    for case_id, expected in [('threshold-equal', 0), ('below-box-score', 0), ('tiny-negative', 0),
                              ('two-blocks', 2), ('edge-touching', 1), ('vertical', 1)]:
        probability = np.zeros((1, 1, 128, 128), dtype=np.float32)
        plane = probability[0, 0]
        if case_id == 'threshold-equal': plane[20:60, 10:100] = np.float32(0.3)
        elif case_id == 'below-box-score': plane[20:60, 10:100] = np.float32(0.4)
        elif case_id == 'tiny-negative': plane[60, 60] = np.float32(0.95)
        elif case_id == 'two-blocks':
            plane[20:35, 12:42] = np.float32(0.85); plane[20:35, 80:110] = np.float32(0.95)
        elif case_id == 'edge-touching': plane[0:18, 0:42] = np.float32(0.9)
        elif case_id == 'vertical': plane[15:112, 55:67] = np.float32(0.9)
        add_case(case_id, patterned, probability, 'hand-authored_geometry_map_not_neural_detection', expected)
    assert cases[-1]['boxes'][0]['rotateCounterClockwise90']
    assert cases[-3]['boxes'][0]['originalIndex'] != cases[-3]['boxes'][0]['readingOrder']
    kernel_cases = []
    for name, points in [('axis-aligned', [[10,10],[40,10],[40,20],[10,20]]),
                         ('fractional-rotated', [[12.25,6.75],[43.75,18.25],[39.25,31.75],[7.75,20.25]])]:
        box = np.array(points, dtype=np.float32)
        expanded = post.unclip(box); again = post.unclip(box)
        assert np.array_equal(expanded, again) and 0 < len(expanded) <= 2048
        quad, minimum_side = post.get_mini_boxes(expanded)
        kernel_cases.append(dict(id=name, input=box.tolist(), expanded=expanded.tolist(),
                                 minimumRectangle=quad.tolist(), minimumSide=float(minimum_side)))
    sources = {}
    for function in [DBPostProcess, TextDetector, get_rotate_crop_image]:
        path = Path(inspect.getsourcefile(function));sources[str(path).split('/site-packages/')[-1]] = sha(path.read_bytes())
    for path, value in manifest['sourceHashes'].items():
        if path in sources: assert sources[path] == value
    result = dict(schema=1, scope='synthetic_DB_geometry_and_perspective_crop_reference_not_OCR_quality_or_Android_implementation',
                  parentManifestSha256=MANIFEST_SHA, versions=VERSIONS, sourceHashes=sources,
                  scriptSha256=sha(Path(__file__).read_bytes()), configuration=CONFIG,
                  morphology=dict(kernel=[2,2], anchor='OpenCV default (-1,-1)', iterations=1,
                                  contours='RETR_LIST + CHAIN_APPROX_SIMPLE'),
                  crop=dict(interpolation='INTER_CUBIC', border='BORDER_REPLICATE',
                            dimensionRule='truncate maximum opposite-edge length',
                            rotation='CCW 90 when pre-rotation height/width >= 1.5'),
                  pairing=pairing, unclipKernels=kernel_cases, cases=cases, files=files,
                  limitations=['Host golden data only; no Android geometry execution',
                               'Contours beyond 1000 require explicit incomplete status; stress case still pending',
                               'Reject invalid/nonfinite/degenerate geometry before allocation in Android port',
                               'Box/crop equality does not prove recognition or translation quality'])
    (out/'manifest.json').write_text(json.dumps(result, ensure_ascii=False, indent=2)+'\n')
    print(json.dumps(dict(cases=len(cases), boxes=sum(len(c['boxes']) for c in cases),
                         files=len(files)+1, manifestSha256=sha((out/'manifest.json').read_bytes()),
                         boxCounts={c['id']:len(c['boxes']) for c in cases}), ensure_ascii=False))

if __name__ == '__main__':
    main()
