import unittest
from evaluate import cer


class EvaluationTests(unittest.TestCase):
    def test_cer(self):
        self.assertEqual(cer('你好。','你 好'),0)
        self.assertEqual(cer('你好','你'),.5)
        self.assertEqual(cer('你好','你号'),.5)
        self.assertEqual(cer('你好','你好呀'),.5)
        with self.assertRaises(ValueError):
            cer('。','你好')
