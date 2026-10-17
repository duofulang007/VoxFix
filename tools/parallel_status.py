#!/usr/bin/env python3
"""并行开发状态一览（只读，不改任何东西）。

用法：
    python tools/parallel_status.py

输出：每个 worktree 的目录、分支、未提交改动数、相对 main 的领先/落后提交数，
以及哪些分支已合并。用来核实"是否真的在并行"，不依赖任何口头说明。
"""
from __future__ import annotations

import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
MAIN = "main"


def git(*args: str, cwd: Path | None = None) -> str:
    result = subprocess.run(
        ["git", *args],
        cwd=str(cwd or ROOT),
        capture_output=True,
        text=True,
        encoding="utf-8",
        errors="replace",
    )
    if result.returncode != 0:
        raise RuntimeError(f"git {' '.join(args)} 失败：{result.stderr.strip()}")
    return result.stdout


def parse_worktrees() -> list[dict[str, str]]:
    entries: list[dict[str, str]] = []
    current: dict[str, str] = {}
    for line in git("worktree", "list", "--porcelain").splitlines():
        if not line.strip():
            if current:
                entries.append(current)
                current = {}
            continue
        key, _, value = line.partition(" ")
        current[key] = value
    if current:
        entries.append(current)
    return entries


def safe_git(*args: str, cwd: Path) -> str | None:
    try:
        return git(*args, cwd=cwd)
    except Exception:
        return None


def main() -> int:
    if git("rev-parse", "--is-inside-work-tree").strip() != "true":
        print("不在 git 仓库内")
        return 1

    worktrees = parse_worktrees()

    print(f"仓库：{ROOT}")
    print(f"基线：{MAIN}")
    print()

    if len(worktrees) <= 1:
        print("当前只有一个 worktree —— 没有并行的物理隔离。")
        print("（这不代表没在工作，只代表所有改动都在同一份目录里。）")
    else:
        print(f"当前有 {len(worktrees)} 个 worktree —— 存在并行隔离。")
    print()

    header = f"{'分支':<26}{'未提交':>5}{'领先':>5}{'落后':>5}  目录"
    print(header)
    print("-" * 46 + "  " + "-" * 30)

    for wt in worktrees:
        path = Path(wt.get("worktree", ""))
        branch_ref = wt.get("branch", "")
        branch = branch_ref.replace("refs/heads/", "") if branch_ref else "(detached)"

        status = safe_git("status", "--porcelain", cwd=path) if path.is_dir() else None
        dirty = str(len([l for l in status.splitlines() if l.strip()])) if status is not None else "?"

        ahead = behind = "-"
        if branch not in (MAIN, "(detached)"):
            counts = safe_git("rev-list", "--left-right", "--count", f"{MAIN}...{branch}", cwd=ROOT)
            parts = (counts or "").split()
            if len(parts) == 2:
                behind, ahead = parts[0], parts[1]

        print(f"{branch:<26}{dirty:>5}{ahead:>5}{behind:>5}  {path}")

    print()
    merged_out = safe_git("branch", "--merged", MAIN, cwd=ROOT) or ""
    merged = [b.strip().lstrip("*+ ") for b in merged_out.splitlines()]
    merged = [b for b in merged if b and b != MAIN]
    if merged:
        print("已合并回 main 的分支：")
        for b in merged:
            print(f"  · {b}")
    else:
        print("还没有分支合并回 main。")

    unmerged = safe_git("branch", "--no-merged", MAIN, cwd=ROOT) or ""
    pending = [b.strip().lstrip("*+ ") for b in unmerged.splitlines()]
    pending = [b for b in pending if b and b != MAIN]
    if pending:
        print()
        print("尚未合并的分支（还在开发中）：")
        for b in pending:
            print(f"  · {b}")

    return 0


if __name__ == "__main__":
    sys.exit(main())
