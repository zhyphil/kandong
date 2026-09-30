"""Post-observation grammar extension, not blind translation-quality acceptance."""
import unittest
from unittest.mock import patch
import translation_grouped_checks_v2 as m


class WrappedConditionTests(unittest.TestCase):
    def cases(self):
        for language,negative,positive,before,after in (
                ('en','No refunds','Refunds are available','before','after'),
                ('fr','Aucun remboursement','Remboursement possible','avant','après')):
            for relation,zh_relation,opposite in ((before,'前','后'),(after,'后','前')):
                for head,action,wrong_action in ((negative,'恕不退款','可办理退款'),(positive,'可办理退款','恕不退款')):
                    yield language,head+' '+relation+'\nconfirmation.',zh_relation,opposite,action,wrong_action

    def test_all_eight_relations_support_one_visual_wrap_without_rewriting_response(self):
        for language,source,relation,_,action,_ in self.cases():
            for prefix in ('','在'):
                for separator in ('',' ','\n','\r\n',' \n\t'):
                    value=prefix+'确认'+relation+separator+action+'。'
                    with self.subTest(source=source,value=value):
                        result=m.evaluate(source,value,language)
                        self.assertEqual('candidate-unverified',result['status'])
                        self.assertFalse(result['semanticVerified'])

    def test_every_direction_polarity_event_omission_and_extra_clause_still_rejected(self):
        for language,source,relation,opposite,action,wrong_action in self.cases():
            correct='确认'+relation+'\n'+action+'。'
            wrongs=['确认'+opposite+'\n'+action+'。','确认'+relation+'\n'+wrong_action+'。',
                    '付款'+relation+'\n'+action+'。','确认\n'+action+'。',action+'。',
                    correct+'但须批准。',correct+'确认前可退款。','仅'+correct,
                    correct.replace('\n','\n\n'),correct.replace('\n','除非'),
                    '确认'+relation+'\n不可以不退款。','确认'+relation+'\n可申请退款。',
                    '确认'+relation+'\n拒绝退款申请。','确认'+relation+'\n部分退款。',
                    '确认'+relation+'\n可办理退款或不能退款。',correct+'\n',correct+'100欧元']
            for wrong in wrongs:
                with self.subTest(source=source,wrong=wrong):
                    self.assertEqual('withheld',m.evaluate(source,wrong,language)['status'])
            for wrong_source in (source+' unless approved','only '+source,source.replace('confirmation.','payment.'),source.split('\n')[0]):
                self.assertEqual('withheld',m.evaluate(wrong_source,correct,language)['status'])

    def test_prior_simple_forms_remain_supported(self):
        for text in ('确认后不予退款。','在确认后\n不予退款。','确认之后\n不能退款。'):
            self.assertEqual('candidate-unverified',m.evaluate('Aucun remboursement après\nconfirmation.',text,'fr')['status'])

    def test_other_critical_rejections_survive_the_extended_grammar(self):
        old={'version':'old','status':'withheld','reasons':['refund-condition-unverified','currency-amount-unverified'], 'semanticVerified':False}
        with patch.object(m,'original_evaluate',return_value=old):
            checked=m.evaluate('No refunds after\nconfirmation.','确认后\n恕不退款。','en')
            self.assertEqual(['currency-amount-unverified'],checked['reasons'])
            self.assertEqual('withheld',checked['status'])


if __name__=='__main__': unittest.main()
