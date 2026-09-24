"""Host contract tests with invented evidence, never language-model quality claims."""
import copy
import unittest
from lingua_candidate import (EvidenceError, ObservationSession, page_id, queries, route,
                              validate_observation)


def block(raw='A complete sentence.', name='target', group='a', conflict=False):
    return dict(id=name, groupId=group, text=raw, ocrConflict=conflict)


def page(*blocks):
    return dict(pageId=page_id(list(blocks)), targetId='target', blocks=list(blocks))


def observation(raw, language='en', score=1):
    candidates = [dict(language=language, score=score)]
    if score < 1:
        candidates.append(dict(language='de' if language != 'de' else 'en', score=1-score))
        candidates.sort(key=lambda c: -c['score'])
    return dict(raw=raw, candidates=candidates, segments=[dict(start=0, end=len(raw), raw=raw,
                wordCount=1, language=language)] if raw else [])


def evidence(p, language='en'):
    return {k: dict(observation(raw, language), pageId=p['pageId'], key=k) for k, raw in queries(p).items()}


class PolicyTests(unittest.TestCase):
    def test_direct_keeps_raw_and_chinese(self):
        for lang, raw, action in [('en', '  The fee excludes tax. ', 'translate'),
                                  ('fr', 'Le prix ne comprend pas le repas.', 'translate'),
                                  ('zh', '此票价不包含早餐和服务费。', 'keep')]:
            p = page(block(raw)); e = evidence(p, lang); saved = copy.deepcopy(e)
            result = route(p, e)
            self.assertEqual((action, lang, raw), (result['action'], result['language'], result['raw']))
            self.assertEqual(saved, e)

    def test_identity_and_missing_evidence_rejected(self):
        p = page(block()); good = evidence(p)
        for mutate in [lambda e: e.pop('block:target'),
                       lambda e: e['block:target'].update(pageId='other'),
                       lambda e: e['block:target'].update(key='page'),
                       lambda e: e['block:target'].update(raw='rewritten')]:
            e = copy.deepcopy(good); mutate(e)
            self.assertEqual('invalid-evidence', route(p, e)['reason'])
        p['pageId'] = 'old'; self.assertEqual('invalid-evidence', route(p, good)['reason'])

    def test_different_card_cannot_supply_short_anchor(self):
        p = page(block('Go'), block(name='other', group='b'))
        self.assertEqual('review', route(p, evidence(p))['action'])

    def test_same_card_short_anchor_and_own_evidence(self):
        p = page(block('Go'), block(name='context'))
        e = evidence(p)
        self.assertEqual('same-card-context', route(p, e)['reason'])
        e['block:target'].update(observation('Go', 'de'))
        self.assertEqual('review', route(p, e)['action'])

    def test_conflict_only_blocks_own_card(self):
        p = page(block(), block(name='other', group='b', conflict=True))
        self.assertEqual('translate', route(p, evidence(p))['action'])
        p = page(block(), block(name='other', conflict=True))
        self.assertEqual('ocr-conflict', route(p, evidence(p))['reason'])

    def test_context_anchor_disagreement_and_insufficient_support(self):
        p = page(block('Go'), block(name='context'), block(name='second'))
        e = evidence(p); e['block:second'].update(observation(p['blocks'][2]['text'], 'fr'))
        self.assertEqual('review', route(p, e)['action'])
        p = page(block('Go'), block(name='context')); e = evidence(p)
        e['block:target'].update(observation('Go', 'en', .09))
        self.assertEqual('review', route(p, e)['action'])

    def test_mixed_or_missing_letter_segments_never_accepted(self):
        p = page(block('Hello bonjour')); e = evidence(p)
        e['block:target']['segments'] = [dict(start=0, end=6, raw='Hello ', wordCount=1, language='en'),
                                        dict(start=6, end=13, raw='bonjour', wordCount=1, language='fr')]
        self.assertEqual('review', route(p, e)['action'])
        e['block:target']['segments'].pop()
        self.assertEqual('uncovered-letters', route(p, e)['reason'])

    def test_codepoint_offsets_with_accents_cjk_and_emoji(self):
        raw = '🙂é漢字'
        o = observation(raw); o['segments'][0].update(start=1, raw=raw[1:])
        validate_observation(o, raw)
        for start, end in [(1, len(raw.encode())), (3, 2), (-1, 2), (True, 4)]:
            broken = copy.deepcopy(o); broken['segments'][0].update(start=start, end=end)
            with self.assertRaises(EvidenceError): validate_observation(broken, raw)
        broken = copy.deepcopy(o); broken['segments'] *= 2
        with self.assertRaises(EvidenceError): validate_observation(broken, raw)

    def test_invalid_scores_do_not_route(self):
        p = page(block())
        for score in [float('nan'), float('inf'), -1, 1.1, True, .4]:
            e = evidence(p); e['block:target']['candidates'][0]['score'] = score
            self.assertEqual('invalid-evidence', route(p, e)['reason'])
        e = evidence(p); e['block:target']['candidates'] *= 2
        self.assertEqual('invalid-evidence', route(p, e)['reason'])

    def test_literal_and_unsupported(self):
        p = page(block('€109.50')); e = evidence(p)
        e['block:target'].update(candidates=[], segments=[])
        self.assertEqual('keep-literal', route(p, e)['action'])
        p = page(block()); self.assertEqual('review', route(p, evidence(p, 'de'))['action'])

    def test_budget_and_duplicate_blocks(self):
        for blocks in [[block('x'*513)], [block()]*2,
                       [block(name='target' if i == 0 else str(i)) for i in range(33)],
                       [block('x'*512, name='target' if i == 0 else str(i)) for i in range(17)]]:
            with self.assertRaises(EvidenceError): queries(page(*blocks))

    def test_session_cleanup_cache_identity_and_no_reuse(self):
        class Fake:
            count = 0
            def unload_language_models(self): self.count += 1
        detector = Fake(); calls = []
        def convert(d, raw): calls.append(raw); return observation(raw)
        session = ObservationSession(detector, convert)
        p = page(block())
        with session:
            e = session.observe(p); e['block:target']['raw'] = 'bad'
            self.assertEqual(p['blocks'][0]['text'], session.observe(p)['block:target']['raw'])
        session.close()
        self.assertEqual((1, 1, {}, 1), (detector.count, len(calls), session.cache, session.unloaded))
        with self.assertRaises(EvidenceError): session.observe(p)
        def fail(d, raw): raise RuntimeError('fake inference failure')
        with self.assertRaises(RuntimeError):
            with ObservationSession(detector, fail) as failing: failing.observe(p)
        self.assertEqual((2, {}, None), (detector.count, failing.cache, failing.detector))


if __name__ == '__main__':
    unittest.main()
