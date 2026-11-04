"""Stage only the pinned winning OCR recognizer; never package evaluation models."""
import hashlib
import shutil
import urllib.request
from prepare_screen_ocr_eval import DEST, ROOT, download


def main():
    opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))
    target = DEST / 'PP-OCRv6_rec_small.onnx'
    digest = '6f327246b50388f3c176ae304bd95767ea6dc0c9ae92153ef8cbe210b3c14884'
    DEST.mkdir(parents=True, exist_ok=True)
    download(opener, 'https://www.modelscope.cn/models/RapidAI/RapidOCR/resolve/v3.10.0/onnx/PP-OCRv6/rec/PP-OCRv6_rec_small.onnx', target, digest)
    output = ROOT / '.android/screen-ocr/assets/screen-ocr/PP-OCRv6_rec_small.onnx'
    output.parent.mkdir(parents=True, exist_ok=True)
    if not output.exists() or hashlib.sha256(output.read_bytes()).hexdigest() != digest:
        shutil.copyfile(target, output)
    print('PP-OCRv6 small SHA256 verified')


if __name__ == '__main__':
    main()
