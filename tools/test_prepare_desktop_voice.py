import hashlib
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

import prepare_desktop_voice as subject

TEMP_ROOT = (Path(__file__).resolve().parents[1] / '.android/desktop-integration-20261025').resolve()
assert TEMP_ROOT.is_relative_to(Path(__file__).resolve().parents[1])
TEMP_ROOT.mkdir(parents=True, exist_ok=True)


class ModelImportTest(unittest.TestCase):
    def test_hash_mismatch_leaves_source_untouched(self):
        with tempfile.TemporaryDirectory(dir=TEMP_ROOT) as folder:
            path = Path(folder) / 'model.onnx'
            path.write_bytes(b'bad')
            with self.assertRaises(ValueError):
                subject.verify(path, '0' * 64)
            self.assertEqual(path.read_bytes(), b'bad')

    def test_all_sources_validated_before_any_replacement(self):
        with tempfile.TemporaryDirectory(dir=TEMP_ROOT) as folder:
            root = Path(folder); source = root / 'source'; source.mkdir()
            (source / 'model.int8.onnx').write_bytes(b'model')
            (source / 'tokens.txt').write_bytes(b'bad tokens')
            target = root / 'models/desktop-sensevoice/model.int8.onnx'
            target.parent.mkdir(parents=True); target.write_bytes(b'previous')
            assets = {'desktop-sensevoice/model.int8.onnx': hashlib.sha256(b'model').hexdigest(),
                      'desktop-sensevoice/tokens.txt': hashlib.sha256(b'tokens').hexdigest()}
            with patch.object(subject, 'ROOT', root), patch.object(subject, 'ASSETS', assets):
                with self.assertRaises(ValueError): subject.prepare(source)
            self.assertEqual(target.read_bytes(), b'previous')

    def test_import_and_verify_without_source(self):
        with tempfile.TemporaryDirectory(dir=TEMP_ROOT) as folder:
            root = Path(folder); source = root / 'source'; source.mkdir()
            assets = {}
            for name, data in [('desktop-sensevoice/model.int8.onnx', b'model'), ('desktop-sensevoice/tokens.txt', b'tokens')]:
                (source / Path(name).name).write_bytes(data)
                assets[name] = hashlib.sha256(data).hexdigest()
            with patch.object(subject, 'ROOT', root), patch.object(subject, 'ASSETS', assets):
                subject.prepare(source); subject.prepare()
            self.assertFalse(list((root / 'models/desktop-sensevoice').glob('*.part')))


if __name__ == '__main__':
    unittest.main()
