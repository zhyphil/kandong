#!/usr/bin/env python3
"""Freeze real synthetic crops/resizes before porting OCR image preprocessing.
Only the existing pinned five-source comparison is recognized; color controls
exercise packing, not OCR quality. Output must stay outside the repository.
"""
import argparse
import gzip
import hashlib
import importlib.metadata
import json
import math
import os
from pathlib import Path

import numpy as np
from PIL import Image

ROOT = Path(__file__).resolve().parents[1]
PROBE_SHA = "126d8d3860d5a4ea098f0875838dbed51a55d8a321f50d409805d394a460061c"
SELECTED = ["en-quality-16", "fr-nonrefundable-16", "zh-hans-quality-16", "zh-hant-quality-16", "mixed-quality-16"]


def sha(data):
    return hashlib.sha256(data).hexdigest()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--models", type=Path, required=True)
    parser.add_argument("--processed-inputs", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    models, processed, out = (p.resolve() for p in (args.models, args.processed_inputs, args.output))
    assert out != ROOT and ROOT not in out.parents
    out.mkdir(parents=True, exist_ok=True)
    os.chdir(out)
    import onnxruntime as ort
    ort.disable_telemetry_events()
    import cv2
    from rapidocr import RapidOCR, ModelType, OCRVersion, LangRec
    provenance = json.loads((ROOT / "docs/evidence/rapidocr/2026-09-24/models.json").read_text())
    for name, entry in provenance["files"].items():
        assert sha((models / name).read_bytes()) == entry["sha256"]
    probe_bytes = (ROOT / "modelprobe/src/main/assets/probes/manifest.json").read_bytes()
    assert sha(probe_bytes) == PROBE_SHA
    probe = json.loads(probe_bytes)
    captured, current, case_outputs = [], [], {}
    files = {}

    def write_png(name, bgr):
        assert bgr.dtype == np.uint8 and bgr.ndim == 3 and bgr.shape[2] == 3
        assert 0 < bgr.shape[0] <= 2048 and 0 < bgr.shape[1] <= 4096
        Image.fromarray(bgr[:, :, ::-1]).save(out / name)
        with Image.open(out / name) as image:
            assert np.array_equal(np.asarray(image.convert("RGB"))[:, :, ::-1], bgr)
        files[name] = {"sha256": sha((out / name).read_bytes()), "width": bgr.shape[1],
                       "height": bgr.shape[0], "rawBgrSha256": sha(bgr.tobytes())}
        return name

    params = {
        "Global.model_root_dir": str(models), "Global.use_cls": False,
        "Global.text_score": 0.0, "Global.log_level": "warning",
        "Det.ocr_version": OCRVersion.PPOCRV5, "Det.model_type": ModelType.MOBILE,
        "Det.model_path": str(models / "ch_PP-OCRv5_det_mobile.onnx"),
        "Det.limit_side_len": 1280, "Det.limit_type": "max",
        "Cls.model_path": str(models / "ch_ppocr_mobile_v2.0_cls_mobile.onnx"),
        "Rec.ocr_version": OCRVersion.PPOCRV5, "Rec.model_type": ModelType.MOBILE,
        "Rec.lang_type": LangRec.CH,
        "Rec.model_path": str(models / "ch_PP-OCRv5_rec_mobile.onnx"),
        "EngineConfig.onnxruntime.intra_op_num_threads": 1,
        "EngineConfig.onnxruntime.inter_op_num_threads": 1,
    }
    engine = RapidOCR(params=params)
    recognizer = engine.text_rec
    real_resize = recognizer.resize_norm_img
    real_recognize = engine.recognize_txt
    real_session = recognizer.session

    def resize(img, ratio):
        norm = real_resize(img, ratio)
        width = norm.shape[2]
        resized_width = min(width, math.ceil(48 * (img.shape[1] / float(img.shape[0]))))
        resized = cv2.resize(img, (resized_width, 48), interpolation=cv2.INTER_LINEAR)
        current.append((img.copy(), resized.copy(), norm.copy()))
        return norm

    def recognize(imgs):
        captured[:] = [img.copy() for img in imgs]
        return real_recognize(imgs)

    class SessionRecorder:
        def __init__(self):
            self.inputs = []
        def __call__(self, tensor):
            self.inputs.append(tensor.copy())
            return real_session(tensor)

    recorder = SessionRecorder()
    recognizer.resize_norm_img = resize
    engine.recognize_txt = recognize
    recognizer.session = recorder
    cases = []

    def record_case(case_id, source, expected_path, expected_tensor):
        assert 1 <= len(current) <= 4 and expected_tensor.dtype == np.float32
        assert expected_tensor.shape[0] == len(current)
        rows = []
        # Match actual resize calls to captured input pixels, not to reference words.
        remaining = set(range(len(captured)))
        for sorted_index, (crop, resized, norm) in enumerate(current):
            match = [i for i in sorted(remaining) if np.array_equal(captured[i], crop)]
            assert match, "Resize input must come from actual detector crop list"
            original_index = match[0]
            remaining.remove(original_index)
            assert np.array_equal(norm, expected_tensor[sorted_index])
            crop_name = write_png(f"{case_id}-crop-{original_index}.png", crop)
            resized_name = write_png(f"{case_id}-resized-{original_index}.png", resized)
            argb = ((np.uint32(255) << 24) | (resized[:, :, 2].astype(np.uint32) << 16)
                    | (resized[:, :, 1].astype(np.uint32) << 8) | resized[:, :, 0].astype(np.uint32))
            argb_name = f"{case_id}-resized-{original_index}.argbz"
            argb_bytes = argb.astype("<u4").tobytes()
            (out / argb_name).write_bytes(gzip.compress(argb_bytes, mtime=0))
            files[argb_name] = {"sha256": sha((out / argb_name).read_bytes()),
                                "argb32LeSha256": sha(argb_bytes)}
            rows.append({"originalIndex": original_index, "tensorRow": sorted_index,
                         "crop": crop_name, "resized": resized_name, "resizedArgb": argb_name})
        assert not remaining
        row = {"id": case_id, "source": source, "shape": list(expected_tensor.shape),
               "tensorSha256": sha(expected_tensor.astype("<f4").tobytes()),
               "expectedTensor": expected_path, "rows": rows}
        cases.append(row)
        case_outputs[case_id] = expected_tensor
        print(case_id, row["shape"], [r["originalIndex"] for r in rows], flush=True)

    for source in SELECTED:
        captured.clear(); current.clear(); recorder.inputs.clear()
        task = next(t for t in probe["tasks"] if t["id"] == "ch/" + source)
        path = processed / (source + "-2x.png")
        with Image.open(path) as image:
            assert sha(image.convert("RGBA").tobytes()) == task["sourcePixelSha256"]
        engine(str(path), use_cls=False, text_score=0.0)
        assert len(recorder.inputs) == 1
        tensor = recorder.inputs[0]
        assert sha(tensor.astype("<f4").tobytes()) == task["tensorSha256"]
        for model in ["ch", "latin"]:
            pair = next(t for t in probe["tasks"] if t["id"] == model + "/" + source)
            assert pair["tensorSha256"] == task["tensorSha256"]
        reference_name = f"reference-{source}.f32z"
        reference = (ROOT / "modelprobe/src/main/assets" / task["asset"]).read_bytes()
        assert sha(reference) == task["compressedSha256"]
        (out / reference_name).write_bytes(reference)
        files[reference_name] = {"sha256": sha(reference), "originalProbeAsset": task["asset"]}
        record_case(source, {"type": "actual_pinned_detector_crops", "inputId": source,
                            "sourcePixelSha256": task["sourcePixelSha256"]}, reference_name, tensor)

    # Asymmetric color controls expose BGR/RGB swaps that grayscale text misses.
    # Deliberately non-sorted dimensions, including equal-ratio rows with distinct pixels.
    captured.clear(); current.clear()
    for i, (height, width) in enumerate([(11, 41), (7, 17), (14, 34)]):
        y, x = np.indices((height, width))
        crop = np.stack(((x * 17 + i * 23) % 256, (y * 31 + x * 3) % 256,
                         (x * 7 + y * 19 + i * 47) % 256), axis=2).astype(np.uint8)
        captured.append(crop)
    order = np.argsort(np.array([x.shape[1] / float(x.shape[0]) for x in captured]))
    max_ratio = max(320 / 48, max(x.shape[1] / float(x.shape[0]) for x in captured))
    for index in order:
        resize(captured[index], max_ratio)
    tensor = np.stack([r[2] for r in current])
    name = "color-controls.f32z"
    (out / name).write_bytes(gzip.compress(tensor.astype("<f4").tobytes(), mtime=0))
    files[name] = {"sha256": sha((out / name).read_bytes())}
    record_case("color-controls", {"type": "project_authored_no_text_calibration",
                                  "notOcrQualityEvidence": True}, name, tensor)
    manifest = {"schema": 1, "scope": "synthetic_crop_to_recognition_tensor_contract_not_Android_OCR",
                "probeManifestSha256": PROBE_SHA, "caseCount": len(cases), "cases": cases,
                "libraries": {p: importlib.metadata.version(p) for p in
                              ["rapidocr", "onnxruntime", "numpy", "Pillow", "opencv-python"]},
                "files": files,
                "contract": {"channels": "BGR", "layout": "NCHW", "height": 48,
                             "width": "floor(48 * max(320/48, max crop aspect ratio))",
                             "resizedWidth": "min(batch width, ceil(48 * crop aspect ratio))",
                             "normalization": "float32(value)/255; minus 0.5; divide by 0.5",
                             "padding": "float32 positive zero on the right after normalization",
                             "rowOrder": "actual ascending crop ratio; tied controls observed as input order; no universal numpy tie-order guarantee",
                             "resize": "upstream OpenCV INTER_LINEAR, port not yet validated"}}
    (out / "manifest.json").write_text(json.dumps(manifest, ensure_ascii=False, indent=2) + "\n")
    lines = ["# Fixed upstream fixtures, not generated by Kotlin under test", "cases=" + ",".join(c["id"] for c in cases)]
    for c in cases:
        prefix = c["id"] + "."
        lines += [prefix + "count=" + str(c["shape"][0]), prefix + "width=" + str(c["shape"][3]),
                  prefix + "expectedTensor=" + c["expectedTensor"], prefix + "tensorSha256=" + c["tensorSha256"],
                  prefix + "order=" + ",".join(str(r["originalIndex"]) for r in c["rows"])]
        for row in c["rows"]:
            rp = prefix + str(row["originalIndex"]) + "."
            f = files[row["crop"]]
            lines += [rp + "sourceWidth=" + str(f["width"]), rp + "sourceHeight=" + str(f["height"]),
                      rp + "resizedArgb=" + row["resizedArgb"],
                      rp + "resizedWidth=" + str(files[row["resized"]]["width"])]
    (out / "manifest.properties").write_text("\n".join(lines) + "\n")
    print("MANIFEST", sha((out / "manifest.json").read_bytes()), flush=True)


if __name__ == "__main__":
    main()
