"""Offline contract tests. Fakes prove routing/guards, never provider quality."""
import copy
import json
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

import translation_full_page_recorded as m
from translation_deepl import DeepLError


class FakeClient:
    def __init__(self, failure=None, quota=1000000):
        self.calls = []; self.usage_calls = 0; self.failure = failure; self.quota = quota

    def usage(self):
        self.usage_calls += 1
        return {'character_count': 0, 'character_limit': self.quota}

    def _request(self, path, body):
        self.calls.append(copy.deepcopy(body))
        if isinstance(self.failure, Exception):
            raise self.failure
        if self.failure is not None:
            return self.failure
        # Deliberately echoed source: transport fixture, NOT claimed real translation.
        return {'translations': [{'text': body['text'][0], 'detected_source_language': body['source_lang'],
                                  'billed_characters': len(body['text'][0])}]}


class RecordedTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory(prefix='kandong-recorded-test-')
        self.addCleanup(self.tmp.cleanup)
        self.root = Path(self.tmp.name)

    def completed(self):
        run = self.root / 'run'; m.new_run(run)
        client = FakeClient(); sleeps = []
        result = m.execute(run, client, sleep=sleeps.append)
        self.assertEqual('completed', result['status'])
        self.assertEqual(23, len(client.calls)); self.assertEqual([2] * 22, sleeps)
        self.assertEqual(2, client.usage_calls)
        return run

    def review(self, run, verdict='uncertain'):
        review = m.review_template(run); review['reviewer'] = 'offline test fixture'
        for entry in review['entries']:
            entry.update(verdict=verdict, sourceQuality='uncertain', contextDefects='Not evaluated by this fake.',
                         notes='Contract fixture only, no real quality claim.')
        path = self.root / 'review.json'; m.save(path, review)
        return path, review

    def test_plan_counts_whole_context_tracks_chinese_zero(self):
        plan = m.plan(); data = m.inputs()
        self.assertEqual(23, plan['calls']); self.assertLessEqual(plan['maxSourceCharacters'], 5000)
        self.assertEqual({m.AUTHORED: 9, m.OCR: 14}, plan['countsByTrack'])
        self.assertEqual(0, plan['chineseCalls'])
        self.assertEqual(53, sum(len(p['ocr']['rawCandidates']) for p in data['pages']))
        self.assertEqual([8, 15, 8, 12], [len(p['authored']['blocks']) for p in data['pages']])
        for row in plan['rows']:
            page = next(p for p in data['pages'] if p['id'] == row['page'])
            part = page['authored' if row['track'] == m.AUTHORED else 'ocr']
            self.assertEqual(part['context'], row['request']['context'])
            self.assertNotIn(row['language'], ('zh-Hans', 'zh-Hant'))
            self.assertNotIn('rubric', json.dumps(row['request']))
        fr_ocr = next(r for r in plan['rows'] if r['page'] == 'fr-seam' and r['track'] == m.OCR)
        fr_clean = next(r for r in plan['rows'] if r['page'] == 'fr-seam' and r['track'] == m.AUTHORED)
        self.assertIn('Pnx totar": oz,oo e.', fr_ocr['context'])
        self.assertNotIn('Pnx totar', fr_clean['context'])
        self.assertEqual(20, len(fr_ocr['context'].splitlines()))
        self.assertEqual(15, len(fr_clean['context'].splitlines()))

    def test_exact_duplicates_keep_distinct_ids_and_calls(self):
        rows = [r for r in m.plan()['rows'] if r['track'] == m.OCR and r['sourceText'] == 'confirmation.']
        self.assertEqual(4, len(rows)); self.assertEqual(4, len({r['id'] for r in rows}))
        run = self.completed()
        receipts = [m.read(run / f'receipt-{i:02d}.json') for i in range(23)]
        self.assertEqual(23, len({r['id'] for r in receipts}))

    def test_canonical_fields_fingerprint_and_context(self):
        page = m.inputs()['pages'][0]
        fields = m.canonical_fields('en-normal')
        self.assertEqual(m._canonical_fields(page), fields)
        self.assertEqual(['context', page['ocr']['context']], fields[-2:])
        self.assertEqual('4607182418800017408', m.double_bits(1))
        self.assertEqual('-9223372036854775808', m.double_bits(-0.0))
        self.assertEqual(64, len(m.fingerprint(fields)))
        changed = copy.deepcopy(page); changed['ocr']['rawCandidates'][-1]['rawText'] += '!'
        self.assertNotEqual(m.fingerprint(fields), m.fingerprint(m._canonical_fields(changed)))
        changed = copy.deepcopy(page); changed['ocr']['rawCandidates'][-1]['detectorScore'] += .00001
        self.assertNotEqual(m.fingerprint(fields), m.fingerprint(m._canonical_fields(changed)))
        changed = copy.deepcopy(page); changed['ocr']['association']['identity']['sourceBatch'] = 'new-session'
        self.assertEqual(fields, m._canonical_fields(changed))

    def test_pinned_archive_change_rejected(self):
        original = Path.read_bytes
        def altered(path):
            raw = original(path)
            return raw + b' ' if path.name == 'full-page-ocr-latin-en-normal.json' else raw
        with patch.object(Path, 'read_bytes', altered):
            with self.assertRaisesRegex(ValueError, 'PINNED_SOURCE_CHANGED'):
                m.plan()

    def test_source_hash_or_frozen_plan_change_rejected(self):
        run = self.completed()
        hashes = m.read(run / 'source-hashes.json'); hashes['scripts/translation_deepl.py'] = '0' * 64
        m.save(run / 'source-hashes.json', hashes)
        with self.assertRaisesRegex(ValueError, 'SOURCE_HASHES_CHANGED'):
            m.review_template(run)
        m.save(run / 'source-hashes.json', m.source_hashes())
        plan = m.read(run / 'plan.json'); plan['rows'][0]['context'] = 'cropped'
        m.save(run / 'plan.json', plan)
        with self.assertRaisesRegex(ValueError, 'FROZEN_PLAN_CHANGED'):
            m.review_template(run)

    def test_quota_zero_calls_and_no_retry(self):
        for quota in (0, 1000001):
            with self.subTest(quota=quota):
                run = self.root / str(quota); m.new_run(run); client = FakeClient(quota=quota)
                result = m.execute(run, client, sleep=lambda _: self.fail('must not sleep'))
                self.assertEqual('stopped', result['status']); self.assertEqual([], client.calls)
                self.assertFalse(result['retried']); self.assertEqual(1, client.usage_calls)

    def test_http_first_stop_safe_error_no_retry(self):
        for i, error in enumerate((DeepLError('HTTP_429'), DeepLError('secret-bearing-error'))):
            run = self.root / str(i); m.new_run(run); client = FakeClient(failure=error)
            result = m.execute(run, client, sleep=lambda _: self.fail('no retry'))
            self.assertEqual('stopped', result['status']); self.assertEqual(1, len(client.calls))
            self.assertEqual(0, result['completed']); self.assertEqual(1, client.usage_calls)
            self.assertNotIn('secret', json.dumps(result)); self.assertTrue(result['quotaConsumptionUncertain'])
            with self.assertRaisesRegex(ValueError, 'PARTIAL_RUN'):
                m.review_template(run)
            with self.assertRaisesRegex(ValueError, 'RESUME_REFUSED'):
                m.execute(run, client)

    def test_bad_response_saved_before_binding_first_stop(self):
        for i, response in enumerate(({'translations': []}, {'translations': [], 'geometry': []},
                {'translations': [{'text': 'x', 'detected_source_language': 'FR', 'billed_characters': 1}]})):
            run = self.root / str(i); m.new_run(run); client = FakeClient(failure=response)
            status = m.execute(run, client, sleep=lambda _: self.fail('no retry'))
            self.assertEqual('stopped', status['status']); self.assertEqual(1, len(client.calls))
            self.assertEqual(response, m.read(run / 'response-00.json'))
            self.assertFalse((run / 'check-00.json').exists())

    def test_money_bad_xml_rejected_by_existing_rule(self):
        row = next(r for r in m.plan()['rows'] if 'tag_handling' in r['request'])
        result = m.DeepLAdapter.response(row, {'translations': [{'text': '<segment>坏的</segment>',
                      'detected_source_language': row['language'].upper(), 'billed_characters': 1}]})
        self.assertEqual('original-with-warning', result['state'])
        self.assertIn('protected-money-binding-unverified', result['check']['reasons'])

    def test_template_assumes_nothing_and_uncertain_export_withholds(self):
        run = self.completed(); template = m.review_template(run)
        self.assertTrue(all(e['verdict'] is None for e in template['entries']))
        with self.assertRaises(ValueError):
            m.validate_review(run, template)
        path, _ = self.review(run); packet = m.export_packet(run, path)
        self.assertFalse(packet['semanticVerified']); self.assertFalse(packet['qualityAccepted'])
        self.assertEqual(14, sum(len(p['outcomes']) for p in packet['pages']))
        for page in packet['pages']:
            self.assertEqual(m.fingerprint(m.canonical_fields(page['id'])), page['fingerprint'])
            if page['language'].startswith('zh'):
                self.assertEqual([], page['targetKeys']); self.assertEqual([], page['outcomes'])
            for outcome in page['outcomes']:
                self.assertEqual('KEEP_ORIGINAL', outcome['kind']); self.assertIsNone(outcome['chinese'])

    def test_review_swaps_duplicates_missing_changed_and_response_tamper(self):
        run = self.completed(); _, review = self.review(run)
        mutations = []
        changed = copy.deepcopy(review); changed['entries'][0]['key'] = changed['entries'][1]['key']; mutations.append(changed)
        changed = copy.deepcopy(review); changed['entries'][0] = changed['entries'][1]; mutations.append(changed)
        changed = copy.deepcopy(review); changed['entries'].pop(); mutations.append(changed)
        changed = copy.deepcopy(review); changed['entries'][0]['rawResponseSha256'] = '0'*64; mutations.append(changed)
        changed = copy.deepcopy(review); changed['entries'][0]['track'] = m.OCR; mutations.append(changed)
        for changed in mutations:
            with self.assertRaises(ValueError):
                m.validate_review(run, changed)
        raw = (run / 'response-00.json').read_bytes(); (run / 'response-00.json').write_bytes(raw + b' ')
        with self.assertRaisesRegex(ValueError, 'RESPONSE_IDENTITY_CHANGED'):
            m.validate_review(run, review)

    def test_authored_pass_cannot_fill_ocr_and_rule_rejection_is_distinct(self):
        run = self.completed(); path, review = self.review(run)
        for item in review['entries']:
            if item['track'] == m.AUTHORED:
                item['verdict'] = 'pass'
        m.save(path, review); packet = m.export_packet(run, path)
        self.assertTrue(all(o['kind'] == 'KEEP_ORIGINAL' for p in packet['pages'] for o in p['outcomes']))
        self.assertGreater(packet['report']['countsByTrack'][m.AUTHORED]['correctButRuleRejected'], 0)

    def test_only_individually_reviewed_ocr_rule_pass_becomes_candidate(self):
        class ChineseFixtureClient(FakeClient):
            def _request(self, path, body):
                result = super()._request(path, body)
                if body['text'] == ['Breakfast is not included.']:
                    result['translations'][0]['text'] = '不含早餐。'
                return result
        run = self.root / 'run'; m.new_run(run)
        self.assertEqual('completed', m.execute(run, ChineseFixtureClient(), sleep=lambda _: None)['status'])
        path, review = self.review(run)
        selected = next(r for r in m.plan()['rows'] if r['track'] == m.OCR and
                        r['sourceText'] == 'Breakfast is not included.')
        selected_review = next(e for e in review['entries'] if e['id'] == selected['id'])
        selected_review.update(verdict='pass', sourceQuality='correct')
        m.save(path, review); packet = m.export_packet(run, path)
        candidates = [o for p in packet['pages'] for o in p['outcomes'] if o['kind'] == 'CANDIDATE']
        self.assertEqual(1, len(candidates))
        self.assertEqual(selected['key'], candidates[0]['key'])
        self.assertEqual('RECORDED_DEEPL', candidates[0]['origin'])
        self.assertEqual('不含早餐。', candidates[0]['chinese'])
        self.assertFalse(candidates[0]['semanticVerified'])
        selected_review['sourceQuality'] = 'incorrect'
        m.save(path, review)
        withheld = m.export_packet(run, path)
        self.assertTrue(all(o['kind'] == 'KEEP_ORIGINAL' for p in withheld['pages'] for o in p['outcomes']))

    def test_documented_tag_metadata_does_not_replace_binding_checks(self):
        row = next(r for r in m.plan()['rows'] if 'tag_handling' in r['request'])
        response = {'translations':[{'text':row['request']['text'][0], 'detected_source_language':row['language'].upper(),
            'billed_characters':1, 'tag_handling_version':'v2'}]}
        self.assertIsInstance(m.DeepLAdapter.response(row, response), dict)
        response['translations'][0]['tag_handling_version'] = 'unknown'
        with self.assertRaises(ValueError): m.DeepLAdapter.response(row,response)

    def test_dryrun_export_template_never_load_key(self):
        with patch('deepl_credentials.load_key', side_effect=AssertionError('credential read')):
            self.assertEqual(0, m.main(['--out', str(self.root / 'dryrun')]))
            run = self.completed(); path, _ = self.review(run)
            self.assertEqual(0, m.main(['--review-template', str(run), '--out', str(self.root / 'template.json')]))
            self.assertEqual(0, m.main(['--export', str(run), '--review', str(path), '--out', str(self.root / 'packet.json')]))

    def test_json_duplicate_fields_and_nonfinite_rejected(self):
        for raw in ('{"x":1,"x":2}', '{"x":NaN}'):
            with self.assertRaises(ValueError):
                m.parse(raw)


if __name__ == '__main__':
    unittest.main()
