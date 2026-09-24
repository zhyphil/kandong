#!/usr/bin/env python3
"""Explicit local-only preparation for the test APK. No downloads and no main-APK assets."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import tempfile

ROOT = Path(__file__).resolve().parents[1]
STAGE = ROOT / "modelprobe/build/detector-assets/detector-model"
MODEL_NAME = "ch_PP-OCRv5_det_mobile.onnx"
MODEL_BYTES = 4_819_576
MODEL_SHA = "4d97c44a20d30a81aad087d6a396b08f786c4635742afc391f6621f5c6ae78ae"
MANIFEST_SHA = "74aa1e39c8d3187ee2388ad07bb228e3c876c0486ccd02c47208942d26f2c80a"
MODEL_URL = "https://www.modelscope.cn/models/RapidAI/RapidOCR/resolve/v3.9.2/onnx/PP-OCRv5/det/" + MODEL_NAME
LEGAL = {
    "ModelScope-README.md.txt": (1140, "3dc91bb3cb667df783178917d69b38bfabb8c935596f34243a1c9f0d36916b6e"),
    "ORT-LICENSE.txt": (1073, "2f07c72751aed99790b8a4869cf2311df85a860b22ded05fa22803587a48922c"),
    "ORT-ThirdPartyNotices.txt": (338088, "143764b952fdb1a7c69ce653bfba74a7744d6a8a573bfb73e235fba356c83de3"),
    "PaddleOCR-LICENSE.txt": (11376, "3840c5c0c61c294264d2dd77b8777be6ddd90121ef4e0e64abcd22edea581d6e"),
    "RapidOCR-LICENSE.txt": (11422, "3e0af25fdd06aa9586ae97adb00ea927ebe5a3805ac77d2d3a81ce5f55693333"),
}
NOTICE = """KanDong detector numeric probe: local synthetic experiment, androidTest assets only.

Detector weights originate in PaddleOCR / Baidu and respective rights holders;
converted ONNX weights are distributed by RapidAI/RapidOCR. The pinned ModelScope
v3.9.2 model card declares Apache License 2.0. Its declaration and the existing
PaddleOCR/RapidOCR license copies accompany this unmodified local model copy.
ONNX Runtime 1.30.0 is covered by the included MIT license and third-party notices.

Model identity is pinned in provenance.json. Conversion recipes and the complete
weight licensing chain have not been independently audited. These copies document
a local experiment; they are not redistribution certification or release approval.
No recognition dictionaries, user screens or downloaded data are added by this script.
"""
PROVENANCE = {
    "scope": "local_synthetic_detector_numeric_experiment_androidTest_only",
    "preparation": "explicit_local_file_copy_no_download",
    "fixtureManifestSha256": MANIFEST_SHA,
    "model": {"asset": "detector-model/" + MODEL_NAME, "bytes": MODEL_BYTES, "sha256": MODEL_SHA, "sourceUrl": MODEL_URL},
    "modelLicenseDeclaration": {"license": "Apache License 2.0", "cardSha256": LEGAL["ModelScope-README.md.txt"][1]},
    "conversionRecipeAudited": False,
    "redistributionCertified": False,
    "legalCopies": {name: {"bytes": size, "sha256": sha} for name, (size, sha) in LEGAL.items()},
}


def authenticated(path, size, sha, cap):
    if not path.is_file() or path.stat().st_size != size or not 0 < size <= cap:
        raise ValueError("Missing file or invalid byte length: " + str(path))
    with path.open("rb") as stream:
        raw = stream.read(cap + 1)
    if len(raw) != size or hashlib.sha256(raw).hexdigest() != sha:
        raise ValueError("Pinned identity mismatch: " + str(path))
    return raw


def generated_legal():
    return {"NOTICE.txt": NOTICE.encode("utf-8"),
            "provenance.json": (json.dumps(PROVENANCE, sort_keys=True, indent=2) + "\n").encode("utf-8")}


def atomic_write(target, raw):
    target.parent.mkdir(parents=True, exist_ok=True)
    if target.is_symlink():
        raise ValueError("Refusing staged symlink: " + str(target))
    temporary = None
    try:
        with tempfile.NamedTemporaryFile(dir=target.parent, prefix=".detector-", delete=False) as stream:
            temporary = Path(stream.name)
            stream.write(raw)
            stream.flush()
            os.fsync(stream.fileno())
        temporary.replace(target)
    finally:
        if temporary is not None:
            temporary.unlink(missing_ok=True)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--model", type=Path, required=True, help="Existing local pinned detector ONNX file; never a URL")
    args = parser.parse_args()
    authenticated(ROOT / "modelprobe/src/androidTest/assets/detector-v1/manifest.json", 28958, MANIFEST_SHA, 128 * 1024)
    # Authenticate all inputs before creating or replacing any staged asset.
    payloads = {MODEL_NAME: authenticated(args.model.expanduser(), MODEL_BYTES, MODEL_SHA, 6 * 1024 * 1024)}
    for name, (size, sha) in LEGAL.items():
        payloads["legal/" + name] = authenticated(ROOT / "modelprobe/src/main/assets/legal" / name, size, sha, 512 * 1024)
    payloads.update({"legal/" + name: raw for name, raw in generated_legal().items()})
    for directory in (ROOT / "modelprobe/build", STAGE.parent, STAGE, STAGE / "legal"):
        if directory.is_symlink():
            raise ValueError("Refusing staged directory symlink: " + str(directory))
    for name, raw in payloads.items():
        atomic_write(STAGE / name, raw)
        if (STAGE / name).read_bytes() != raw:
            raise OSError("Staged readback mismatch: " + name)
    print("Prepared pinned local detector and legal copies for androidTest only:", STAGE)
    print("Local experiment provenance only; redistribution is not certified.")


if __name__ == "__main__":
    main()
