import types
import unittest
from unittest.mock import Mock

import numpy as np

from server import Engine, RATE, audio_samples, clean_text, join_text, refine_text, validate_hotwords


class VoiceTests(unittest.TestCase):
    def test_hotword_boundary(self):
        self.assertEqual(validate_hotwords(['语音识别','语音识别']), ['语音识别'])
        for value in ['词', ['Obsidian'], [''], ['字'*31], ['词']*101]:
            with self.assertRaises(ValueError):
                validate_hotwords(value)
    def test_pcm_trust_boundary(self):
        self.assertEqual(len(audio_samples(np.zeros(3200, dtype='<f4').tobytes())), 3200)
        for invalid in [b'', b'x', np.array([np.nan], dtype='<f4').tobytes(), np.array([2], dtype='<f4').tobytes(), bytes(RATE * 4 + 4)]:
            with self.assertRaises(ValueError):
                audio_samples(invalid)

    def test_text_spacing(self):
        self.assertEqual(clean_text('你 好  world'), '你好  world')
        self.assertEqual(join_text('Hello', 'world'), 'Hello world')
        self.assertEqual(join_text('你好。', '世界'), '你好。世界')
        self.assertEqual(refine_text('请帮我测试语音', '请帮我测试语音。'), '请帮我测试语音。')
        self.assertEqual(refine_text('打开 Obsidian', '打开我手机'), '打开 Obsidian')
        self.assertEqual(refine_text('今天去跑步', '这是其他内容'), '今天去跑步')
        self.assertEqual(refine_text('明天不要跑十公里', '明天要跑十公里'), '明天不要跑十公里')
        self.assertEqual(refine_text('明天跑10公里', '明天跑15公里'), '明天跑10公里')
        self.assertEqual(refine_text('明天跑十公里', '明天跑十五公里'), '明天跑十公里')

    def engine(self, endpoint=False):
        engine = Engine()
        stream = Mock()
        engine.online = Mock()
        engine.online.create_stream.return_value = stream
        engine.online.is_ready.return_value = False
        engine.online.is_endpoint.return_value = endpoint
        engine.online.get_result.return_value = '你 好'
        engine.final = Mock()
        engine.final.create_stream.return_value = types.SimpleNamespace(result=types.SimpleNamespace(text='你好'), accept_waveform=Mock())
        engine.punct = Mock()
        engine.punct.add_punctuation.side_effect = lambda text: text + '。'
        engine.ready = True
        return engine

    def test_partial_and_final(self):
        engine = self.engine()
        key = engine.start()['session']
        partial = engine.chunk(key, np.full(3200, .1, dtype='<f4').tobytes())
        self.assertEqual(partial, {'text': '你好', 'final': False})
        final = engine.finish(key)
        self.assertEqual(final['text'], '你好。')
        self.assertNotIn(key, engine.sessions)
        with self.assertRaises(ValueError):
            engine.chunk(key, np.zeros(3200, dtype='<f4').tobytes())

    def test_silence_does_not_run_second_pass(self):
        engine = self.engine()
        key = engine.start()['session']
        engine.chunk(key, np.zeros(3200, dtype='<f4').tobytes())
        engine.finish(key)
        engine.final.decode_stream.assert_not_called()

    def test_not_ready_and_session_limit(self):
        with self.assertRaises(ValueError):
            Engine().start()
        engine = self.engine()
        for _ in range(3):
            engine.start()
        with self.assertRaises(ValueError):
            engine.start()


if __name__ == '__main__':
    unittest.main()
