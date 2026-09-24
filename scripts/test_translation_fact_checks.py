import unittest
from translation_fact_checks import issues,number_tokens,small_count
class FactCheckTest(unittest.TestCase):
    def test_equivalent_padded_time(self):self.assertEqual(issues('09:15','9:15','en'),[])
    def test_two_nights_same_count(self):self.assertEqual(issues('$240.00 for 2 nights','两晚$240.00','en'),[])
    def test_count_wrong_or_omitted(self):
        for out in ['三晚$240.00','$240.00']:self.assertIn('missing-source-number',issues('$240.00 for 2 nights',out,'en'))
    def test_french_decimal_euro_equivalence(self):self.assertEqual(issues('Prix total : 37,80 €','总价37.80欧元','fr'),[])
    def test_pounds_equivalence(self):self.assertEqual(issues('£18.75 per hour','每小时18.75英镑','en'),[])
    def test_unknown_dollar_currency_not_guessed(self):self.assertIn('missing-currency-identity',issues('$18.75','18.75美元','en'))
    def test_currency_substitution_rejected(self):self.assertIn('missing-currency-identity',issues('37,80 €','37.80美元','fr'))
    def test_changed_decimals_and_missing_year(self):
        self.assertIn('missing-source-number',issues('37,80 €','37.08欧元','fr'))
        self.assertIn('missing-source-number',issues('5 décembre 2026','12月5日','fr'))
    def test_ambiguous_grouping_not_normalized(self):
        self.assertTrue(issues('1,000 €','1.000欧元','fr'))
        self.assertTrue(issues('1,000','1000','en'))
    def test_multiplicity_preserved(self):self.assertTrue(issues('2 adults, 2 nights','2名成人，住宿','en'))
    def test_words_are_not_misread_as_numbers(self):self.assertFalse(number_tokens('一旦取消','zh-CN'))
    def test_only_small_counts_are_supported(self):
        for word,value in [('两',2),('十',10),('十八',18),('二十',20),('九十九',99)]:self.assertEqual(small_count(word),value)
        self.assertFalse(number_tokens('两百晚','zh-CN'))
    def test_numeric_pass_not_semantic_pass(self):self.assertEqual(issues('7 kg maximum','最少7公斤','en'),[])
    def test_unknown_result_not_filled(self):self.assertEqual(issues('€37',None,'fr'),[])
if __name__=='__main__':unittest.main()
