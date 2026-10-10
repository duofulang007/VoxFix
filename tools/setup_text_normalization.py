"""Prepare pinned upstream WeText graphs and its existing Android OpenFst library."""
from pathlib import Path
import hashlib
import shutil
import subprocess
import zipfile
from setup_polish import download

ROOT = Path(__file__).resolve().parents[1]
LIB = ROOT/'.android/third-party/openfst-android'
MODELS = ROOT/'models/text'
GRAPHS = {
    'zh/itn/tagger.fst': ('zh_itn_tagger.fst', '1b57d35fa33030b50f2699d77b3c2cf2c3d5961c45d450322f5cf9aa729b810c'),
    'zh/itn/tagger_enable_0_to_9.fst': ('zh_itn_tagger_small.fst', 'a29dad112b1725e79f96f5a84e8b7f2ceb9dc3d88ffc785ebf994899701ccc9f'),
    'zh/itn/verbalizer.fst': ('zh_itn_verbalizer.fst', '440ac75aaeda3dd81f91b42abec4c08e84b7b0186462cc536a3a74d3ad1f13c4'),
    'remove_interjections.fst': ('remove_interjections.fst', '70f36aa8071968f72278fb8db9f17a060e8f8ac73a5e4161a009e33c688fedc1'),
}

def valid(path, expected):
    if not path.is_file(): return False
    with path.open('rb') as source: return hashlib.file_digest(source, 'sha256').hexdigest() == expected

if __name__ == '__main__':
    flat = LIB/'jni/arm64-v8a/libfst-flat.a'
    if not flat.is_file():
        archive = ROOT/'.model-downloads/wenet-openfst-android-1.0.2.aar'
        download('https://jitpack.io/com/github/pengzhendong/wenet-openfst-android/1.0.2/wenet-openfst-android-1.0.2.aar', archive, '05a816ad427e2d38a5b2f479aba3c3b2feef62b589ab24b97af9c8435252c879')
        with zipfile.ZipFile(archive) as package:
            for name in package.namelist():
                if not name.startswith(('jni/include/', 'jni/arm64-v8a/')): continue
                target = LIB/name
                if not target.resolve().is_relative_to(LIB.resolve()): raise ValueError('Upstream archive path escapes target')
                package.extract(name, LIB)
        shutil.copyfile(flat.with_name('libfst.a'), flat)
        # The old upstream archive embeds two archives; modern lld links them separately.
        ar = ROOT/'.android/native-tools/ndk/android-ndk-r28b/toolchains/llvm/prebuilt/windows-x86_64/bin/llvm-ar.exe'
        subprocess.run([str(ar), 'd', str(flat), 'libglog.a', 'libgflags_nothreads.a'], check=True)
    if not all(valid(MODELS/name, digest) for name, digest in GRAPHS.values()):
        wheel = ROOT/'.model-downloads/wetext-0.1.8-py3-none-any.whl'
        download('https://files.pythonhosted.org/packages/43/fe/ca7ccae2673b64ba7d63612e68b31963e3842dd77fb6aa632270d123d685/wetext-0.1.8-py3-none-any.whl', wheel, 'b2083e7f38ac38fcbecdf7aec6ac6f0e3d1a11b763facfa4daabc12138215454')
        MODELS.mkdir(parents=True, exist_ok=True)
        with zipfile.ZipFile(wheel) as package:
            for member, (name, digest) in GRAPHS.items():
                (MODELS/name).write_bytes(package.read('wetext/fsts/'+member))
                assert valid(MODELS/name, digest), 'WeText graph checksum mismatch'
    assert flat.is_file() and all(valid(MODELS/name, digest) for name, digest in GRAPHS.values())
    print('UPSTREAM_WETEXT_READY')
