"""Trust-boundary checks for the phone replay plan, without models or a device."""
import copy
import json
import unittest

from evaluate_desktop_voice import OUT, validate_rows


class ReplayPlanTest(unittest.TestCase):
    def setUp(self):
        self.rows = [{'id': 'fixed-0', 'samples': 3200, 'frames': [
            {'at': 1600, 'finals': [], 'draft': [0, 1600]},
            {'at': 3200, 'finals': [[0, 3200]], 'draft': None}]}]

    def test_valid(self):
        validate_rows(self.rows)

    def test_duplicate_identity_rejected(self):
        with self.assertRaisesRegex(ValueError, 'Duplicate'):
            validate_rows(self.rows + copy.deepcopy(self.rows))

    def test_future_or_negative_audio_rejected(self):
        for bounds in ([-1, 1600], [0, 1601], [1600, 1600]):
            with self.subTest(bounds=bounds), self.assertRaisesRegex(ValueError, 'boundary'):
                rows = copy.deepcopy(self.rows)
                rows[0]['frames'][0]['draft'] = bounds
                validate_rows(rows)

    def test_backwards_clock_rejected(self):
        self.rows[0]['frames'].insert(1, {'at': 800, 'finals': [], 'draft': None})
        with self.assertRaisesRegex(ValueError, 'clock'):
            validate_rows(self.rows)

    def test_missing_release_rejected(self):
        self.rows[0]['frames'].pop()
        with self.assertRaisesRegex(ValueError, 'release'):
            validate_rows(self.rows)


if __name__ == '__main__':
    if (OUT / 'plan.json').exists():
        validate_rows(json.loads((OUT / 'plan.json').read_text(encoding='utf-8'))['rows'])
    unittest.main()
