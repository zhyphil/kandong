"""Pinned synthetic recorded-provider experiment; no product or live-screen authority.

CLI: --out NEW (dry run), --execute-synthetic --out NEW (explicit credential use),
--review-template RUN --out FILE, --export RUN --review REVIEW --out FILE.

Fingerprint v1 is SHA256 over UTF-8 strings, each preceded by its unsigned 4-byte
big-endian byte length. Numeric integers use base-10; floating coordinates/scores
use signed base-10 IEEE754 binary64 bits (Kotlin Double.toBits().toString()).
Candidates follow association.rawCandidateIds, which are lexically sorted full
IDs. Groups sort by newline-joined sorted stable keys; edges sort by
(left stable key, right stable key, kind). No session, version, batch or time.
canonical_fields(page_id) exposes the complete ordered fields for Kotlin parity.
Raw response hashes cover stored canonical JSON bytes of the unmodified parsed
DeepL envelope, NOT original HTTP wire bytes (the existing client parses JSON).
"""
import argparse
import hashlib
import json
import re
import struct
import time
from collections import Counter
from datetime import datetime, timezone
from pathlib import Path

from translation_deepl import DeepLClient, DeepLError, MAX_BYTES, remaining_characters
from translation_protected_facts import prepare, display
from translation_probe import reject_duplicate_keys

ROOT = Path(__file__).resolve().parents[1]
FIXTURES = ROOT / 'docs/fixtures/full-page-translation-v1'
MANIFEST = 'docs/fixtures/full-page-ocr-v1/manifest.json'
MANIFEST_SHA = 'e95fa1bac48e0a32272a65cf4b85679705597b2bbe3ca69997f038fdf26345f1'
ARCHIVE_DIR = 'docs/evidence/ocr-association-handoff/2026-09-30/emulator-final/'
ARCHIVES = {
    'en-normal': ('latin', '8e1cb6dec61edb7777344b6c10f345ebca95600cb5dabca091392f0c4e1e03c4'),
    'fr-seam': ('latin', 'ed265e5a3926265908a7776e105803d8f38ec9d06abb9cd7b676da219218a724'),
    'hans-normal': ('ch', '79d7e4f9bebac6af68e6f63ad8325e8510ea995ca749400e2fa161a1b6a81c79'),
    'hant-seam': ('ch', '81e98f2a0778574ec6b846a0e9515c9a2c7384fdbec1805bcce77e0262262b2b'),
}
TARGET_ORDINALS = {'en-normal': [1, 2, 3, 6], 'fr-seam': [1, 3, 5, 6, 8, 12, 15, 16, 18, 20]}
AUTHORED = 'AUTHORED_SOURCE'
OCR = 'RECORDED_OCR_SOURCE'
INPUT_SHA = '80d273e04e60784a3f154655821b5072eee6ac2ffddd0f02e4f4df20330942d8'
RUBRIC_SHA = 'e3980f866d37b46f91cefe71dc89195c131a04c6ee24705760c1e87095a43e46'
CHECK_FILES = ['translation_full_page_recorded.py', 'translation_deepl.py',
               'translation_protected_facts.py', 'translation_critical_checks.py',
               'translation_probe.py', 'translation_online_contract.py', 'translation_fact_checks.py', 'deepl_credentials.py']


def require(condition, code='EVIDENCE_MISMATCH'):
    if not condition:
        raise ValueError(code)


def encoded(value):
    return json.dumps(value, ensure_ascii=False, sort_keys=True, separators=(',', ':'), allow_nan=False).encode('utf-8')


def sha(raw):
    return hashlib.sha256(raw).hexdigest()


def parse(raw):
    def bad_constant(_):
        raise ValueError('INVALID_JSON')
    return json.loads(raw, object_pairs_hook=reject_duplicate_keys, parse_constant=bad_constant)


def read(path):
    return parse(Path(path).read_bytes())


def save(path, value):
    Path(path).write_bytes(encoded(value) + b'\n')


def pinned(path, expected):
    raw = Path(path).read_bytes()
    require(sha(raw) == expected, 'PINNED_SOURCE_CHANGED')
    return parse(raw)


def stable_key(full_id):
    match = re.search(r'/(s\d+/c\d+-r\d+-f\d+)$', full_id)
    require(match is not None, 'INVALID_CANDIDATE_ID')
    return match[1]


def known(candidate, group):
    return bool(candidate['rawText'].strip()) and group['text'] != 'DIFFERENT_RAW' and \
        'POSSIBLE_CLIP' not in group['reasons'] and group['geometry'] != 'UNCERTAIN'


def derive_inputs():
    """Derive only the audited paths. No caller-supplied corpus can reach executor."""
    manifest = pinned(ROOT / MANIFEST, MANIFEST_SHA)
    pages = []
    for page_id, (model, archive_sha) in ARCHIVES.items():
        page = next(p for p in manifest['pages'] if p['id'] == page_id)
        archive_path = ARCHIVE_DIR + f'full-page-ocr-{model}-{page_id}.json'
        archive = pinned(ROOT / archive_path, archive_sha)
        require(archive['fixtureSha256'] == MANIFEST_SHA and archive['pageFixtureId'] == page_id)
        association = archive['association']
        require(association['published'] is True and association['rejection'] is None)
        candidates = archive['rawCandidates']
        by_id = {c['id']: c for c in candidates}
        ids = association['rawCandidateIds']
        require(len(by_id) == len(candidates) == len(ids) and ids == sorted(by_id))
        require(len({stable_key(i) for i in ids}) == len(ids))
        groups = {i: g for g in association['groups'] for i in g['memberIds']}
        require(set(groups) == set(ids))
        ocr_targets = [c['id'] for c in candidates if known(c, groups[c['id']])]
        if page['language'] in ('en', 'fr'):
            require(ocr_targets == [candidates[i-1]['id'] for i in TARGET_ORDINALS[page_id]])
        else:
            ocr_targets = []
        blocks = []
        for placement in page['placements']:
            asset = manifest['files'][placement['asset']]
            asset_path = ROOT / 'docs/fixtures/trilingual-holdout-v1' / placement['asset']
            raw_asset = asset_path.read_bytes()
            require(len(raw_asset) == asset['bytes'] and sha(raw_asset) == asset['sha256'], 'PINNED_IMAGE_CHANGED')
            for n, line in enumerate(asset['lines']):
                l, t, r, b = line['glyph']
                blocks.append({'key': f'{placement["id"]}/l{n}', 'text': line['text'],
                    'placement': placement, 'asset': placement['asset'], 'glyph': line['glyph'],
                    'pageBounds': [l+placement['left'], t+placement['top'], r+placement['left'], b+placement['top']],
                    'geometryOrigin': 'AUTHORED_GLYPH_BOUNDS'})
        pages.append({'id': page_id, 'language': page['language'], 'width': page['width'], 'height': page['height'],
            'archivePath': archive_path, 'archiveSha256': archive_sha,
            'authored': {'blocks': blocks, 'context': '\n'.join(b['text'] for b in blocks),
                'targetKeys': [b['key'] for b in blocks if b['placement']['id'] in ('p0', 'p1')]
                              if page['language'] in ('en', 'fr') else []},
            'ocr': {'rawCandidates': candidates, 'association': association,
                'context': '\n'.join(by_id[i]['rawText'] for i in ids),
                'targetIds': [i for i in ids if i in ocr_targets],
                'targetKeys': [stable_key(i) for i in ids if i in ocr_targets]}})
    return {'schema': 1, 'syntheticOnly': True, 'qualityAccepted': False,
            'manifestPath': MANIFEST, 'manifestSha256': MANIFEST_SHA, 'pages': pages}


def inputs():
    value = pinned(FIXTURES / 'inputs.json', INPUT_SHA)
    require(value == derive_inputs())
    pinned(FIXTURES / 'rubric.json', RUBRIC_SHA)
    return value


def double_bits(value):
    return str(struct.unpack('>q', struct.pack('>d', float(value)))[0])


def _canonical_fields(page):
    ocr = page['ocr']; a = ocr['association']; identity = a['identity']; model = identity['model']
    fields = ['recorded-full-page-v1', page['id'], page['language'], str(page['width']), str(page['height']),
              model['id'], model['sha256'], str(model['vocabulary']), identity['detectorSha256'], identity['dictionarySha256']]
    by_id = {c['id']: c for c in ocr['rawCandidates']}
    for cid in a['rawCandidateIds']:
        c = by_id[cid]
        fields += ['candidate', stable_key(cid), c['modelId'], c['rawText'], str(c['stripIndex'])]
        fields += [str(n) for n in c['read'] + c['core']]
        fields += [str(c[k]) for k in ('contourIndex', 'rawBoxIndex', 'finalBoxIndex', 'stripReadingOrder')]
        fields += [double_bits(n) for q in c['localQuad'] + c['pageQuad'] for n in q]
        fields += [double_bits(c['detectorScore']), '1' if c['ownsCoreCenter'] else '0',
                   str(c['recognitionInputShape'][3]), str(c['recognitionOutputShape'][1])]
    groups = [(sorted(stable_key(i) for i in g['memberIds']), g) for g in a['groups']]
    for keys, g in sorted(groups, key=lambda pair: '\n'.join(pair[0])):
        reasons = sorted(g['reasons'])
        fields += ['group', str(len(keys)), *keys, g['text'], g['geometry'], str(len(reasons)), *reasons]
    edges = sorted((stable_key(e['leftId']), stable_key(e['rightId']), e['kind']) for e in a['edges'])
    for left, right, kind in edges:
        fields += ['edge', left, right, kind]
    fields += ['context', '\n'.join(by_id[i]['rawText'] for i in a['rawCandidateIds'])]
    return fields


def canonical_fields(page_id):
    return _canonical_fields(next(p for p in inputs()['pages'] if p['id'] == page_id))


def fingerprint(fields):
    digest = hashlib.sha256()
    for field in fields:
        raw = field.encode('utf-8'); digest.update(struct.pack('>I', len(raw))); digest.update(raw)
    return digest.hexdigest()


def source_hashes():
    fixed = {MANIFEST: MANIFEST_SHA}
    fixed.update({ARCHIVE_DIR + f'full-page-ocr-{model}-{pid}.json': h for pid, (model, h) in ARCHIVES.items()})
    fixed.update({str((FIXTURES / 'inputs.json').relative_to(ROOT)): INPUT_SHA,
                  str((FIXTURES / 'rubric.json').relative_to(ROOT)): RUBRIC_SHA})
    for path, expected in fixed.items():
        require(sha((ROOT / path).read_bytes()) == expected, 'PINNED_SOURCE_CHANGED')
    return {**fixed, **{'scripts/' + name: sha((ROOT / 'scripts' / name).read_bytes()) for name in CHECK_FILES}}


class DeepLAdapter:
    """Provider-neutral rows contain source identity and context; wire schema stays here."""
    @staticmethod
    def request(row):
        require(row['language'] in ('en', 'fr'), 'UNSUPPORTED_LANGUAGE')
        decision = prepare(row['sourceText'], row['language'])
        require(decision['route'] in ('plain', 'protected-money'), 'NON_REMOTE_TARGET')
        body = {'text': [decision['value']['xml'] if decision['route'] == 'protected-money' else row['sourceText']],
                'context': row['context'], 'source_lang': row['language'].upper(),
                'target_lang': 'ZH-HANS', 'show_billed_characters': True}
        if decision['route'] == 'protected-money':
            body.update(tag_handling='xml', tag_handling_version='v2', ignore_tags=['keep'])
        require(len(json.dumps(body, ensure_ascii=False).encode()) <= MAX_BYTES, 'REQUEST_TOO_LARGE')
        return body

    @staticmethod
    def response(row, response):
        require(isinstance(response, dict) and set(response) == {'translations'}, 'INVALID_RESPONSE_ENVELOPE')
        rows = response['translations']
        require(isinstance(rows, list) and len(rows) == 1 and isinstance(rows[0], dict), 'INVALID_RESPONSE_COUNT')
        item = rows[0]
        require(set(item) <= {'text', 'detected_source_language', 'billed_characters', 'model_type_used', 'tag_handling_version'}, 'INVALID_RESPONSE_FIELDS')
        if 'tag_handling_version' in item:
            require(item['tag_handling_version'] in ('v1', 'v2'), 'INVALID_RESPONSE_FIELDS')
        require(item.get('detected_source_language') == row['language'].upper() and
                isinstance(item.get('text'), str) and bool(item['text'].strip()), 'INVALID_RESPONSE_BINDING')
        count = item.get('billed_characters')
        require(type(count) is int and 0 <= count <= len(DeepLAdapter.request(row)['text'][0]), 'BILLING_UNVERIFIED')
        return display({'id': row['key'], 'text': row['sourceText']}, row['page'], row['language'], item['text'])


def plan():
    data = inputs(); rows = []
    for track in (AUTHORED, OCR):
        for page in data['pages']:
            if page['language'] not in ('en', 'fr'):
                continue
            if track == AUTHORED:
                part = page['authored']; selected = [(b['key'], b['key'], b['text']) for b in part['blocks'] if b['key'] in part['targetKeys']]
            else:
                part = page['ocr']; by_id = {c['id']: c for c in part['rawCandidates']}
                selected = [(stable_key(i), i, by_id[i]['rawText']) for i in part['targetIds']]
            for key, full_id, text in selected:
                row = {'track': track, 'page': page['id'], 'key': key, 'sourceId': full_id,
                       'language': page['language'], 'sourceText': text, 'context': part['context']}
                row['id'] = f'{track}/{page["id"]}/{key}'
                row['sourceSha256'] = sha(text.encode('utf-8'))
                row['request'] = DeepLAdapter.request(row)
                row['requestSha256'] = sha(encoded(row['request']))
                rows.append(row)
    require(len(rows) == 23 and len({r['id'] for r in rows}) == 23, 'PLAN_COUNT_CHANGED')
    budget = sum(len(r['request']['text'][0]) for r in rows)
    require(budget <= 5000, 'SOURCE_BUDGET_EXCEEDED')
    return {'schema': 1, 'provider': 'DeepL', 'syntheticOnly': True, 'qualityAccepted': False,
            'inputSha256': INPUT_SHA, 'rubricSha256': RUBRIC_SHA, 'calls': 23,
            'countsByTrack': {AUTHORED: 9, OCR: 14}, 'chineseCalls': 0,
            'maxSourceCharacters': budget, 'paceSeconds': 2, 'retries': 0, 'rows': rows}


def preflight(frozen):
    return {k: v for k, v in frozen.items() if k != 'rows'}


def utc_now():
    return datetime.now(timezone.utc).isoformat().replace('+00:00', 'Z')


def new_run(out):
    frozen = plan(); hashes = source_hashes()
    out = Path(out); out.mkdir(parents=True, exist_ok=False)
    save(out / 'plan.json', frozen); save(out / 'source-hashes.json', hashes)
    save(out / 'preflight.json', preflight(frozen))
    save(out / 'status.json', {'status': 'dryrun', 'completed': 0, 'attempted': 0, 'qualityAccepted': False})
    return frozen


def validate_plan(out):
    frozen = plan()
    require(read(out / 'plan.json') == frozen, 'FROZEN_PLAN_CHANGED')
    require(read(out / 'source-hashes.json') == source_hashes(), 'SOURCE_HASHES_CHANGED')
    require(read(out / 'preflight.json') == preflight(frozen), 'PREFLIGHT_CHANGED')
    return frozen


def check_usage(usage, budget=0):
    left = remaining_characters(usage)
    require(usage['character_limit'] <= 1000000, 'PLAN_LIMIT_NEEDS_REVIEW')
    require(left >= budget, 'INSUFFICIENT_QUOTA')
    return left


def safe_error(error):
    text = str(error)
    fixed = {'INSUFFICIENT_QUOTA', 'PLAN_LIMIT_NEEDS_REVIEW', 'USAGE_UNVERIFIED',
        'REDIRECT_REFUSED', 'CONNECTION_FAILED_NO_RETRY', 'INVALID_RESPONSE', 'RESPONSE_TOO_LARGE',
        'REQUEST_TOO_LARGE', 'INVALID_RESPONSE_ENVELOPE', 'INVALID_RESPONSE_COUNT',
        'INVALID_RESPONSE_FIELDS', 'INVALID_RESPONSE_BINDING', 'BILLING_UNVERIFIED'}
    return text if text in fixed or re.fullmatch(r'HTTP_[1-5]\d\d', text) else 'EXECUTION_FAILED'


def execute(out, client, sleep=time.sleep):
    """Client injection is for existing unittest fakes; CLI alone loads the key."""
    out = Path(out); frozen = validate_plan(out)
    require(read(out / 'status.json')['status'] == 'dryrun', 'RESUME_REFUSED')
    started = utc_now(); completed = attempted = billed = 0
    status = {'status': 'running', 'recordedAt': started, 'qualityAccepted': False, 'retried': False}
    save(out / 'status.json', {**status, 'attempted': 0, 'completed': 0})
    try:
        usage = client.usage(); save(out / 'usage-before.json', usage)
        left = check_usage(usage, frozen['maxSourceCharacters'])
        save(out / 'quota-preflight.json', {'remainingCharacters': left, 'budget': frozen['maxSourceCharacters'], 'passed': True})
        for n, row in enumerate(frozen['rows']):
            if n:
                sleep(2)
            attempted += 1
            save(out / 'status.json', {**status, 'attempted': attempted, 'completed': completed,
                                      'billedCharacters': billed, 'quotaConsumptionUncertain': True})
            response = client._request('/v2/translate', row['request'])
            # Persist the complete unmodified parsed envelope BEFORE shape or binding checks.
            raw = encoded(response)
            (out / f'response-{n:02d}.json').write_bytes(raw)
            receipt = {'id': row['id'], 'requestSha256': row['requestSha256'], 'rawResponseSha256': sha(raw)}
            save(out / f'receipt-{n:02d}.json', receipt)
            bound = DeepLAdapter.response(row, response)
            save(out / f'check-{n:02d}.json', bound)
            billed += response['translations'][0]['billed_characters']; completed += 1
        after = client.usage(); save(out / 'usage-after.json', after); check_usage(after)
        status.update(status='completed', quotaConsumptionUncertain=False)
    except (DeepLError, ValueError, OSError, TypeError, KeyError) as error:
        status.update(status='stopped', error=safe_error(error), quotaConsumptionUncertain=attempted > completed)
    status.update(completed=completed, attempted=attempted, billedCharacters=billed)
    save(out / 'status.json', status)
    return status


def verified_run(out):
    out = Path(out); frozen = validate_plan(out); status = read(out / 'status.json')
    require(status.get('status') == 'completed' and status.get('completed') == 23 and
            status.get('attempted') == 23 and status.get('retried') is False and
            status.get('qualityAccepted') is False and status.get('quotaConsumptionUncertain') is False, 'PARTIAL_RUN')
    require(isinstance(status.get('recordedAt'), str) and status['recordedAt'].endswith('Z'), 'INVALID_RECORDED_TIME')
    datetime.fromisoformat(status['recordedAt'].replace('Z', '+00:00'))
    before = read(out / 'usage-before.json'); after = read(out / 'usage-after.json')
    left = check_usage(before, frozen['maxSourceCharacters']); check_usage(after)
    require(read(out / 'quota-preflight.json') == {'remainingCharacters': left, 'budget': frozen['maxSourceCharacters'], 'passed': True})
    records = []; billed = 0
    for prefix in ('response', 'receipt', 'check'):
        require({p.name for p in out.glob(prefix + '-*.json')} == {f'{prefix}-{i:02d}.json' for i in range(23)}, 'RECORD_COUNT_CHANGED')
    for n, row in enumerate(frozen['rows']):
        raw = (out / f'response-{n:02d}.json').read_bytes(); response = parse(raw)
        receipt = {'id': row['id'], 'requestSha256': row['requestSha256'], 'rawResponseSha256': sha(raw)}
        require(read(out / f'receipt-{n:02d}.json') == receipt, 'RESPONSE_IDENTITY_CHANGED')
        bound = DeepLAdapter.response(row, response)
        require(read(out / f'check-{n:02d}.json') == bound, 'CHECK_CHANGED')
        records.append((row, receipt, bound)); billed += response['translations'][0]['billed_characters']
    require(status.get('billedCharacters') == billed, 'BILLING_CHANGED')
    return frozen, status, records


def review_identity(row, receipt):
    return {k: row[k] for k in ('id', 'track', 'page', 'key', 'sourceId', 'sourceSha256', 'requestSha256')} | \
        {'rawResponseSha256': receipt['rawResponseSha256']}


def review_template(run):
    frozen, _, records = verified_run(run)
    return {'schema': 1, 'kind': 'DEVELOPER_REVIEW', 'inputSha256': INPUT_SHA, 'rubricSha256': RUBRIC_SHA,
            'planSha256': sha(encoded(frozen)), 'reviewer': None,
            'entries': [{**review_identity(row, receipt), 'verdict': None, 'sourceQuality': None,
                         'contextDefects': None, 'notes': None} for row, receipt, _ in records]}


def validate_review(run, review):
    template = review_template(run)
    require(set(review) == set(template), 'REVIEW_ENVELOPE_CHANGED')
    for k in ('schema', 'kind', 'inputSha256', 'rubricSha256', 'planSha256'):
        require(review[k] == template[k], 'REVIEW_IDENTITY_CHANGED')
    require(isinstance(review['reviewer'], str) and bool(review['reviewer'].strip()), 'REVIEWER_REQUIRED')
    require(isinstance(review['entries'], list) and len(review['entries']) == 23, 'REVIEW_COUNT_CHANGED')
    by_id = {r['id']: r for r in review['entries']}
    require(len(by_id) == 23 and set(by_id) == {r['id'] for r in template['entries']}, 'REVIEW_IDENTITY_CHANGED')
    for expected in template['entries']:
        actual = by_id[expected['id']]
        require(set(actual) == set(expected), 'REVIEW_FIELDS_CHANGED')
        for k in ('id', 'track', 'page', 'key', 'sourceId', 'sourceSha256', 'requestSha256', 'rawResponseSha256'):
            require(actual[k] == expected[k], 'REVIEW_IDENTITY_CHANGED')
        require(actual['verdict'] in ('pass', 'fail', 'uncertain'), 'REVIEW_VERDICT_REQUIRED')
        require(actual['sourceQuality'] in ('correct', 'incorrect', 'uncertain'), 'SOURCE_REVIEW_REQUIRED')
        require(isinstance(actual['contextDefects'], str) and isinstance(actual['notes'], str) and
                bool(actual['notes'].strip()), 'REVIEW_NOTES_REQUIRED')
    return by_id


def export_packet(run, review_path):
    frozen, status, records = verified_run(run)
    review_raw = Path(review_path).read_bytes(); reviews = validate_review(run, parse(review_raw))
    outcomes = {}; diagnostics = []; summaries = {}
    for row, receipt, bound in records:
        review = reviews[row['id']]; rule_pass = bound['state'] == 'candidate-unverified'
        allowed = review['verdict'] == 'pass' and review['sourceQuality'] == 'correct' and rule_pass
        diagnostic = {**review_identity(row, receipt), 'sourceQuality': review['sourceQuality'],
                      'contextDefects': review['contextDefects'], 'verdict': review['verdict'],
                      'rulePassed': rule_pass, 'ruleReasons': bound['check']['reasons'],
                      'correctButRuleRejected': review['verdict'] == 'pass' and not rule_pass,
                      'uiCandidate': row['track'] == OCR and allowed, 'semanticVerified': False}
        diagnostics.append(diagnostic)
        if row['track'] == OCR:
            outcomes[(row['page'], row['key'])] = {'key': row['key'], 'sourceText': row['sourceText'],
                'chinese': bound['translation'] if allowed else None,
                'kind': 'CANDIDATE' if allowed else 'KEEP_ORIGINAL',
                'origin': 'RECORDED_DEEPL' if allowed else 'SOURCE',
                'reason': None if allowed else 'CHECK_UNVERIFIED', 'semanticVerified': False}
    for track in (AUTHORED, OCR):
        subset = [d for d in diagnostics if d['track'] == track]
        summaries[track] = {'providerCompleted': len(subset),
            'sourceQuality': dict(Counter(d['sourceQuality'] for d in subset)),
            'semanticReview': dict(Counter(d['verdict'] for d in subset)),
            'ruleRejected': sum(not d['rulePassed'] for d in subset),
            'correctButRuleRejected': sum(d['correctButRuleRejected'] for d in subset),
            'uiCandidates': sum(d['uiCandidate'] for d in subset)}
    pages = []
    for page in inputs()['pages']:
        keys = page['ocr']['targetKeys']; fields = _canonical_fields(page)
        warnings = ['OCR_COVERAGE_UNVERIFIED', 'FIXED_FIXTURE_LANGUAGE_DECLARED', 'SPATIAL_GROUPS_ARE_NOT_SEMANTIC']
        if any(g['geometry'] == 'UNCERTAIN' or g['text'] == 'DIFFERENT_RAW' or g['reasons'] for g in page['ocr']['association']['groups']):
            warnings.append('OCR_CONTEXT_HAS_UNVERIFIED_STRUCTURE')
        pages.append({k: page[k] for k in ('id', 'language', 'width', 'height')} | {
            'fingerprint': fingerprint(fields), 'canonicalFields': fields, 'targetKeys': keys,
            'outcomes': [outcomes[(page['id'], k)] for k in keys], 'contextWarnings': warnings})
    return {'schema': 1, 'syntheticOnly': True, 'qualityAccepted': False, 'semanticVerified': False,
            'provider': 'DeepL', 'mode': 'RECORDED', 'inputSha256': INPUT_SHA,
            'reviewSha256': sha(review_raw), 'recordedAt': status['recordedAt'], 'pages': pages,
            'report': {'preflight': preflight(frozen), 'usageBefore': read(Path(run) / 'usage-before.json'),
                       'usageAfter': read(Path(run) / 'usage-after.json'), 'status': status,
                       'countsByTrack': summaries, 'diagnostics': diagnostics,
                       'reviewKind': 'DEVELOPER_REVIEW_NOT_BLIND_QUALITY_ACCEPTANCE'}}


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--out', type=Path, required=True)
    mode = parser.add_mutually_exclusive_group()
    mode.add_argument('--execute-synthetic', action='store_true')
    mode.add_argument('--export', type=Path)
    mode.add_argument('--review-template', type=Path)
    parser.add_argument('--review', type=Path)
    args = parser.parse_args(argv)
    try:
        require(bool(args.export) == bool(args.review), 'EXPORT_REQUIRES_REVIEW')
        if args.export or args.review_template:
            require(not args.out.exists(), 'OUTPUT_EXISTS')
            result = export_packet(args.export, args.review) if args.export else review_template(args.review_template)
            save(args.out, result); return 0
        frozen = new_run(args.out)
        print(json.dumps(preflight(frozen)), flush=True)
        if not args.execute_synthetic:
            return 0
        # Deliberately imported only in the explicit execution branch.
        from deepl_credentials import load_key
        try:
            client = DeepLClient(load_key())
        except (ValueError, OSError):
            save(args.out / 'status.json', {'status': 'stopped', 'error': 'CREDENTIAL_UNAVAILABLE',
                                          'completed': 0, 'attempted': 0, 'qualityAccepted': False})
            return 1
        status = execute(args.out, client)
        print(json.dumps(status), flush=True)
        return 0 if status['status'] == 'completed' else 1
    except (ValueError, OSError, KeyError, TypeError, AttributeError, IndexError, StopIteration):
        print('RECORDED_EVIDENCE_REJECTED', flush=True)
        return 1


if __name__ == '__main__':
    raise SystemExit(main())
