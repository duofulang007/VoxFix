import hashlib
import io
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch
import setup_polish


class DownloadTests(unittest.TestCase):
    def test_partial_ranges_and_checksum(self):
        payload = bytes(range(256))*4
        expected = hashlib.sha256(payload).hexdigest()
        class Response(io.BytesIO):
            status = 206
        def response(request, timeout):
            start, end = map(int, request.headers['Range'].split('=')[1].split('-'))
            result = Response(payload[start:end+1]);result.headers={'Content-Range':f'bytes {start}-{end}/{len(payload)}'}
            return result
        with tempfile.TemporaryDirectory() as folder, patch.object(setup_polish, 'MODEL_HASH', expected), patch.object(setup_polish, 'MODEL_SIZE', len(payload)), patch.object(setup_polish.urllib.request, 'urlopen', side_effect=response):
            target = Path(folder)/'model.gguf';target.with_suffix('.gguf.part').write_bytes(payload[:200])
            target.with_suffix('.gguf.range0').write_bytes(payload[200:220])
            setup_polish.download('https://example.com/model',target,expected)
            self.assertEqual(target.read_bytes(),payload)
            self.assertFalse(list(Path(folder).glob('*.range*')))
            self.assertFalse(target.with_suffix('.gguf.part').exists())
            setup_polish.download('https://example.com/model',target,expected)
