#!/usr/bin/env python3
"""为官方语料里**没有 view** 的模型生成视图包装，让它们也能进视图流水线。

做法（见 docs/PHASE-3-PLAN.md 的 T3）：
  1. 找出文件里的顶层包名；
  2. 为每个模型建一个独立工作区（一模型一工作区，避免跨文件重名互相干扰）；
  3. 在工作区里写一个包装文件：

         package CorpusViews {
             private import <包名>::*;
             private import StandardViewDefinitions::*;
             view 'corpus <包名>' : GeneralView { expose <包名>::**; }
         }

生成之后再对每个工作区跑一次产物生成，就是 T3 的扩面验收。

用法：
    python scripts/make-corpus-views.py --corpus <目录> --out build/corpus-views [--limit N]
    python scripts/make-corpus-views.py --corpus <目录> --out build/corpus-views --run
"""

from __future__ import annotations

import argparse
import json
import re
import shutil
import subprocess
import sys
from pathlib import Path

try:
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
except Exception:
    pass

PACKAGE_LINE = re.compile(
    r"^\s*(?:standard\s+library\s+|library\s+)?package\s+('([^']+)'|([A-Za-z_][\w]*))\s*\{",
    re.M,
)

WRAPPER = """package CorpusViews {{
    private import {package}::*;
    private import StandardViewDefinitions::*;

    view 'corpus {label}' : GeneralView {{
        expose {package}::**;
    }}
}}
"""


def top_package(text: str) -> tuple[str, str] | None:
    """返回 (用于引用的名字, 用于显示的名字)。

    包名带空格时必须保留单引号——`import 'Turbojet Stage Analysis'::*;`。
    第一版把引号剥掉了，生成的包装文件语法非法，被 T3 验收脚本抓了个正着。
    """
    match = PACKAGE_LINE.search(text)
    if not match:
        return None
    return match.group(1), (match.group(2) or match.group(3))


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--corpus", type=Path, required=True)
    parser.add_argument("--out", type=Path, required=True)
    parser.add_argument("--limit", type=int, default=0, help="只处理前 N 个文件（0 = 全部）")
    parser.add_argument("--run", action="store_true", help="生成后逐个跑一次产物生成")
    args = parser.parse_args()

    files = sorted(args.corpus.rglob("*.sysml")) + sorted(args.corpus.rglob("*.kerml"))
    if args.limit:
        files = files[: args.limit]
    if not files:
        raise SystemExit(f"no sources under {args.corpus}")

    if args.out.exists():
        shutil.rmtree(args.out, ignore_errors=True)
    args.out.mkdir(parents=True, exist_ok=True)

    generated = []
    skipped = []
    for path in files:
        text = path.read_text(encoding="utf-8", errors="replace")
        package = top_package(text)
        slug = re.sub(r"[^A-Za-z0-9._-]+", "-", path.stem)[:60]
        if package is None:
            skipped.append((str(path), "no top-level package"))
            continue
        reference, display = package
        workspace = args.out / slug
        # 复制**整个目录**而不是单个文件：语料里同目录的文件经常互相 import，
        # 只挑一个文件出来会立刻断链（实测 10 个里挂 5 个）。
        shutil.copytree(path.parent, workspace, dirs_exist_ok=True)
        (workspace / "corpus-views.sysml").write_text(
            WRAPPER.format(package=reference, label=display), encoding="utf-8"
        )
        generated.append((slug, display, path.name))

    print(f"[gen] 源文件 {len(files)}：生成 {len(generated)} 个工作区，跳过 {len(skipped)} 个")
    for item, reason in skipped[:5]:
        print(f"  [skip] {Path(item).name}: {reason}")
    if len(skipped) > 5:
        print(f"  ... 另有 {len(skipped) - 5} 个跳过")

    index = [{"slug": slug, "package": package, "file": name} for slug, package, name in generated]
    (args.out / "index.json").write_text(
        json.dumps(index, ensure_ascii=False, indent=2) + "\n", encoding="utf-8"
    )

    if not args.run:
        return 0

    ok = 0
    failed = []
    for slug, package, _ in generated:
        workspace = args.out / slug
        report = workspace / "check.json"
        result = subprocess.run(
            [
                "powershell", "-NoProfile", "-ExecutionPolicy", "Bypass",
                "-File", str(Path(__file__).with_name("run-view.ps1")),
                "-Workspace", str(workspace), "-Check", "-Report", str(report),
            ],
            capture_output=True,
            text=True,
        )
        if result.returncode != 0 or not report.is_file():
            failed.append((slug, "运行失败"))
            continue
        summary = json.loads(report.read_text(encoding="utf-8"))["summary"]
        if summary["errors"] > 0:
            first = next(
                (
                    message
                    for file in json.loads(report.read_text(encoding="utf-8"))["files"]
                    for message in file["messages"]
                    if message.startswith("ERROR")
                ),
                "",
            )
            failed.append((slug, f"errors={summary['errors']}｜{first[:90]}"))
        else:
            ok += 1

    print(f"[run] 通过 {ok} / {len(generated)}")
    for slug, reason in failed[:10]:
        print(f"  [fail] {slug}: {reason}")
    if len(failed) > 10:
        print(f"  ... 另有 {len(failed) - 10} 个失败")
    return 0 if not failed else 1


if __name__ == "__main__":
    raise SystemExit(main())
