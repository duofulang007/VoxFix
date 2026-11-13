import hashlib
import shutil
import subprocess
import tarfile
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
COMMIT = "afdc340b14e7f92a294a95c374d13ada0d8c7a82"
SHA256 = "8c47f9a43e3bff815d4bf74ba041e8fb56271e9f90cb1543ee79f406b1c90c47"
SOURCE = ROOT / ".android" / "snow-src.tar.gz"
ANDROID = ROOT / "android" / "app" / "src" / "main"

def prepare_archive():
    if not SOURCE.exists():
        temp = SOURCE.with_suffix(".download")
        subprocess.run([
            "curl.exe", "-fLSs", "--retry", "2",
            f"https://codeload.github.com/mg-chao/snow-apps/tar.gz/{COMMIT}", "-o", str(temp)
        ], check=True)
        if hashlib.sha256(temp.read_bytes()).hexdigest() != SHA256:
            raise ValueError("Snow Shot source archive SHA-256 mismatch")
        temp.replace(SOURCE)
    if hashlib.sha256(SOURCE.read_bytes()).hexdigest() != SHA256:
        raise ValueError("Snow Shot source archive SHA-256 mismatch")

def build(folder, name, crates, dependencies=""):
    build_dir = ROOT / ".android" / folder / "native"
    build_dir.mkdir(parents=True, exist_ok=True)
    prefix = f"snow-apps-{COMMIT}/snow-crates/"
    with tarfile.open(SOURCE) as archive:
        for member in archive:
            if not member.isfile() or not member.name.startswith(prefix):
                continue
            relative = Path(member.name.removeprefix(prefix))
            if len(relative.parts) < 3 or relative.parts[0] != "crates" or relative.parts[1] not in crates:
                continue
            if ".." in relative.parts or relative.is_absolute():
                raise ValueError("Invalid source archive path")
            dest = build_dir / relative
            data = archive.extractfile(member).read()
            if not dest.exists() or dest.read_bytes() != data:
                dest.parent.mkdir(parents=True, exist_ok=True)
                dest.write_bytes(data)

    # VOXFIX 手机侧扩展：实时采集的位移连续性先验（覆盖上游 estimator.rs）。
    # 见 docs/长截屏三档滚动-实现原理与优化-2026-11-09.md §6.1；上游文件每次从
    # 固定提交重抽，此覆盖随后重新应用，保证可重复构建。
    overlay = ROOT / "tools" / "snow-stitch-mobile" / "estimator.rs"
    if overlay.exists():
        for crate in crates:
            crate_target = build_dir / "crates" / crate / "src" / "estimator.rs"
            if crate_target.exists():
                crate_target.write_bytes(overlay.read_bytes())

    manifest = build_dir / "Cargo.toml"
    members = ", ".join(f'"crates/{crate}"' for crate in crates)
    manifest.write_text(
        f'[workspace]\nmembers = [{members}]\n'
        'resolver = "3"\n[workspace.package]\nlicense = "Apache-2.0"\n' + dependencies, encoding="utf-8"
    )
    shutil.copyfile(ROOT / "tools" / f"snow-{name}.Cargo.lock", build_dir / "Cargo.lock")
    cargo = shutil.which("cargo")
    if cargo is None:
        raise RuntimeError("Install Rust and run rustup target add aarch64-linux-android")
    subprocess.run([
        cargo, "build", "--locked", "--release", "--manifest-path", str(manifest),
        "--target", "aarch64-linux-android", "-p", crates[1],
    ], check=True)

    clang = ROOT / ".android/native-tools/ndk/android-ndk-r28b/toolchains/llvm/prebuilt/windows-x86_64/bin/clang.exe"
    library = build_dir.parent / "jni" / "arm64-v8a" / f"libvoxfix_snow_{name}.so"
    library.parent.mkdir(parents=True, exist_ok=True)
    temporary = library.with_suffix(".tmp")
    subprocess.run([
        str(clang), "--target=aarch64-linux-android30", "-shared", "-O2", "-Wall", "-Wextra",
        "-Wl,--exclude-libs,ALL", "-Wl,-z,max-page-size=16384", "-Wl,--strip-debug",
        "-I", str(build_dir / "crates" / crates[1] / "include"), str(ANDROID / f"cpp/snow_{name}_jni.c"),
        str(build_dir / "target/aarch64-linux-android/release" / f"lib{crates[1].replace('-', '_')}.a"),
        "-ldl", "-llog", "-lm", "-ljnigraphics", "-o", str(temporary),
    ], check=True)
    temporary.replace(library)


if __name__ == "__main__":
    prepare_archive()
    build("snow-auto-region", "region", ("visual-region-detector", "snow-visual-region-detector-c"))
    build("snow-scroll-capture", "stitch", ("snow-stitch-images", "snow-stitch-images-c"), """
[workspace.dependencies]
anyhow = "1.0.102"
image = { version = "0.25.9", default-features = false, features = ["png"] }
png = "0.18.1"
rayon = "1.11.0"
serde = { version = "1.0.228", features = ["derive"] }
serde_json = "1.0.145"
tempfile = "3"
thiserror = "2.0.17"
""")
