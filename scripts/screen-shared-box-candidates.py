#!/usr/bin/env python3
"""Keep both raw recognizers on one detected crop set; synthetic host evaluation only."""
import argparse
import copy
import hashlib
import importlib.metadata
import importlib.util
import inspect
import json
import os
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
VERSIONS = {'rapidocr': '3.9.2', 'numpy': '2.5.3', 'opencv-python': '5.0.0.93',
            'Pillow': '12.3.0', 'onnxruntime': '1.30.0'}
SOURCES = {
    'main.py': 'c2ae17098dde838ac3d2933eec5b218d5c4404ded9fe5d32df7c24fd0e54aa39',
    'ch_ppocr_rec/main.py': '84b7a55a8972d14a92800b66facc73976b8d0b06bd8888e551414dbec8d6d326',
    'utils/process_img.py': 'abaf2ed615878f618a372cba6157bc6c41a494e05f6c41bf9f7c5c2ba1ee5772',
    'ch_ppocr_det/main.py': 'a56c0f51fd6a8c03a5abf2f0a5843b382b8f3257d1bfacf8d5ac6305d5e63024'}
DATASETS = {
    'baseline72': ('ocrlab/src/main/assets/trilingual-v1', '2341dff63bd10d41867351ddff0e4145daa2d9d3493bc8cdc88ea289c1a076a8'),
    'holdout124': ('docs/fixtures/trilingual-holdout-v1', '3afed931fa6904864fe71f93f0be1de11a182f5a016fbc933fa529384cf165d9')}

def sha(raw): return hashlib.sha256(raw).hexdigest()
def read(path, digest):
    raw = path.read_bytes(); assert sha(raw) == digest, str(path)
    return json.loads(raw)
def module(name, file):
    spec = importlib.util.spec_from_file_location(name, ROOT / 'scripts' / file)
    obj = importlib.util.module_from_spec(spec); spec.loader.exec_module(obj)
    return obj

def associate(ch, latin, choose):
    # Identity and geometry enter here. Source answers, language and scores do not.
    assert len(ch) == len(latin) <= 8
    assert len({r['boxId'] for r in ch}) == len(ch)
    assert len({r['boxId'] for r in latin}) == len(latin)
    right = {r['boxId']: r for r in latin}
    assert {r['boxId'] for r in ch} == set(right), 'BOX_ID_MISMATCH'
    result = []
    for a in ch:
        b = right[a['boxId']]
        assert a['quad'] == b['quad'], 'BOX_GEOMETRY_MISMATCH'
        result.append(dict(boxId=a['boxId'], quad=a['quad'], ch=a['raw'], latin=b['raw'],
                           selection=choose(a['raw'], b['raw'])))
    return result

def checks(choose):
    a = dict(boxId='p.box-0', quad=[[0, 0], [10, 0], [10, 2], [0, 2]], raw='行李')
    b = dict(a, raw='')
    assert associate([a], [b], choose)[0]['selection']['raw'] == '行李'
    assert associate([], [], choose) == []
    second = dict(a, boxId='p.box-1', raw='Prix')
    paired = associate([a, second], [dict(second, raw='Prix'), b], choose)
    assert [r['boxId'] for r in paired] == ['p.box-0', 'p.box-1']
    for bad in [[dict(b, boxId='q.box-0')], [dict(b, quad=[])], [], [b, b]]:
        try: associate([a], bad, choose)
        except AssertionError: pass
        else: raise AssertionError('invalid identity accepted')

def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('--models', type=Path, required=True); p.add_argument('--output', type=Path, required=True)
    args = p.parse_args(); out = args.output.resolve(); models = args.models.resolve()
    assert out != ROOT and ROOT not in out.parents and not out.exists()
    assert {k: importlib.metadata.version(k) for k in VERSIONS} == VERSIONS
    screen = module('previous_screen', 'screen-rapidocr-fixtures.py')
    select = module('previous_selection', 'screen-block-recognizer-selection.py')
    select.checks(); checks(select.choose)
    model_meta = {}
    for name, digest in screen.MODEL_HASHES.items():
        raw = (models / name).read_bytes(); assert sha(raw) == digest
        model_meta[name] = dict(sha256=digest, bytes=len(raw))
    out.mkdir(parents=True); os.chdir(out)
    import onnxruntime as ort
    ort.disable_telemetry_events()
    import cv2
    import numpy as np
    from PIL import Image
    from omegaconf import OmegaConf
    from rapidocr import LangRec, ModelType, OCRVersion, RapidOCR
    from rapidocr.main import RapidOCRError
    from rapidocr.ch_ppocr_cls import TextClsOutput
    from rapidocr.utils.process_img import map_boxes_to_original
    source_root = Path(inspect.getsourcefile(RapidOCR)).parent
    for name, digest in SOURCES.items(): assert sha((source_root / name).read_bytes()) == digest
    cv2.setNumThreads(1)
    engines = {}
    for lang in [LangRec.CH, LangRec.LATIN]:
        prefix = lang.value
        params = {
            'Global.model_root_dir': str(models), 'Global.use_cls': False,
            'Global.text_score': 0.0, 'Global.log_level': 'warning',
            'Det.ocr_version': OCRVersion.PPOCRV5, 'Det.model_type': ModelType.MOBILE,
            'Det.model_path': str(models / 'ch_PP-OCRv5_det_mobile.onnx'),
            'Det.limit_side_len': 1280, 'Det.limit_type': 'max',
            'Cls.model_path': str(models / 'ch_ppocr_mobile_v2.0_cls_mobile.onnx'),
            'Rec.ocr_version': OCRVersion.PPOCRV5, 'Rec.model_type': ModelType.MOBILE,
            'Rec.lang_type': lang, 'Rec.model_path': str(models / (prefix + '_PP-OCRv5_rec_mobile.onnx')),
            'EngineConfig.onnxruntime.intra_op_num_threads': 1,
            'EngineConfig.onnxruntime.inter_op_num_threads': 1}
        engines[prefix] = RapidOCR(params=params)
        for part in [engines[prefix].text_det, engines[prefix].text_cls, engines[prefix].text_rec]:
            assert part.session.session.get_providers() == ['CPUExecutionProvider']
    rows = []; totals = dict(detectorCalls=0, recognizerCalls=0, historicalOutputsReproduced=0,
                             sharedBoxes=0, emptyChCandidates=0, emptyLatinCandidates=0)
    for dataset, (directory, digest) in DATASETS.items():
        base = ROOT / directory; manifest = read(base / 'manifest.json', digest)
        history = read(ROOT / f'docs/evidence/rapidocr/2026-09-24/{dataset}.json', select.audit.HISTORICAL[dataset])
        for key, engine in engines.items():
            assert OmegaConf.to_container(engine.cfg, resolve=True, enum_to_str=True) == history['configuration'][key]
        previous = {(r['inputId'], r['scale'], r['recognizer']): r for r in history['results']}
        assert manifest['scope'] == 'project_authored_synthetic_only'
        for item in manifest['images']:
            name = item['asset']; assert Path(name).name == name
            data = (base / name).read_bytes(); assert sha(data) == item['pngSha256']
            image = Image.open(base / name).convert('RGBA')
            assert image.size == (item['width'], item['height']) and sha(image.tobytes()) == item['pixelSha256']
            for scale in [1, 2]:
                actual_image = image if scale == 1 else screen.smooth2(image)
                pixel_sha = sha(actual_image.tobytes()); image_id = Path(name).stem
                path = out / f'{dataset}-{image_id}-{scale}.png'; actual_image.save(path)
                ch_engine = engines['ch']; original = ch_engine.load_img(path)
                processed, operations = ch_engine.preprocess_img(original)
                totals['detectorCalls'] += 1
                try:
                    crops, detector = ch_engine.detect_and_crop(processed, operations)
                except RapidOCRError as e:
                    assert str(e) == 'The text detection result is empty'
                    crops, detector = [], None
                assert len(crops) <= 8
                quads = [] if not crops else (map_boxes_to_original(detector.boxes.copy(), operations,
                                                  original.shape[0], original.shape[1]) / scale).tolist()
                assert len(quads) == len(crops)
                crop_hashes = [sha(c.tobytes()) for c in crops]
                raw_blocks = {}; removed = {}
                for model, engine in engines.items():
                    old = previous[(image_id, scale, model)]
                    assert old['inputPixelSha256'] == pixel_sha
                    if crops:
                        rec = engine.recognize_txt(crops); totals['recognizerCalls'] += 1
                        texts = list(rec.txts); scores = [float(x) for x in rec.scores]
                        assert len(texts) == len(scores) == len(crops)
                        assert all(np.isfinite(scores))
                        assert crop_hashes == [sha(c.tobytes()) for c in crops]
                        raw_blocks[model] = [dict(boxId=f'{pixel_sha}.box-{i}', quad=quad, raw=text)
                                             for i, (quad, text) in enumerate(zip(quads, texts, strict=True))]
                        removed[model] = [i for i, text in enumerate(texts) if not text.strip()]
                        # Reapply the ORIGINAL finalizer to copies and demand exact historical output.
                        det_copy = copy.copy(detector); det_copy.boxes = detector.boxes.copy()
                        final = engine.build_final_output(original, det_copy, TextClsOutput(), copy.copy(rec), crops, operations)
                        final_texts = list(final.txts or [])
                        final_quads = [] if final.boxes is None else (final.boxes / scale).tolist()
                        final_scores = [] if final.scores is None else [float(x) for x in final.scores]
                    else:
                        raw_blocks[model] = []; removed[model] = []
                        final_texts, final_quads, final_scores = [], [], []
                    assert (final_texts, final_quads, final_scores) == (old['lines'], old['boxesUnscaled'], old['scores']), (dataset, image_id, scale, model)
                    totals['historicalOutputsReproduced'] += 1
                blocks = associate(raw_blocks['ch'], raw_blocks['latin'], select.choose)
                selected_raw = '\n'.join(r['selection']['raw'] for r in blocks) if all(r['selection']['raw'] is not None for r in blocks) else None
                totals['sharedBoxes'] += len(blocks)
                totals['emptyChCandidates'] += len(removed['ch']); totals['emptyLatinCandidates'] += len(removed['latin'])
                # Answers and source language are used only below, AFTER inference and selection.
                source = select.audit.normalize(item['source'])
                rows.append(dict(dataset=dataset, id=image_id, scale=scale, pixelSha256=pixel_sha,
                    source=item['source'], languageForScoringOnly=item['language'], textImage=bool(source),
                    blocks=blocks, cropBgrSha256=crop_hashes, removedByHistoricalFinalizer=removed,
                    selectedRaw=selected_raw, selectedExact=selected_raw is not None and select.audit.normalize(selected_raw) == source,
                    chExact=select.audit.normalize(previous[(image_id,scale,'ch')]['recognizedRaw']) == source,
                    latinExact=select.audit.normalize(previous[(image_id,scale,'latin')]['recognizedRaw']) == source))
                print(dataset, image_id, scale, 'boxes', len(blocks), 'removed', removed, 'selectedExact', rows[-1]['selectedExact'], flush=True)
    assert len(rows) == 98 and totals['detectorCalls'] == 98 and totals['historicalOutputsReproduced'] == 196
    groups = []
    for dataset in DATASETS:
        for scale in [1,2]:
            group = [r for r in rows if r['dataset']==dataset and r['scale']==scale]; text = [r for r in group if r['textImage']]
            groups.append(dict(dataset=dataset,scale=scale,textImages=len(text),
                chExact=sum(r['chExact'] for r in text),latinExact=sum(r['latinExact'] for r in text),selectedExact=sum(r['selectedExact'] for r in text),
                regressions=[r['id'] for r in text if r['chExact'] and not r['selectedExact']],
                improved=[r['id'] for r in text if r['selectedExact'] and not r['chExact']],
                review=[r['id'] for r in text if r['selectedRaw'] is None],
                remainingFailures=[r['id'] for r in text if not r['selectedExact']],
                blankControls=sum(not r['textImage'] for r in group),blankControlsPassed=all(r['selectedRaw']=='' for r in group if not r['textImage'])))
    result = dict(schema=1,scope='Mac local CPU synthetic existing corpus with shared detector crops; not Android or unseen quality acceptance',
        ruleFrozenAt='e123c55',scriptSha256=sha(Path(__file__).read_bytes()),upstreamSources=SOURCES,versions=VERSIONS,models=model_meta,
        parentScriptSha256={n:sha((ROOT/'scripts'/n).read_bytes()) for n in ['screen-rapidocr-fixtures.py','screen-block-recognizer-selection.py','audit-end-to-end-ocr-quality.py']},
        fixtureManifests={k:v[1] for k,v in DATASETS.items()},historySha256=select.audit.HISTORICAL,
        checks='existing 10 selector checks plus 7 shared-identity checks',totals=totals,groups=groups,results=rows,
        limitations=['Same authored corpus/font informed the rule; fresh corpus still required',
                     'Unchanged raw text; no confidence calibration or semantic fidelity guarantee',
                     'No real screen data, translation or production integration'])
    (out/'shared-box-report.json').write_text(json.dumps(result,ensure_ascii=False,indent=2,allow_nan=False)+'\n')
    print(json.dumps(dict(totals=totals,groups=groups),ensure_ascii=False,indent=2),flush=True)

if __name__ == '__main__': main()
