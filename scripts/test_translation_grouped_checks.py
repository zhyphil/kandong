"""Authored before supplier execution. Semantic counterexamples, not provider expectations."""
import unittest
from unittest.mock import patch
import translation_grouped_checks as m


class ConditionChecks(unittest.TestCase):
    def cases(self):
        for language in ('en','fr'):
            for before in (True,False):
                for positive in (True,False):
                    if language=='en':
                        source=('Refunds are available' if positive else 'No refunds')+' '+('before' if before else 'after')+'\nconfirmation.'
                    else:
                        source=('Remboursement possible' if positive else 'Aucun remboursement')+' '+('avant' if before else 'après')+'\nconfirmation.'
                    chinese='确认'+('前' if before else '后')+('可以退款' if positive else '不能退款')+'。'
                    yield language,source,chinese

    def test_all_eight_complete_conditions(self):
        cases=list(self.cases());self.assertEqual(8,len(cases))
        for language,source,text in cases:
            with self.subTest(source=source):
                self.assertEqual('candidate-unverified',m.evaluate(source,text,language)['status'])
                self.assertEqual('candidate-unverified',m.evaluate(source.upper(),text,language)['status'])
                self.assertFalse(m.evaluate(source,text,language)['semanticVerified'])
                self.assertEqual(m.source_condition(source,language),m.source_condition(source.replace('\n',' ').rstrip('.'),language))

    def test_every_relation_polarity_event_omission_and_contradiction(self):
        for language,source,text in self.cases():
            reversed_relation=text.replace('前','后') if '前' in text else text.replace('后','前')
            reversed_polarity=text.replace('可以','不能') if '可以' in text else text.replace('不能','可以')
            for wrong in (reversed_relation,reversed_polarity,text.replace('确认','付款'),text.replace('确认',''),
                          text.replace('前','').replace('后',''),text.replace('可以','不可以不').replace('不能','不能不'),
                          text+'确认前可以退款。',text[:-1]+'，但不可退款。','仅'+text,'除非'+text,'confirmation.',
                          '退款。','确认后不可以不退款。',text+' extra',text+'\n',text+'100欧元'):
                with self.subTest(source=source,wrong=wrong):
                    self.assertEqual('withheld',m.evaluate(source,wrong,language)['status'])

    def test_source_suffixes_qualifiers_double_negation_and_incomplete(self):
        for language,source,text in self.cases():
            for wrong in (source+' only',source+' unless approved',source+' except on Monday',source+'\nExtra',
                          'only '+source,'not '+source,source.replace('confirmation.','payment.'),
                          source.split('\n')[0], 'confirmation.',source.replace(' ','  ',1),source+'\n'):
                self.assertEqual('withheld',m.evaluate(wrong,text,language)['status'])

    def test_only_specific_old_rejection_can_be_removed(self):
        source='No refunds before confirmation.';text='确认前不能退款。'
        old={'version':'old','status':'withheld','reasons':['refund-condition-unverified','currency-amount-unverified','other'], 'semanticVerified':False}
        with patch.object(m,'old_evaluate',return_value=old):
            result=m.evaluate(source,text,'en')
            self.assertEqual(['currency-amount-unverified','other'],result['reasons'])
            self.assertEqual('withheld',result['status'])
            self.assertIn('refund-condition-unverified',m.evaluate(source,'确认后不能退款。','en')['reasons'])


if __name__=='__main__': unittest.main()
