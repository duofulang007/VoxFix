import unittest
from unittest.mock import Mock
from polish import Polisher, validate_text, segments, guarded_result


class PolishTests(unittest.TestCase):
    def test_boundaries(self):
        for text in [None, '', ' ', 42, '字'*12001]:
            with self.assertRaises(ValueError):
                validate_text(text)

    def test_long_segments_preserve_every_character(self):
        for text in ['你好。明天见！\n我知道了', 'a'*1501, '\n\n', '前文。'+'字'*1200+'。后文！']:
            parts = segments(text)
            self.assertEqual(''.join(parts), text)
            self.assertTrue(all(len(part)<=600 for part in parts))

    def test_protected_content(self):
        self.assertEqual(guarded_result('明天不跑10公里', '明天不跑10公里。'), ('明天不跑10公里。', True))
        for candidate in ['明天跑10公里。', '明天不跑15公里。', '明天不跑十公里。', '<think>思考</think>明天不跑10公里。']:
            self.assertEqual(guarded_result('明天不跑10公里', candidate), ('明天不跑10公里', False))
        self.assertEqual(guarded_result('打开 Obsidian', '打开其他软件'), ('打开 Obsidian', False))
        self.assertEqual(guarded_result('我就是就是晚点联系你', '我就是就是晚点联系你。'), ('我就是晚点联系你。', True))
        self.assertEqual(guarded_result('他说“就是就是”。', '他说“就是就是”。'), ('他说“就是就是”。', False))

    def test_chunk_join_cannot_split_protected_tokens(self):
        engine = Polisher()
        engine.load = Mock()
        engine.request = Mock(side_effect=lambda path,data: {'choices':[{'finish_reason':'stop','message':{'content':data['messages'][-1]['content']}}]})
        for text in ['甲'*597+'13800138000', '甲'*597+'Obsidian', '甲'*595+'“这段引用必须原样保留。”']:
            result = engine.polish(text)
            self.assertEqual(result['text'], text)


if __name__ == '__main__':
    unittest.main()
