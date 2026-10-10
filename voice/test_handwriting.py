import unittest
import numpy as np
from handwriting import Handwriting, preprocess, validate_ink


class InkTests(unittest.TestCase):
    def test_empty_and_bounds(self):
        self.assertIsNone(preprocess([]))
        self.assertEqual(validate_ink({'w': 1000, 'h': 1000, 'strokes': []}), [])
        for invalid in [{'w': 0, 'h': 10, 'strokes': []}, {'w': 10, 'h': 10, 'strokes': [[[11, 0]]]}, {'w': 10, 'h': 10, 'strokes': [[[float('nan'), 0]]]}]:
            with self.assertRaises(ValueError):
                validate_ink(invalid)

    def test_render_and_real_model(self):
        strokes = [[[100, 500], [900, 500]]]
        data = preprocess(strokes)
        self.assertEqual(data.shape, (1, 96, 96, 1))
        self.assertEqual(data.dtype, np.uint8)
        model = Handwriting()
        model.load()
        self.assertTrue(model.ready, model.error)
        result = model.recognize({'w': 1000, 'h': 1000, 'strokes': strokes})
        self.assertIn('一', result)
        self.assertEqual(len(result), 9)


if __name__ == '__main__':
    unittest.main()
