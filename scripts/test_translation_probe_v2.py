import json
import unittest
from translation_probe import load_cases
from translation_probe_v2 import request_body,validate,literal_issues

class TranslationRevisionTest(unittest.TestCase):
    def test_targets_repeat_only_exact_source(self):
        c=load_cases()[0];r=request_body(c,True);d=json.loads(r['messages'][1]['content'])
        self.assertEqual(d['targets'],[dict(id='b005',sourceText='Book')]);self.assertEqual(len(d['blocks']),5)
    def test_wrong_source_echo_rejected(self):
        with self.assertRaises(ValueError):validate(load_cases()[0],json.dumps(dict(translations=[dict(id='b005',sourceText='Select your dates',text='选择日期')])))
    def test_currency_and_year_omission_flagged_not_repaired(self):
        self.assertEqual(literal_issues('$120.00 per night','每晚120.00元'),['missing-currency-symbol'])
        self.assertEqual(literal_issues('10 October 2026','10月10日'),['missing-source-number'])
        self.assertEqual(literal_issues('2 bags, 8 kg each','2件行李，每件8公斤'),[])
    def test_literal_guard_does_not_claim_to_detect_wrong_meaning(self):
        self.assertEqual(literal_issues('$210.00 for 2 nights','$210.00每2晚，不可退款'),[])
    def test_guard_retains_raw_failed_output_and_binding(self):
        c=load_cases()[2];rows=[dict(id=b['id'],sourceText=b['text'],text=b['text']) for b in c['blocks'] if b['id'] in c['targetIds']]
        rows[0]['text']='每晚120.00元';out=validate(c,json.dumps(dict(translations=rows)))
        self.assertEqual(out[0]['status'],'rejected-literal');self.assertEqual(out[0]['text'],'每晚120.00元')

if __name__=='__main__':unittest.main()
