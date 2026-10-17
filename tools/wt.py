#!/usr/bin/env python3
"""并行 worktree 管理：建线 / 列线 / 收线。

用法：
    python tools/wt.py new <线名> [--native]
    python tools/wt.py list
    python tools/wt.py done <线名>

设计要点：
- 所有并行线集中放在 <主目录>-wt/ 下，不散落在 Projects 里。
- 新建的 worktree 是“裸”的：.gitignore 排除了 models/、.android/ 和
  preview 下的生成词库，缺了它们测试跑不起来。本脚本自动补生成数据。
- 默认不挂 4.4 GB 的 .android 工具链（分支只写代码 + 网页回归，
  构建统一在 main 上做）。确实要在这条线构建时加 --native。
"""
from __future__ import annotations

import shutil
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
WT_HOME = ROOT.parent / f"{ROOT.name}-wt"
MAIN = "main"

# .gitignore 里排除、但运行和测试需要的生成物（相对 ROOT）
GENERATED = [
    "preview/pinyin-data.json",
    "preview/pinyin-lite.json",
    "preview/pinyin-initials.json",
    "preview/phrase-packs.json",
    "键盘试用页.html",
]

# 只有 --native 才挂载的重目录
NATIVE_DIRS = ["models", ".android"]


def run(args: list[str], cwd: Path | None = None, quiet: bool = False) -> str:
    result = subprocess.run(
        args,
        cwd=str(cwd or ROOT),
        capture_output=True,
        text=True,
        encoding="utf-8",
        errors="replace",
    )
    if result.returncode != 0:
        raise RuntimeError(f"{' '.join(args)} 失败：\n{result.stderr.strip()}")
    if not quiet and result.stdout.strip():
        print(result.stdout.strip())
    return result.stdout


def branch_name(line: str) -> str:
    return f"feat/{line}"


def branch_exists(name: str) -> bool:
    out = run(["git", "branch", "--list", name], quiet=True)
    return bool(out.strip())


def merged_into_main(name: str) -> bool:
    out = run(["git", "branch", "--merged", MAIN], quiet=True)
    return name in {b.strip().lstrip("*+ ") for b in out.splitlines()}


def copy_generated(target: Path) -> list[str]:
    copied, missing = [], []
    for rel in GENERATED:
        src = ROOT / rel
        dst = target / rel
        if not src.exists():
            missing.append(rel)
            continue
        dst.parent.mkdir(parents=True, exist_ok=True)
        shutil.copy2(src, dst)
        copied.append(rel)
    return copied, missing


def link_dir(target: Path, name: str) -> str:
    """Windows 目录联接（junction），不需要管理员权限。"""
    src = ROOT / name
    dst = target / name
    if not src.is_dir():
        return f"跳过 {name}（主目录没有）"
    if dst.exists():
        return f"跳过 {name}（已存在）"
    result = subprocess.run(
        ["cmd", "/c", "mklink", "/J", str(dst), str(src)],
        capture_output=True,
        text=True,
        encoding="utf-8",
        errors="replace",
    )
    if result.returncode != 0:
        return f"{name} 联接失败：{result.stderr.strip() or result.stdout.strip()}"
    return f"{name} -> {src}（联接）"


def cmd_new(line: str, native: bool) -> int:
    path = WT_HOME / line
    branch = branch_name(line)

    if path.exists():
        print(f"目录已存在：{path}")
        print("要重建请先运行：python tools/wt.py done " + line)
        return 1

    WT_HOME.mkdir(parents=True, exist_ok=True)

    if branch_exists(branch):
        print(f"分支 {branch} 已存在，直接检出到新目录。")
        run(["git", "worktree", "add", str(path), branch])
    else:
        run(["git", "worktree", "add", "-b", branch, str(path)])

    print()
    copied, missing = copy_generated(path)
    if copied:
        print(f"补齐生成数据 {len(copied)} 个：")
        for rel in copied:
            print(f"  · {rel}")
    if missing:
        print("以下生成物主目录也没有，需要单独生成：")
        for rel in missing:
            print(f"  ！ {rel}")

    if native:
        print()
        for name in NATIVE_DIRS:
            print("  " + link_dir(path, name))
    else:
        print()
        print("未挂 .android / models（分支只跑网页回归）。要在这条线构建 APK：")
        print(f"  python tools/wt.py new {line} --native   # 需先 done 掉当前目录")

    print()
    print(f"就绪：{path}")
    print(f"分支：{branch}")
    print("验收范围：preview/*.test.mjs（网页回归）。装机和 APK 构建留在 main 上做。")
    return 0


def cmd_list() -> int:
    print(f"工作区根：{WT_HOME}")
    print()
    if not WT_HOME.exists():
        print("还没有任何并行线。")
        return 0

    entries = [p for p in WT_HOME.iterdir() if p.is_dir()]
    if not entries:
        print("还没有任何并行线。")
        return 0

    print(f"{'线名':<24}{'分支':<24}{'未提交':>6}{'提交':>5}  状态")
    print("-" * 74)
    for p in sorted(entries):
        branch = branch_name(p.name)
        exists = branch_exists(branch)
        dirty_n = 0
        ahead_n = 0
        if exists:
            try:
                st = run(["git", "status", "--porcelain"], cwd=p, quiet=True)
                dirty_n = len([l for l in st.splitlines() if l.strip()])
                ahead = run(["git", "rev-list", "--count", f"{MAIN}..{branch}"], quiet=True)
                ahead_n = int(ahead.strip() or 0)
            except Exception:
                pass

        if not exists:
            state = "无分支"
        elif merged_into_main(branch) and ahead_n == 0:
            state = "空线（尚无提交）"
        elif merged_into_main(branch):
            state = "已合并，可 done"
        elif dirty_n:
            state = "开发中"
        else:
            state = "有提交，待合并"
        print(f"{p.name:<24}{(branch if exists else '-'):<24}{dirty_n:>6}{ahead_n:>5}  {state}")
    return 0


def cmd_done(line: str) -> int:
    path = WT_HOME / line
    branch = branch_name(line)

    if not path.exists():
        print(f"目录不存在：{path}")
        return 1

    if not merged_into_main(branch):
        st = run(["git", "status", "--porcelain"], cwd=path, quiet=True)
        dirty = len([l for l in st.splitlines() if l.strip()])
        print(f"分支 {branch} 还没合并回 {MAIN}，不能收线。")
        if dirty:
            print(f"该线还有 {dirty} 个未提交改动。")
        print()
        print("先合并：git checkout main && git merge --no-ff " + branch)
        print("合并并跑通回归后，再执行本命令。")
        return 1

    st = run(["git", "status", "--porcelain"], cwd=path, quiet=True)
    dirty = len([l for l in st.splitlines() if l.strip()])
    if dirty:
        print(f"该线还有 {dirty} 个未提交改动，收线会丢掉它们。")
        print(f"先在该目录提交（{path}），或明确告诉我放弃这些改动。")
        return 1

    run(["git", "worktree", "remove", str(path)])
    if branch_exists(branch):
        run(["git", "branch", "-d", branch])
    print(f"已收线：{line}")

    if WT_HOME.exists() and not any(WT_HOME.iterdir()):
        WT_HOME.rmdir()
        print(f"并行的线都收完了，已删除空目录 {WT_HOME}")
    return 0


def main(argv: list[str]) -> int:
    if len(argv) < 2:
        print(__doc__)
        return 1

    action = argv[1]
    if action == "list":
        return cmd_list()
    if action in ("new", "done"):
        if len(argv) < 3:
            print(f"缺少线名。例：python tools/wt.py {action} clip-ref")
            return 1
        line = argv[2]
        if action == "new":
            return cmd_new(line, "--native" in argv[3:])
        return cmd_done(line)

    print(f"未知动作：{action}")
    print(__doc__)
    return 1


if __name__ == "__main__":
    sys.exit(main(sys.argv))
