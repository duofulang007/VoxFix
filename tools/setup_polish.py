"""Install the selected, checksum-pinned text model and Windows CPU runtime."""
import concurrent.futures
import hashlib
import zipfile
import urllib.request
import shutil
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
MODEL_URL = 'https://huggingface.co/unsloth/Qwen3.5-2B-GGUF/resolve/f6d5376be1edb4d416d56da11e5397a961aca8ae/Qwen3.5-2B-Q4_K_M.gguf'
MODEL_HASH = 'aaf42c8b7c3cab2bf3d69c355048d4a0ee9973d48f16c731c0520ee914699223'
RUNTIME_URL = 'https://github.com/ggml-org/llama.cpp/releases/download/b11529/llama-b11529-bin-win-cpu-x64.zip'
RUNTIME_HASH = '5c7ee910f4fe8aa29d51075d70dc061d579cd8119d2100632d704d2a50164ed4'
MODEL_SIZE = 1280835840


def download(url, target, expected, algorithm='sha256'):
    target.parent.mkdir(parents=True, exist_ok=True)
    def digest(path):
        with path.open('rb') as stream:
            return hashlib.file_digest(stream, algorithm).hexdigest()
    if target.exists() and digest(target) == expected:
        return
    part = target.with_suffix(target.suffix + '.part')
    offset = part.stat().st_size if part.exists() else 0
    if part.exists() and digest(part) == expected:
        part.replace(target)
        return
    if expected == MODEL_HASH and offset < MODEL_SIZE:
        ranges = []
        size = (MODEL_SIZE-offset+7)//8
        for index, start in enumerate(range(offset, MODEL_SIZE, size)):
            ranges.append((start, min(start+size, MODEL_SIZE)-1, part.with_suffix(f'.range{index}')))
        def fetch_range(item):
            start, end, path = item
            if path.exists() and path.stat().st_size == end-start+1:
                return
            used = path.stat().st_size if path.exists() else 0
            if used > end-start+1:
                raise ValueError('模型分片大小异常')
            request_start = start+used
            request = urllib.request.Request(url, headers={'Range': f'bytes={request_start}-{end}'})
            with urllib.request.urlopen(request, timeout=60) as response:
                if response.status != 206 or not response.headers.get('Content-Range', '').startswith(f'bytes {request_start}-{end}/'):
                    raise ValueError('模型下载未返回指定分片')
                with path.open('ab') as output:
                    while chunk := response.read(1024*1024):
                        output.write(chunk)
            if path.stat().st_size != end-start+1:
                raise ValueError('模型分片未下载完整，请重新运行继续下载')
            print(f'MODEL_RANGE_VERIFIED {start}-{end}', flush=True)
        with concurrent.futures.ThreadPoolExecutor(max_workers=8) as pool:
            list(pool.map(fetch_range, ranges))
        assembled = target.with_suffix(target.suffix + '.assembled.part')
        with assembled.open('wb') as output:
            if part.exists():
                with part.open('rb') as source:
                    shutil.copyfileobj(source, output)
            for _, _, path in ranges:
                with path.open('rb') as source:
                    shutil.copyfileobj(source, output)
        if digest(assembled) != expected:
            raise ValueError('模型校验失败，分片保留用于诊断')
        assembled.replace(target)
        part.unlink(missing_ok=True)
        for _, _, path in ranges:
            path.unlink()
        print('VERIFIED ' + target.name, flush=True)
        return
    request = urllib.request.Request(url, headers={'Range': f'bytes={offset}-'} if offset else {})
    open_url = urllib.request.build_opener(urllib.request.ProxyHandler({})).open if url.startswith('https://dl.google.com/') else urllib.request.urlopen
    with open_url(request, timeout=60) as response:
        append = offset and response.status == 206
        with part.open('ab' if append else 'wb') as output:
            while chunk := response.read(1024*1024):
                output.write(chunk)
    if digest(part) != expected:
        raise ValueError('下载校验失败：' + target.name)
    part.replace(target)
    print('VERIFIED ' + target.name, flush=True)


def runtime():
    archive = ROOT / '.model-downloads/llama-b11529-bin-win-cpu-x64.zip'
    download(RUNTIME_URL, archive, RUNTIME_HASH)
    target = ROOT / 'models/polish/runtime'
    target.mkdir(parents=True, exist_ok=True)
    with zipfile.ZipFile(archive) as package:
        for item in package.infolist():
            name = Path(item.filename).name
            if name == 'llama-server.exe' or name.endswith('.dll'):
                (target / name).write_bytes(package.read(item))
    assert (target / 'llama-server.exe').is_file()


if __name__ == '__main__':
    with concurrent.futures.ThreadPoolExecutor(max_workers=2) as pool:
        jobs = [pool.submit(runtime), pool.submit(download, MODEL_URL, ROOT / 'models/polish/Qwen3.5-2B-Q4_K_M.gguf', MODEL_HASH)]
        for job in jobs:
            job.result()
    print('OFFLINE_POLISH_INSTALLED', flush=True)
