import copy
import json
import unittest
from translation_probe_v2 import literal_issues as previous_issues,request_body as previous_request
from translation_candidate_v3 import cases,literal_issues,request_body,validate,MODEL

class NextCandidateTest(unittest.TestCase):
    def test_zero_padded_time_regression(self):
        source='Cancel before 09:15 on 6 November 2026.';translation='2026年11月6日9:15前取消'
        self.assertEqual(previous_issues(source,translation),['missing-source-number'])
        self.assertEqual(literal_issues(source,translation),[])
    def test_missing_year_changed_number_and_currency_still_rejected(self):
        for source,translation in [('21 November 2026','11月21日'),('7 kg','8公斤'),('$48.90','$48.09'),('$48.90','48.90元')]:
            self.assertTrue(literal_issues(source,translation))
    def test_decimal_ambiguity_not_silently_normalized(self):
        self.assertTrue(literal_issues('37,80 €','37.80 €'))
        self.assertTrue(literal_issues('1,000','1.000'))
    def test_source_pages_and_prompt_not_rewritten_for_model(self):
        for case in cases():
            r=request_body(case,True);old=previous_request(case,True)
            self.assertEqual(r['messages'],old['messages']);self.assertEqual(r['format'],old['format'])
            self.assertEqual(r['model'],MODEL);self.assertFalse(r['think'])
    def test_guard_preserves_raw_output_and_uncertainty(self):
        c=cases()[0];raw=json.dumps({'translations':[{'id':'b005','sourceText':'Book','text':None}]})
        self.assertEqual(validate(c,raw)[0]['status'],'uncertain')
    def test_protocol_source_echo_still_required(self):
        with self.assertRaises(ValueError):validate(cases()[0],json.dumps({'translations':[{'id':'b005','sourceText':'Reserve a room','text':'预订'}]}))
    def test_new_corpus_has_expected_frozen_counts(self):
        allcases=cases();self.assertEqual(len(allcases),20)
        self.assertEqual(sum(len(c['targetIds']) for c in allcases if c['sourceLanguage'].startswith(('en','fr'))),39)
        self.assertEqual(sum(len(c['targetIds']) for c in allcases if c['id'].startswith('p2')),13)
    def test_literal_check_not_semantic_approval(self):
        self.assertEqual(literal_issues('7 kg maximum','至少7公斤'),[])

if __name__=='__main__':unittest.main()
