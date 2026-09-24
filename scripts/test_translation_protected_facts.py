import unittest
from translation_protected_facts import local_date, protect_money, restore_money, prepare, display
from translation_critical_checks import evaluate


class LocalDateTest(unittest.TestCase):
    def test_complete_labels_preserve_relation_and_explicit_only(self):
        examples = [
            ('Valable le 29 février 2028.', 'fr', '在2028年2月29日有效'),
            ('Valid on April 8, 2029', 'en', '在2029年4月8日有效'),
            ('Only valid on 8 April 2029.', 'en', '仅在2029年4月8日有效'),
            ('Valid only on 8 April 2029', 'en', '仅在2029年4月8日有效'),
            ("Valable jusqu’au 1er août 2030 inclus.", 'fr', '有效期至2030年8月1日（含当日）'),
            ('Valid until 3 May 2031', 'en', '有效期至2031年5月3日'),
            ('Valid until May 3, 2031 inclusive.', 'en', '有效期至2031年5月3日（含当日）'),
        ]
        for source, language, expected in examples:
            with self.subTest(source=source):
                result = local_date(source, language)
                self.assertIsNotNone(result)
                self.assertEqual(expected, result['text'])
                self.assertEqual('local-date-rule', result['origin'])
                self.assertFalse(result['semanticVerified'])
                self.assertEqual('candidate-unverified', evaluate(source, result['text'], language)['status'])

    def test_invalid_ambiguous_or_extra_conditions_are_never_partially_rendered(self):
        for source, language in [
            ('Valable le 29 février 2027', 'fr'), ('Valable le 2er avril 2028', 'fr'),
            ('Valable le 31 avril 2028', 'fr'), ('Valid on 04/08/2029', 'en'),
            ('Not valid on 8 April 2029', 'en'), ('Valid on 8 April 2029 except children', 'en'),
            ('Valable le 8 avril 2029 sauf le matin.', 'fr'),
            ('Valid on 8 April 2029. No refund.', 'en'),
            ('Valid on 8 April 2029 or 9 April 2029', 'en'),
            ('Valable le April 8, 2029', 'fr'), ('Valid on 8 avril 2029', 'en'),
            ('May I help you?', 'en'), ('Valable le 8 avril 2029', 'zh-CN'),
            ('Valid until May 3, 2031 exclusive', 'en'),
        ]:
            with self.subTest(source=source): self.assertIsNone(local_date(source, language))


class ProtectedMoneyTest(unittest.TestCase):
    def test_protection_escapes_markup_and_keeps_exact_original_spelling(self):
        item = protect_money('Pay US$ 12.50 & <fees> €4.20', 'en')
        self.assertIsNotNone(item)
        self.assertEqual('<segment>Pay <keep id="m0">US$ 12.50</keep> &amp; &lt;fees&gt; <keep id="m1">€4.20</keep></segment>', item['xml'])
        result = restore_money(item, '<segment>支付 <keep id="m0">US$ 12.50</keep> 和费用 <keep id="m1">€4.20</keep></segment>')
        self.assertEqual('支付 US$ 12.50 和费用 €4.20', result['text'])
        self.assertEqual(result['text'], result['checkText'])

    def test_french_decimal_is_kept_for_display_and_compared_numerically(self):
        item = protect_money('Prix total : 37,80 €', 'fr')
        self.assertIsNotNone(item)
        result = restore_money(item, '<segment>总价：<keep id="m0">37,80 €</keep></segment>')
        self.assertEqual('总价：37,80 €', result['text'])
        self.assertEqual('总价：37.80 €', result['checkText'])
        self.assertEqual('candidate-unverified', evaluate(item['source'], result['checkText'], 'fr')['status'])

    def test_ambiguous_partial_signed_and_unbound_money_is_rejected(self):
        for source, lang in [('$1,200 total','en'), ('1 200 €','fr'), ('$1 200','en'),
                ('€1.234','fr'), ('-$12','en'), ('USD -12','en'), ('+£12','en'),
                ('Price in USD','en'), ('$12 USD','en'), ('CAD12 and 4 USD extra USD','en')]:
            with self.subTest(source=source), self.assertRaises(ValueError):protect_money(source, lang)
        self.assertIsNone(protect_money('Book a room for 2 nights', 'en'))

    def test_tampered_or_reordered_markup_is_rejected(self):
        item = protect_money('From $12 to $24', 'en')
        self.assertIsNotNone(item)
        for response in [
            '<segment><keep id="m0">$12</keep></segment>',
            '<segment><keep id="m0">$12</keep><keep id="m0">$24</keep></segment>',
            '<segment><keep id="m1">$24</keep><keep id="m0">$12</keep></segment>',
            '<segment><keep id="m0">$13</keep><keep id="m1">$24</keep></segment>',
            '<segment><keep id="m0"><b>$12</b></keep><keep id="m1">$24</keep></segment>',
            '<segment><keep id="m0" x="1">$12</keep><keep id="m1">$24</keep></segment>',
            '<segment><keep id="m0">$12</keep><keep id="m1">$24</keep><b>x</b></segment>',
            '<segment x="1"><keep id="m0">$12</keep><keep id="m1">$24</keep></segment>',
            '<!DOCTYPE x [<!ENTITY x "bad">]><segment>&x;</segment>',
            '<?xml version="1.0"?><segment/>', '<segment><!-- comment --></segment>',
            '<wrong/>', '<segment>', 'plain $12 to $24',
        ]:
            with self.subTest(response=response), self.assertRaises(ValueError):restore_money(item, response)

    def test_extra_currency_or_wrong_period_is_still_withheld(self):
        item = protect_money('$12 per month', 'en')
        self.assertIsNotNone(item)
        for xml in ['<segment>每月<keep id="m0">$12</keep>美元</segment>',
                    '<segment>每年<keep id="m0">$12</keep></segment>']:
            result = restore_money(item, xml)
            self.assertEqual('withheld', evaluate(item['source'], result['checkText'], 'en')['status'])

    def test_parser_size_is_bounded(self):
        item = protect_money('$12 total','en')
        self.assertIsNotNone(item)
        with self.assertRaises(ValueError):restore_money(item,'<segment>'+'a'*20000+'</segment>')


class SourceBindingTest(unittest.TestCase):
    def test_date_renders_locally_and_keeps_source_geometry(self):
        source = {'id': 'date', 'text': 'Valable le 8 avril 2029', 'bounds': [4,5,80,90], 'groupId': 'ticket'}
        result = display(source, 'screen-2', 'fr')
        self.assertEqual('local-date-rendered', result['state'])
        self.assertEqual('在2029年4月8日有效', result['displayText'])
        self.assertEqual(source, result['source'])
        self.assertIsNot(source, result['source'])
        self.assertIsNone(result['provenance']['provider'])
        self.assertFalse(result['check']['semanticVerified'])

    def test_extra_date_conditions_remain_original_without_cloud_request(self):
        text = 'Valid on 8 April 2029 except children'
        self.assertEqual('withheld', prepare(text, 'en')['route'])
        result = display({'id':'x', 'text':text}, 'p', 'en', '在2029年4月8日有效')
        self.assertEqual('original-with-warning', result['state'])
        self.assertEqual(text, result['displayText'])
        self.assertIsNone(result['translation'])

    def test_malformed_response_is_withheld_and_french_original_is_displayed(self):
        source = {'id':'price', 'text':'Prix : 14,20 EUR', 'bounds':[0,0,30,30]}
        result = display(source, 'p', 'fr', '<segment>价格：<keep id="m0">14,20 EUR</keep></segment>')
        self.assertEqual('candidate-unverified', result['state'])
        self.assertEqual('价格：14,20 EUR', result['displayText'])
        rejected = display(source, 'p', 'fr', '<segment>价格：14.20 欧元</segment>')
        self.assertEqual('original-with-warning', rejected['state'])
        self.assertIsNone(rejected['translation'])
        self.assertEqual(source['text'], rejected['displayText'])


if __name__ == '__main__': unittest.main()
