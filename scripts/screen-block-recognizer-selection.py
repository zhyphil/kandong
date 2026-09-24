#!/usr/bin/env python3
"""Retrospective screening of the block rule frozen in e123c55; no model inference or correction."""
import argparse
import collections
import importlib.util
import json
import unicodedata
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
spec = importlib.util.spec_from_file_location('quality_audit', ROOT / 'scripts/audit-end-to-end-ocr-quality.py')
audit = importlib.util.module_from_spec(spec)
spec.loader.exec_module(audit)

def choose(ch, latin):
    # Only candidate text enters this rule: no fixture id, language label, score, source answer, or amount.
    if any(unicodedata.name(c, '').startswith(('CJK UNIFIED IDEOGRAPH-', 'CJK COMPATIBILITY IDEOGRAPH-')) for c in ch):
        return dict(model='ch', raw=ch, reason='Han glyph evidence in the Chinese candidate')
    if any('LATIN' in unicodedata.name(c, '') and unicodedata.category(c).startswith('L') for c in latin):
        return dict(model='latin', raw=latin, reason='Latin letter evidence in the Latin candidate and no Han evidence in the Chinese candidate')
    return dict(model=None, raw=None, reason='Insufficient glyph evidence; preserve both candidates for review')

def select(ch, latin):
    if len(ch) != len(latin) or any(a['quad'] != b['quad'] for a, b in zip(ch, latin)):
        return dict(state='UNALIGNED', raw=None, blocks=[])
    blocks = []
    for a, b in zip(ch, latin):
        decision = choose(a['raw'], b['raw'])
        blocks.append(dict(**decision, quad=a['quad'], candidates=dict(ch=a['raw'], latin=b['raw'])))
    state = 'EMPTY' if not blocks else 'READY' if all(b['raw'] is not None for b in blocks) else 'REQUIRES_REVIEW'
    return dict(state=state, raw='\n'.join(b['raw'] for b in blocks) if state in ['READY', 'EMPTY'] else None, blocks=blocks)

def checks():
    assert choose('不含稅', '/')['raw'] == '不含稅'
    assert choose('Sortie / 出口', 'Sortie /')['model'] == 'ch'
    assert choose('Ce bilet', 'Ce billet')['raw'] == 'Ce billet'
    assert choose('Echeance', 'Échéance')['model'] == 'latin'
    assert choose('8', '8')['model'] is None
    assert choose('/', '/')['raw'] is None
    q = [[0, 0], [10, 0], [10, 10], [0, 10]]
    a = [dict(quad=q, raw='总计8元')]; b = [dict(quad=q, raw='8')]
    assert select(a, b)['raw'] == '总计8元'
    assert select(a, [])['state'] == 'UNALIGNED'
    shifted = [[0.01, 0], [10, 0], [10, 10], [0, 10]]
    assert select(a, [dict(quad=shifted, raw='8')])['state'] == 'UNALIGNED'
    assert select([], []) == dict(state='EMPTY', raw='', blocks=[])

def evaluate(dataset, id, scale, language, source, ch, latin):
    # Scoring is deliberately AFTER selection; no source fields cross the selector boundary.
    selected = select(ch, latin)
    ch_raw = '\n'.join(b['raw'] for b in ch); latin_raw = '\n'.join(b['raw'] for b in latin)
    expected = audit.normalize(source)
    exact = selected['raw'] is not None and audit.normalize(selected['raw']) == expected
    return dict(dataset=dataset, id=id, scale=scale, languageForScoringOnly=language, source=source,
                isText=bool(expected), selection=selected, exactNormalized=exact,
                chRaw=ch_raw, chExact=audit.normalize(ch_raw) == expected,
                latinRaw=latin_raw, latinExact=audit.normalize(latin_raw) == expected,
                edits=None if selected['raw'] is None else audit.edits(expected, audit.normalize(selected['raw'])))

def main():
    p = argparse.ArgumentParser(description=__doc__); p.add_argument('--output', type=Path, required=True)
    out = p.parse_args().output.resolve(); assert out != ROOT and ROOT not in out.parents
    checks()
    report_path = ROOT / 'docs/evidence/ocr-quality-audit/2026-09-24/quality-audit.json'
    evidence = audit.read(report_path); assert evidence['phone']['repeatsAgree']
    rows = []
    phone = evidence['phone']['rows']
    for id in audit.PHRASES:
        ch = next(r for r in phone if r['id'] == id and r['recognizer'] == 'ch')
        latin = next(r for r in phone if r['id'] == id and r['recognizer'] == 'latin')
        assert ch['source'] == latin['source'] and ch['language'] == latin['language']
        rows.append(evaluate('accepted-phone-five', id, 1, ch['language'], ch['source'], ch['lines'], latin['lines']))
    for dataset, digest in audit.HISTORICAL.items():
        data = audit.read(ROOT / f'docs/evidence/rapidocr/2026-09-24/{dataset}.json', digest)
        pairs = collections.defaultdict(dict)
        for r in data['results']:
            key = (r['inputId'], r['scale']); assert r['recognizer'] not in pairs[key]
            pairs[key][r['recognizer']] = r
        for (id, scale), pair in sorted(pairs.items()):
            assert set(pair) == {'ch', 'latin'}; ch, latin = pair['ch'], pair['latin']
            assert ch['source'] == latin['source'] and ch['fixtureLanguage'] == latin['fixtureLanguage']
            assert ch['inputPixelSha256'] == latin['inputPixelSha256']
            def blocks(r):
                return [dict(raw=text, quad=quad) for text, quad in zip(r['lines'], r['boxesUnscaled'], strict=True)]
            rows.append(evaluate(dataset, id, scale, ch['fixtureLanguage'], ch['source'], blocks(ch), blocks(latin)))
    assert len(rows) == 103
    groups = []
    for key in sorted(set((r['dataset'], r['scale']) for r in rows)):
        group = [r for r in rows if (r['dataset'], r['scale']) == key]; text = [r for r in group if r['isText']]
        groups.append(dict(dataset=key[0], scale=key[1], textImages=len(text),
            chExact=sum(r['chExact'] for r in text), latinExact=sum(r['latinExact'] for r in text), selectedExact=sum(r['exactNormalized'] for r in text),
            improvedVsCh=[r['id'] for r in text if r['exactNormalized'] and not r['chExact']],
            readyWrongRegressionsVsCh=[r['id'] for r in text if r['chExact'] and r['selection']['state'] == 'READY' and not r['exactNormalized']],
            reviewOrAlignmentRequired=[r['id'] for r in text if r['selection']['raw'] is None],
            remainingMismatches=[r['id'] for r in text if not r['exactNormalized']],
            negativeControls=len(group)-len(text), negativeControlsExact=sum(r['exactNormalized'] for r in group if not r['isText'])))
    result = dict(schema=1, ruleFrozenAtLocalCommit='e123c55', rule='Pair exact geometry; Han evidence in ch -> ch; otherwise Latin letters in latin -> latin; otherwise review. Text retained verbatim.',
        scope='Retrospective offline selection of already recorded synthetic outputs, not new OCR inference or unseen-data validation',
        scriptSha256=audit.sha(Path(__file__).read_bytes()), phoneAuditSha256=audit.sha(report_path.read_bytes()), historicalSourceSha256=audit.HISTORICAL,
        selfChecks='10 assertions: Han/mixed/Latin/diacritics/numeric/punctuation/geometry mismatch/length mismatch/empty',
        groups=groups, results=rows,
        limitations=['A model can hallucinate or omit Han; glyph evidence is not reliable language identification',
                     'No calibrated model confidence; no semantic confidence guarantee',
                     'Known reports informed design; fresh locked corpus still required',
                     'Not integrated into Android or translation; same-line mixed scripts remain a risk'])
    out.mkdir(parents=True, exist_ok=True)
    (out/'selection-screening.json').write_text(json.dumps(result, ensure_ascii=False, indent=2, allow_nan=False)+'\n')
    print(json.dumps(groups, ensure_ascii=False, indent=2))

if __name__ == '__main__':
    main()
