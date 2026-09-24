#!/usr/bin/env python3
"""Screen pinned local OCR models against project-authored fixture pixels, never user screens."""
import argparse
import hashlib
import importlib.metadata
import json
import os
import time
import unicodedata
from pathlib import Path

import numpy as np
from PIL import Image
from omegaconf import OmegaConf

MODEL_HASHES = {
    'ch_PP-OCRv5_det_mobile.onnx': '4d97c44a20d30a81aad087d6a396b08f786c4635742afc391f6621f5c6ae78ae',
    'ch_PP-OCRv5_rec_mobile.onnx': '5825fc7ebf84ae7a412be049820b4d86d77620f204a041697b0494669b1742c5',
    'latin_PP-OCRv5_rec_mobile.onnx': 'b20bd37c168a570f583afbc8cd7925603890efbcdc000a59e22c269d160b5f5a',
    'ch_ppocr_mobile_v2.0_cls_mobile.onnx': 'e47acedf663230f8863ff1ab0e64dd2d82b838fceb5957146dab185a89d6215c',
}

def sha(data):
    return hashlib.sha256(data).hexdigest()

def normalize(text):
    whitespace = set(range(9, 14)) | {32, 133, 160, 5760, 8232, 8233, 8239, 8287, 12288} | set(range(8192, 8203))
    result, space = [], False
    for char in unicodedata.normalize('NFC', text):
        if ord(char) in whitespace:
            space = bool(result)
        else:
            if space:
                result.append(' ')
            result.append(char)
            space = False
    return ''.join(result)

def smooth2(image):
    """Same integer center-aligned 2x interpolation as Android SmoothPixels."""
    pixels = np.asarray(image, dtype=np.int32)
    height, width = pixels.shape[:2]
    nx = np.clip(np.arange(width * 2) * 2 - 1, 0, (width - 1) * 4)
    ny = np.clip(np.arange(height * 2) * 2 - 1, 0, (height - 1) * 4)
    x0, y0 = nx // 4, ny // 4
    x1, y1 = np.minimum(x0 + 1, width - 1), np.minimum(y0 + 1, height - 1)
    wx, wy = (nx % 4)[None, :, None], (ny % 4)[:, None, None]
    top = pixels[y0[:, None], x0[None, :]] * (4 - wx) + pixels[y0[:, None], x1[None, :]] * wx
    bottom = pixels[y1[:, None], x0[None, :]] * (4 - wx) + pixels[y1[:, None], x1[None, :]] * wx
    return Image.fromarray(((top * (4 - wy) + bottom * wy + 8) // 16).astype(np.uint8))

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--assets', type=Path, required=True)
    parser.add_argument('--manifest-sha256', required=True)
    parser.add_argument('--models', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    repo = Path(__file__).resolve().parents[1]
    args.assets, args.models, args.output = args.assets.resolve(), args.models.resolve(), args.output.resolve()
    assert args.output != repo and repo not in args.output.parents, 'Use an output directory outside the project'
    args.output.mkdir(parents=True, exist_ok=True)
    os.chdir(args.output)
    # The SDK may create initialization state before its telemetry API is callable.
    # Confine that state to this explicit host output directory, never the repository.
    import onnxruntime as ort
    ort.disable_telemetry_events()
    from rapidocr import LangRec, ModelType, OCRVersion, RapidOCR
    manifest_bytes = (args.assets / 'manifest.json').read_bytes()
    assert sha(manifest_bytes) == args.manifest_sha256
    manifest = json.loads(manifest_bytes)
    assert manifest['scope'] == 'project_authored_synthetic_only'
    assert 1 <= len(manifest['images']) <= 100
    assert args.output.resolve() != args.assets.resolve()
    args.output.mkdir(parents=True, exist_ok=True)
    models = {}
    for name, expected in MODEL_HASHES.items():
        data = (args.models / name).read_bytes()
        assert sha(data) == expected, name
        models[name] = {'sha256': expected, 'bytes': len(data)}
    inputs = []
    for row in manifest['images']:
        assert Path(row['asset']).name == row['asset'] and row['asset'].endswith('.png')
        source_path = args.assets / row['asset']
        assert sha(source_path.read_bytes()) == row['pngSha256']
        image = Image.open(source_path).convert('RGBA')
        assert image.size == (row['width'], row['height'])
        assert image.width <= 1000 and image.height <= 1000 and image.getextrema()[3] == (255, 255)
        assert sha(image.tobytes()) == row['pixelSha256']
        for scale in [1, 2]:
            processed = image if scale == 1 else smooth2(image)
            path = args.output / (source_path.stem + '-' + str(scale) + 'x.png')
            processed.save(path)
            inputs.append((row, scale, path, sha(processed.tobytes())))
    results, configurations = [], {}
    for language in [LangRec.CH, LangRec.LATIN]:
        prefix = 'ch' if language == LangRec.CH else 'latin'
        params = {
            'Global.model_root_dir': str(args.models), 'Global.use_cls': False,
            'Global.text_score': 0.0, 'Global.log_level': 'warning',
            'Det.ocr_version': OCRVersion.PPOCRV5, 'Det.model_type': ModelType.MOBILE,
            'Det.model_path': str(args.models / 'ch_PP-OCRv5_det_mobile.onnx'),
            'Det.limit_side_len': 1280, 'Det.limit_type': 'max',
            'Cls.model_path': str(args.models / 'ch_ppocr_mobile_v2.0_cls_mobile.onnx'),
            'Rec.ocr_version': OCRVersion.PPOCRV5, 'Rec.model_type': ModelType.MOBILE,
            'Rec.lang_type': language,
            'Rec.model_path': str(args.models / (prefix + '_PP-OCRv5_rec_mobile.onnx')),
            'EngineConfig.onnxruntime.intra_op_num_threads': 1,
            'EngineConfig.onnxruntime.inter_op_num_threads': 1,
        }
        engine = RapidOCR(params=params)
        for component in [engine.text_det, engine.text_cls, engine.text_rec]:
            assert component.session.session.get_providers() == ['CPUExecutionProvider']
        configurations[language.value] = OmegaConf.to_container(engine.cfg, resolve=True, enum_to_str=True)
        for row, scale, path, pixel_hash in inputs:
            start = time.monotonic()
            output = engine(str(path), use_cls=False, text_score=0.0)
            lines = list(output.txts or [])
            raw = '\n'.join(lines)
            boxes = [] if output.boxes is None else output.boxes.tolist()
            scores = [] if output.scores is None else [float(x) for x in output.scores]
            assert len(boxes) == len(lines) == len(scores)
            result = {
                'inputId': Path(row['asset']).stem, 'fixtureLanguage': row['language'],
                'source': row['source'], 'recognizer': language.value, 'scale': scale,
                'inputPixelSha256': pixel_hash, 'recognizedRaw': raw, 'lines': lines,
                'boxesRaw': boxes, 'boxesUnscaled': (np.asarray(boxes) / scale).tolist(),
                'scores': scores, 'elapsedMs': round((time.monotonic() - start) * 1000, 2),
                'exactNormalizedMatch': normalize(raw) == normalize(row['source']),
            }
            results.append(result)
            print(len(results), result['inputId'], language.value, scale, repr(raw), flush=True)
        del engine
    report = {
        'scope': 'Mac_CPU_only_fixed_synthetic_quality_screening_not_Android',
        'scriptSha256': sha(Path(__file__).read_bytes()), 'manifestSha256': sha(manifest_bytes),
        'fixtureVersion': manifest['fixtureVersion'], 'models': models,
        'libraries': {p: importlib.metadata.version(p) for p in ['rapidocr', 'onnxruntime', 'numpy', 'Pillow', 'opencv-python']},
        'configuration': configurations, 'textScoreThreshold': 0.0,
        'inferencePolicy': 'every input uses both recognizers and scales; source and language only score results; no best-per-input selection or correction',
        'normalization': 'NFC + Unicode White_Space folding and trim only',
        'results': results,
    }
    (args.output / 'report.json').write_text(json.dumps(report, ensure_ascii=False, indent=2) + '\n')
    assert len(results) == len(manifest['images']) * 4
    for recognizer in ['ch', 'latin']:
        for scale in [1, 2]:
            rows = [r for r in results if r['recognizer'] == recognizer and r['scale'] == scale]
            print('GROUP', recognizer, scale, sum(r['exactNormalizedMatch'] for r in rows), '/', len(rows), flush=True)

if __name__ == '__main__':
    main()
