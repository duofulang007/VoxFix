"""Build the arm64 translator runtime and drop it where Gradle expects jniLibs.

llama.cpp is compiled as one static library inside libvoxfix_translate.so with a
static libc++, because the Rime runtime already ships libc++_shared.so and an APK
cannot contain two different copies under the same name.
"""

import argparse
import json
import os
import shutil
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
TOOLS = ROOT / ".android/native-tools"
CMAKE = TOOLS / "cmake/bin/cmake.exe"
NINJA = TOOLS / "cmake/bin/ninja.exe"
TOOLCHAIN = TOOLS / "ndk/android-ndk-r28b/build/cmake/android.toolchain.cmake"
LLVM_BIN = TOOLS / "ndk/android-ndk-r28b/toolchains/llvm/prebuilt/windows-x86_64/bin"
BUILD = ROOT / ".android/translate-build"
OUTPUT = ROOT / ".android/translate/jni/arm64-v8a"
SOURCE = ROOT / "android/app/src/main/cpp/translate"
MANIFEST = ROOT / "tools/translate-dependencies.json"


def run(command, cwd=None):
    print(" ".join(str(item) for item in command), flush=True)
    subprocess.run([str(item) for item in command], cwd=cwd, check=True)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--clean", action="store_true")
    args = parser.parse_args()
    manifest = json.loads(MANIFEST.read_text(encoding="utf-8"))
    llama = ROOT / ".android/native-source" / ("llama.cpp-" + manifest["llama"]["commit"][:12])
    if not llama.exists():
        raise SystemExit("Run tools/setup_translate_native.py first: " + str(llama))
    if args.clean and BUILD.exists():
        shutil.rmtree(BUILD)
    BUILD.mkdir(parents=True, exist_ok=True)
    run([CMAKE, "-S", SOURCE, "-B", BUILD, "-G", "Ninja",
         "-DCMAKE_MAKE_PROGRAM=" + str(NINJA),
         "-DCMAKE_TOOLCHAIN_FILE=" + str(TOOLCHAIN),
         "-DLLAMA_SOURCE=" + str(llama),
         "-DANDROID_ABI=arm64-v8a",
         "-DANDROID_PLATFORM=android-30",
         "-DANDROID_STL=c++_static",
         "-DCMAKE_BUILD_TYPE=Release"])
    run([CMAKE, "--build", BUILD, "--target", "voxfix_translate", "--parallel", "4"])
    built = BUILD / "libvoxfix_translate.so"
    if not built.exists():
        raise SystemExit("MISSING_OUTPUT " + str(built))
    OUTPUT.mkdir(parents=True, exist_ok=True)
    for old in OUTPUT.glob("*.so"):
        old.unlink()
    shutil.copyfile(built, OUTPUT / built.name)
    run([LLVM_BIN / "llvm-strip.exe", "--strip-unneeded", str(OUTPUT / built.name)])
    size = (OUTPUT / built.name).stat().st_size
    print("TRANSLATE_JNI_BUILT %d bytes %s" % (size, OUTPUT / built.name))
    return 0


if __name__ == "__main__":
    sys.exit(main())
