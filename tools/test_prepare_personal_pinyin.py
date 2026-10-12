import unittest
from prepare_personal_pinyin import read_words, seed


class PersonalSeedTest(unittest.TestCase):
    def test_duplicates_keep_contact_marker_and_ambiguous_words_are_held(self):
        records = read_words('###以下为正文内容###\n甲乙 0\n甲乙 1\n开心 0\n重甲 0\nABC 0\n')
        self.assertEqual(records['甲乙'], 1)
        rows, english, review = seed(records, {'开心'})
        self.assertEqual(english, ['ABC'])
        self.assertTrue(any(row['reason']=='contact_reading' for row in review))
        self.assertNotIn('开心', [word for word, _ in rows])
        self.assertTrue(any(row['word']=='重甲' and row['reason']=='ambiguous_reading' for row in review))
        self.assertRaises(ValueError, read_words, '###以下为正文内容###\n坏格式 999\n')


if __name__ == '__main__':
    unittest.main()
