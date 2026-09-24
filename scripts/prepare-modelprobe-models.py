#!/usr/bin/env python3
"""Explicitly fetch only the two pinned synthetic probe models; never runs during Gradle builds."""
import hashlib
import json
import urllib.request
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
MANIFEST = ROOT / 'modelprobe/src/main/assets/probes/manifest.json'
EXPECTED_MANIFEST = '126d8d3860d5a4ea098f0875838dbed51a55d8a321f50d409805d394a460061c'
URL_BASE = 'https://www.modelscope.cn/models/RapidAI/RapidOCR/resolve/v3.9.2/onnx/PP-OCRv5/rec/'

def main():
    raw = MANIFEST.read_bytes()
    if hashlib.sha256(raw).hexdigest() != EXPECTED_MANIFEST:
        raise RuntimeError('Unexpected model probe manifest; review it before downloading')
    manifest = json.loads(raw)
    for model in manifest['models']:
        name = Path(model['asset']).name
        if model['asset'] != 'models/' + name or name not in ['ch_PP-OCRv5_rec_mobile.onnx', 'latin_PP-OCRv5_rec_mobile.onnx']:
            raise ValueError('Unexpected model path')
        target = ROOT / 'modelprobe/src/main/assets' / model['asset']
        target.parent.mkdir(parents=True, exist_ok=True)
        if target.exists() and hashlib.sha256(target.read_bytes()).hexdigest() == model['sha256']:
            print(name, 'already verified')
            continue
        request = urllib.request.Request(URL_BASE + name, headers={'User-Agent': 'KanDong-synthetic-model-probe'})
        with urllib.request.urlopen(request, timeout=120) as response:
            data = response.read(model['bytes'] + 1)
        if len(data) != model['bytes'] or hashlib.sha256(data).hexdigest() != model['sha256']:
            raise ValueError('Downloaded model does not match pinned digest: ' + name)
        temporary = target.with_suffix('.onnx.part')
        try:
            temporary.write_bytes(data)
            temporary.replace(target)
        finally:
            temporary.unlink(missing_ok=True)
        print(name, 'downloaded and verified')

if __name__ == '__main__':
    main()
