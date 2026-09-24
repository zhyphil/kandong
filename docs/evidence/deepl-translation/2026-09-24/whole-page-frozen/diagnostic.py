"""Bounded whole-text diagnostic, never creates element-bound translations."""
import hashlib
import json
import sys
import time
from pathlib import Path

ROOT = Path('/Users/haoyuzuo/Projects/KanDong')
sys.path.insert(0, str(ROOT / 'scripts'))
from deepl_credentials import load_key
from translation_deepl import DeepLClient, DeepLError, remaining_characters
from translation_deepl_plain import plain_request
from translation_online_contract import case_by_id


def save(path, data): path.write_text(json.dumps(data, ensure_ascii=False, indent=2) + '\n')


def main():
    import argparse
    parser = argparse.ArgumentParser()
    parser.add_argument('--out', type=Path, required=True)
    parser.add_argument('--execute-synthetic', action='store_true')
    args = parser.parse_args()
    rows = []
    for n in (1, 2):
        for page in ('p106', 'p304', 'p003'):
            c = case_by_id(page)
            body = plain_request(page, c['targetIds'][0])
            body['text'] = [body.pop('context')]
            rows.append({'round': n, 'page': page, 'request': body})
    chars = sum(len(r['request']['text'][0]) for r in rows)
    assert chars <= 3000
    args.out.mkdir(parents=True, exist_ok=False)
    plan = {'calls': len(rows), 'sourceCharacters': chars, 'syntheticOnly': True,
            'diagnosticOnly': True, 'elementBinding': False,
            'purpose': 'Check whether whole-page text resolves date/currency failures; not product output',
            'paceSeconds': 2, 'retries': 0, 'rows': rows}
    save(args.out / 'plan.json', plan)
    names = ['translation_deepl.py', 'translation_deepl_plain.py', 'translation_online_contract.py',
             'translation_candidate_c.py', 'translation_candidate_v3.py', 'translation_probe.py',
             'translation_probe_v2.py', 'deepl_credentials.py']
    save(args.out / 'source-hashes.json', {**{n: hashlib.sha256((ROOT/'scripts'/n).read_bytes()).hexdigest() for n in names},
                                         'diagnostic-script': hashlib.sha256(Path(__file__).read_bytes()).hexdigest()})
    print(json.dumps({'calls': len(rows), 'sourceCharacters': chars, 'execute': args.execute_synthetic}), flush=True)
    if not args.execute_synthetic: return 0
    count = 0; attempted = 0
    try:
        client = DeepLClient(load_key())
        usage = client.usage(); save(args.out / 'usage-before.json', usage)
        left = remaining_characters(usage)
        if usage['character_limit'] > 1000000: raise DeepLError('PLAN_LIMIT_NEEDS_REVIEW')
        if left < chars: raise DeepLError('INSUFFICIENT_QUOTA')
        for i, row in enumerate(rows):
            time.sleep(2)
            attempted += len(row['request']['text'][0]); start = time.monotonic()
            response = client._request('/v2/translate', row['request'])
            if not isinstance(response, dict) or set(response) != {'translations'}: raise ValueError()
            values = response['translations']
            if not isinstance(values, list) or len(values) != 1 or not isinstance(values[0], dict): raise ValueError()
            value = values[0]
            if value.get('detected_source_language') != row['request']['source_lang']: raise ValueError()
            if not isinstance(value.get('text'), str) or not value['text'].strip(): raise ValueError()
            save(args.out / f'response-{i:03}.json', {**row, 'rawWholePageTranslation': value['text'],
                 'responseMetadata': {k: value[k] for k in ('detected_source_language', 'billed_characters', 'model_type_used') if k in value},
                 'elementBinding': False, 'seconds': time.monotonic() - start})
            count += 1
            print(json.dumps({'completed': count}), flush=True)
        save(args.out / 'usage-after.json', client.usage())
        status = {'status': 'completed', 'completed': count, 'attemptedSourceCharacters': attempted, 'qualityAccepted': False}
    except (DeepLError, ValueError, OSError) as error:
        status = {'status': 'stopped', 'completed': count, 'attemptedSourceCharacters': attempted,
                  'error': str(error) if isinstance(error, DeepLError) else type(error).__name__, 'retried': False, 'qualityAccepted': False}
    save(args.out / 'status.json', status)
    return 0 if status['status'] == 'completed' else 1


if __name__ == '__main__': raise SystemExit(main())
