import copy
import unittest
from translation_critical_checks import evaluate, guarded_display


class CriticalTranslationTest(unittest.TestCase):
    def kept(self, source, translated, language='en'):
        result = evaluate(source, translated, language)
        self.assertEqual('candidate-unverified', result['status'], result)
        self.assertEqual([], result['reasons'])

    def withheld(self, source, translated, reason, language='en'):
        result = evaluate(source, translated, language)
        self.assertEqual('withheld', result['status'], result)
        self.assertIn(reason, result['reasons'])

    def test_single_day_must_not_become_deadline(self):
        self.withheld('Valable le 8 avril 2028', '有效期至2028年4月8日', 'date-relation-unverified', 'fr')
        self.withheld('Only valid on 17 August 2029.', '2029年8月17日起有效', 'date-relation-unverified')

    def test_same_day_equivalent_wording_is_retained(self):
        self.kept('Valable le 8 avril 2028', '2028年4月8日有效', 'fr')
        self.kept('Valid only on 17 August 2029.', '仅在2029年8月17日有效。')

    def test_month_day_and_year_have_roles_not_just_number_membership(self):
        self.withheld('Valid on 8 April 2028.', '2028年5月8日有效', 'date-value-unverified')
        self.withheld('Valable le 8 avril 2028', '2028年8月4日有效', 'date-value-unverified', 'fr')

    def test_until_and_inclusive_condition_preserved(self):
        self.kept("Valable jusqu'au 8 avril 2028 inclus.", '有效期至2028年4月8日（含）。', 'fr')
        self.withheld("Valable jusqu'au 8 avril 2028 inclus.", '有效期至2028年4月8日。', 'date-inclusive-unverified', 'fr')
        self.withheld("Valable jusqu'au 8 avril 2028 inclus.", '仅在2028年4月8日有效（含）。', 'date-relation-unverified', 'fr')

    def test_unknown_or_multiple_date_formats_require_review(self):
        self.withheld('Valid on 04/08/2028.', '2028年4月8日有效', 'date-format-unverified')
        self.withheld('Valid from 8 April 2028 to 10 April 2028.', '2028年4月8日至10日有效', 'date-format-unverified')

    def test_cancellation_date_and_clock_direction(self):
        self.kept('Free cancellation before 09:25 on 8 April 2028.', '2028年4月8日9:25前可免费取消。')
        self.withheld('Free cancellation before 09:25 on 8 April 2028.', '2028年4月8日9:25后可免费取消。', 'date-relation-unverified')
        self.withheld('Deadline: 08:06', '截止时间：06:08', 'clock-value-unverified')

    def test_currency_amount_pairs_cannot_swap(self):
        self.kept('€12.50 and £30.00', '12.50欧元和30英镑')
        self.withheld('€12.50 and £30.00', '30欧元和12.50英镑', 'currency-amount-unverified')

    def test_bare_dollar_is_not_a_guessed_currency_even_if_symbol_remains(self):
        self.kept('$12.50 per night', '每晚$12.50')
        self.withheld('$12.50 per night', '每晚12.50美元', 'currency-amount-unverified')
        self.withheld('$12.50 per night', '每晚$12.50美元', 'currency-amount-unverified')

    def test_explicit_currency_and_french_decimal_equivalence(self):
        self.kept('USD 12.50', '12.50美元')
        self.kept('CAD 12.50', '12.50加元')
        self.kept('Prix total : 12,50 €', '总价12.50欧元', 'fr')
        self.withheld('1,200 €', '1200欧元', 'currency-amount-unverified')

    def test_weights_and_direction_and_each_are_independent(self):
        self.kept('2 bags, 9 kg maximum each.', '2件行李，每件最多9公斤。')
        self.withheld('9 kg maximum.', '最多9克。', 'weight-value-unverified')
        self.withheld('9 kg maximum.', '至少9公斤。', 'quantity-limit-unverified')
        self.withheld('2 bags, 9 kg maximum each.', '2件行李，总共最多9公斤。', 'per-item-unverified')

    def test_refund_negation_and_condition_must_survive(self):
        self.kept('No refund after departure.', '出发后不予退款。')
        self.withheld('No refund after departure.', '出发后可退款。', 'refund-negation-unverified')
        self.withheld('No refund after departure.', '出发前不予退款。', 'refund-condition-unverified')
        self.withheld('No refund if cancelled less than 24 hours before pickup.', '取车前24小时不可退款。', 'refund-condition-unverified')

    def test_ticket_exchange_is_not_silently_redeem(self):
        self.kept('Non échangeable et non remboursable.', '不可换票，不可退款。', 'fr')
        self.withheld('Non échangeable et non remboursable.', '不可兑换，不可退款。', 'exchange-condition-unverified', 'fr')

    def test_bad_candidate_cannot_reach_display_and_source_is_not_mutated(self):
        row = {'id':'item','pageId':'page','text':'有效期至2028年4月8日',
               'source':{'id':'item','text':'Valable le 8 avril 2028','bounds':[1,2,50,60],'groupId':'group'},
               'status':'candidate'}
        before = copy.deepcopy(row); result = guarded_display(row, 'fr')
        self.assertEqual(before, row)
        self.assertEqual(row['source'], result['source'])
        self.assertEqual('original-with-warning', result['state'])
        self.assertEqual(row['source']['text'], result['displayText'])
        self.assertIsNone(result['translation'])
        self.assertTrue(result['message'])
        row['text'] = '2028年4月8日有效'
        result = guarded_display(row, 'fr')
        self.assertEqual('candidate-unverified', result['state'])
        self.assertEqual(row['text'], result['displayText'])

    def test_no_checks_is_not_a_semantic_pass(self):
        result = evaluate('Book', '书籍', 'en')
        self.assertEqual('candidate-unverified', result['status'])
        self.assertFalse(result['semanticVerified'])

    def test_missing_translation_and_unsupported_language_are_withheld(self):
        self.withheld('€12.50', None, 'translation-unavailable')
        self.withheld('€12.50', '', 'translation-unavailable')
        self.withheld('€12.50', '12.50欧元', 'source-language-unverified', 'unknown')

    def test_display_rejects_mismatched_local_identity(self):
        with self.assertRaises(ValueError):
            guarded_display({'id':'a','text':'文字','source':{'id':'b','text':'Text'}},'en')

    def test_month_words_without_a_calendar_date_are_not_flagged(self):
        self.kept('May I help you?', '需要帮助吗？')
        self.kept('March forward', '向前行进')

    def test_inclusive_negative_is_not_accepted_as_inclusive(self):
        self.withheld("Valable jusqu'au 8 avril 2028 inclus.", '有效期至2028年4月8日，不含当天。', 'date-inclusive-unverified', 'fr')

    def test_no_changes_or_refunds_protects_both_negations(self):
        self.kept('No changes or refunds after departure.', '出发后不可更改或退款。')
        self.withheld('No changes or refunds after departure.', '出发后可更改或退款。', 'refund-negation-unverified')
        self.withheld('No changes or refunds after departure.', '出发后可更改，但不可退款。', 'change-negation-unverified')

    def test_refundable_must_not_become_nonrefundable(self):
        self.withheld('Refundable.', '不可退款。', 'refund-negation-unverified')

    def test_price_period_and_tax_polarity_are_preserved(self):
        self.kept('$15 per month', '每月$15')
        self.withheld('$15 per month', '每年$15', 'price-period-unverified')
        self.kept('Taxes not included', '不含税')
        self.withheld('Taxes not included', '含税', 'tax-condition-unverified')
        self.withheld('Taxes included', '不含税', 'tax-condition-unverified')

    def test_invalid_clock_and_signed_quantity_are_not_silently_verified(self):
        self.withheld('Deadline 29:75', '截止29:75', 'clock-value-unverified')
        self.withheld('-€12.50', '12.50欧元', 'signed-number-unverified')

    def test_age_unit_and_per_item_contradiction(self):
        self.kept('Minimum age: 18 years', '最低年龄：18岁')
        self.withheld('Minimum age: 18 years', '最低年龄：18个月', 'age-unit-unverified')
        self.withheld('2 bags, 9 kg maximum each.', '2件行李，每件合计最多9公斤。', 'per-item-unverified')


if __name__ == '__main__': unittest.main()
