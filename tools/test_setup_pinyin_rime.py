import hashlib
from pathlib import Path
import tempfile
import unittest
from setup_pinyin_rime import ROOT, verify


class DependencyIntegrityTest(unittest.TestCase):
    def test_rejects_same_length_damage(self):
        data = b'known native dependency'
        spec = {'bytes': len(data), 'blob': hashlib.sha1(
            f'blob {len(data)}\0'.encode() + data).hexdigest(),
            'sha256': hashlib.sha256(data).hexdigest()}
        with tempfile.TemporaryDirectory(dir=ROOT / '.android') as directory:
            file = Path(directory) / 'core.data'
            self.assertFalse(verify(file, spec))
            file.write_bytes(data)
            self.assertTrue(verify(file, spec))
            file.write_bytes(b'x' + data[1:])
            self.assertFalse(verify(file, spec))


if __name__ == '__main__':
    unittest.main()
