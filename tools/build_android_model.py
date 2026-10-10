"""Cross-build the pinned CPU model runner for the emulator, without a placeholder APK."""
import json
import subprocess
import zipfile
from pathlib import Path
from setup_polish import download
from setup_emulator import install

ROOT = Path(__file__).resolve().parents[1]
SOURCE_COMMIT = '8ae386707bed05f02354dbfc6fa5ee0cf58e5f0f'
SOURCE_HASH = 'abe7e72f425a089fdeaf47e053dd7d46d1cfdbad6af69aa160c26ea9922d2dae'

if __name__=='__main__':
    base=ROOT/'.android/native-tools'
    install(('https://dl.google.com/android/repository/android-ndk-r28b-windows.zip','c7d82072807fcabbd6ee356476761d8729307185',base/'ndk'))
    install(('https://dl.google.com/android/repository/cmake-3.31.1-windows.zip','3a8f1f1b33e0ed2a639b6e709ab15806648979c9',base/'cmake'))
    archive=ROOT/'.model-downloads/llama-native-source.zip'
    download(f'https://api.github.com/repos/ggml-org/llama.cpp/zipball/{SOURCE_COMMIT}',archive,SOURCE_HASH)
    source_base=ROOT/'.android/native-source';source_base.mkdir(exist_ok=True)
    with zipfile.ZipFile(archive) as package:
        for name in package.namelist():
            if not (source_base/name).resolve().is_relative_to(source_base.resolve()):raise ValueError('源码档案路径越界')
        package.extractall(source_base)
    source=next(source_base.glob('*/CMakeLists.txt')).parent
    cmake=next((base/'cmake').rglob('cmake.exe'));ninja=next((base/'cmake').rglob('ninja.exe'))
    toolchain=next((base/'ndk').glob('*/build/cmake/android.toolchain.cmake'))
    build=ROOT/'.android/native-build-x86_64'
    subprocess.run([str(cmake),'-S',str(source),'-B',str(build),'-G','Ninja',f'-DCMAKE_MAKE_PROGRAM={ninja}',f'-DCMAKE_TOOLCHAIN_FILE={toolchain}','-DANDROID_ABI=x86_64','-DANDROID_PLATFORM=android-28','-DCMAKE_BUILD_TYPE=Release','-DGGML_NATIVE=OFF','-DGGML_OPENMP=OFF','-DGGML_LLAMAFILE=OFF','-DLLAMA_OPENSSL=OFF','-DLLAMA_CURL=OFF','-DLLAMA_BUILD_TESTS=OFF','-DLLAMA_BUILD_EXAMPLES=OFF','-DLLAMA_BUILD_SERVER=OFF'],check=True)
    subprocess.run([str(cmake),'--build',str(build),'--target','llama-completion','--parallel','2'],check=True)
    binary=build/'bin/llama-completion'
    assert binary.read_bytes()[:4]==b'\x7fELF','Android runner must be ELF, not a Windows executable'
    manifest={'sourceCommit':SOURCE_COMMIT,'sourceSha256':SOURCE_HASH,'abi':'x86_64','ndk':'r28b','targetApi':28,'binary':str(binary),'scope':'Emulator CPU runner; not a native keyboard APK or Redmi K60 benchmark'}
    (ROOT/'docs/android-native-build.json').write_text(json.dumps(manifest,indent=2))
    print('ANDROID_NATIVE_MODEL_RUNNER_BUILT',flush=True)
