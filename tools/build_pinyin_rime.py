"""Compile the small JNI adapter against the verified, unmodified Rime core."""
import json
from pathlib import Path
import shutil
import subprocess
from setup_pinyin_rime import ROOT, TARGET, verify

manifest = json.loads((ROOT / 'tools/pinyin-rime-dependencies.json').read_text())
for spec in manifest['files']:
    if not verify(TARGET / spec['target'], spec):
        raise ValueError('Prepare pinned Rime dependencies before building')
output = TARGET / 'jni/arm64-v8a'
output.mkdir(parents=True, exist_ok=True)
for name in ['libfeelime_rime.so', 'libc++_shared.so']:
    shutil.copyfile(TARGET / 'libs' / name, output / name)
toolchain = ROOT / '.android/native-tools/ndk/android-ndk-r28b/toolchains/llvm/prebuilt/windows-x86_64/bin'
subprocess.run([str(toolchain / 'clang++.exe'), '--target=aarch64-linux-android30',
    '-std=c++17', '-shared', '-fPIC', '-O2', '-fvisibility=hidden',
    str(ROOT / 'android/app/src/main/cpp/rime_jni.cpp'),
    '-I' + str(ROOT / 'android/app/src/main/cpp/rime'), '-L' + str(output),
    '-lfeelime_rime', '-Wl,-z,max-page-size=16384', '-o', str(output / 'libvoxfix_rime.so')], check=True)
for file in output.glob('*.so'):
    subprocess.run([str(toolchain / 'llvm-strip.exe'), '--strip-unneeded', str(file)], check=True)
print('RIME_JNI_BUILT ' + str(sum(file.stat().st_size for file in output.glob('*.so'))) + ' bytes')
