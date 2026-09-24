#!/usr/bin/env python3
"""Score accepted synthetic OCR reports against authored text; never route/correct model output."""
import argparse
import collections
import difflib
import hashlib
import json
import unicodedata
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
EVIDENCE = ROOT / 'docs/evidence/end-to-end-ocr-probe/2026-09-24'
HISTORICAL = {
    'baseline72': 'fe2ee51156b4fca67744efaa62eee68c4dead0be58a69321cfe891892b78da29',
    'holdout124': '64c37cf7b2595cc1fed7f28563f497b4adb20ae1659696a23266da8a20ee6757',
}
# Literal diagnostic phrases only, not a language router or semantic equivalence evaluator.
PHRASES = {
    'en-quality-16': ['39.90 €', 'not included'],
    'fr-nonrefundable-16': ["n'est", 'ni échangeable', 'ni remboursable'],
    'zh-hans-quality-16': ['1234.50元', '不含税', '8公斤', '不可退款'],
    'zh-hant-quality-16': ['1234.50元', '不含稅', '8公斤', '不可退款'],
    'mixed-quality-16': ['房间A', 'Room A', '120 € / night', '不含税', '不含稅'],
}

def sha(raw):
    return hashlib.sha256(raw).hexdigest()

def read(path, expected=None):
    raw = path.read_bytes()
    if expected is not None:
        assert sha(raw) == expected, path
    return json.loads(raw)

def normalize(text):
    # Exact existing screening rule: NFC + Unicode White_Space folding, NOT str.split's extra C0 separators.
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

def edits(expected, actual):
    return [dict(kind=kind, expected=expected[a:b], actual=actual[c:d], expectedRange=[a, b], actualRange=[c, d])
            for kind, a, b, c, d in difflib.SequenceMatcher(None, expected, actual, autojunk=False).get_opcodes() if kind != 'equal']

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--output', type=Path, required=True)
    out = parser.parse_args().output.resolve()
    assert out != ROOT and ROOT not in out.parents
    assert normalize('e\u0301\u202f A\tB') == 'é A B'
    assert normalize('A/B') != normalize('A / B') and normalize('税') != normalize('稅')
    manifest_path = ROOT / 'ocrlab/src/main/assets/trilingual-v1/manifest.json'
    manifest = read(manifest_path, '2341dff63bd10d41867351ddff0e4145daa2d9d3493bc8cdc88ea289c1a076a8')
    ground_truth = {Path(r['asset']).stem: r for r in manifest['images']}
    accepted = read(EVIDENCE / 'huawei/acceptance-summary.json')
    assert accepted['status'] == 'accepted-synthetic-original-to-ocr-on-target-Huawei' and accepted['rounds'] == 2
    reports = [read(EVIDENCE / f'huawei/e2e-{i}-report.json') for i in [1, 2]]
    assert reports[0]['models'] == reports[1]['models']
    for report in reports:
        assert report['passed'] and report['runId'] in accepted['runIds'] and report['api'] == 31 and report['device'] == 'LIO-AN00'
        assert len(report['cases']) == len(report['models']) == 10
    report = reports[0]
    cases = {c['id']: c for c in report['cases']}
    rows = []
    for model in report['models']:
        recognizer, id = model['id'].split('/')
        assert id in PHRASES and model['passed']
        source = ground_truth[id]
        assert cases[id]['sourcePngSha256'] == source['pngSha256']
        bindings = model['bindings']
        assert [b['readingOrder'] for b in bindings] == list(range(len(bindings)))
        lines = [b['raw'] for b in bindings]
        assert len(lines) == len(source['renderedLines'])
        raw = '\n'.join(lines)
        expected, actual = normalize(source['source']), normalize(raw)
        line_rows = [dict(boxId=b['boxId'], quad=b['quad'], source=text, raw=b['raw'],
                          exactNormalized=normalize(text) == normalize(b['raw']))
                     for b, text in zip(bindings, source['renderedLines'], strict=True)]
        phrases = [dict(sourcePhrase=text, literalPresent=normalize(text) in actual) for text in PHRASES[id]]
        assert all(normalize(text) in expected for text in PHRASES[id])
        rows.append(dict(id=id, recognizer=recognizer, language=source['language'], fontPx=source['fontPx'],
                         source=source['source'], raw=raw, exactNormalized=expected == actual,
                         lines=line_rows, literalPhraseChecks=phrases, edits=edits(expected, actual)))
    summary = {}
    for model in ['ch', 'latin']:
        group = [r for r in rows if r['recognizer'] == model]
        summary[model] = dict(textPages=len(group), exactPages=sum(r['exactNormalized'] for r in group),
                             textLines=sum(len(r['lines']) for r in group), exactLines=sum(l['exactNormalized'] for r in group for l in r['lines']),
                             literalPhrases=sum(len(r['literalPhraseChecks']) for r in group),
                             literalPhrasesPresent=sum(p['literalPresent'] for r in group for p in r['literalPhraseChecks']))
    historical = []
    for name, digest in HISTORICAL.items():
        data = read(ROOT / f'docs/evidence/rapidocr/2026-09-24/{name}.json', digest)
        assert data['scope'] == 'Mac_CPU_only_fixed_synthetic_quality_screening_not_Android'
        buckets = collections.defaultdict(list)
        for r in data['results']:
            exact = normalize(r['source']) == normalize(r['recognizedRaw'])
            assert exact == r['exactNormalizedMatch']
            buckets[(r['recognizer'], r['scale'], r['fixtureLanguage'], bool(normalize(r['source'])))].append(r)
        groups = [dict(recognizer=k[0], scale=k[1], language=k[2], isText=k[3], inputs=len(v),
                       exact=sum(r['exactNormalizedMatch'] for r in v), failures=[r['inputId'] for r in v if not r['exactNormalizedMatch']])
                  for k, v in sorted(buckets.items())]
        historical.append(dict(name=name, reportSha256=digest, observations=len(data['results']),
                               provenance='Previously recorded Mac CPU synthetic screening, no new inference or Android coverage claim', groups=groups))
    sources = [manifest_path, EVIDENCE / 'huawei/acceptance-summary.json'] + [EVIDENCE / f'huawei/e2e-{i}-report.json' for i in [1, 2]]
    result = dict(schema=1, scope='quality_audit_of_fixed_synthetic_evidence_not_production_acceptance',
                  normalization='NFC + Unicode White_Space folding and trim only; punctuation and internal spacing remain significant',
                  policy='No best-per-input model selection, no correction, no language label fed to inference; literal phrase presence is not a semantic guarantee',
                  scriptSha256=sha(Path(__file__).read_bytes()), sourceSha256={str(p.relative_to(ROOT)): sha(p.read_bytes()) for p in sources},
                  phone=dict(device='LIO-AN00/API31', originalTextImages=5, fontPx=16, repeatsAgree=True, summary=summary, rows=rows,
                             emptyControls=5, emptyControlsNoRecognition=all(c.get('noRecognitionWork', False) for c in report['cases'][5:])),
                  historicalHost=historical,
                  limitations=['Only five 16px text images on this complete Android chain',
                               '49 historical host images are not 49 new Android tests',
                               'Not a general accuracy estimate; no semantic translation evaluation',
                               'No real screens, model routing, confidence policy, or translation integration'])
    assert len(rows) == 10 and sum(len(r['lines']) for r in rows) == 18
    assert summary['ch']['exactPages'] == 3 and summary['latin']['exactPages'] == 2
    assert result['phone']['emptyControlsNoRecognition']
    out.mkdir(parents=True, exist_ok=True)
    (out / 'quality-audit.json').write_text(json.dumps(result, ensure_ascii=False, indent=2, allow_nan=False) + '\n')
    print(json.dumps(summary, ensure_ascii=False))

if __name__ == '__main__':
    main()
