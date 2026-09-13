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
import urllib.parse
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

# 官方对照工具（fat jar）与本机 Java。可用环境变量覆盖，默认值与本机事实一致
# （见 docs/ENVIRONMENT.md）。
DEFAULT_ORACLE_JAR = Path(
    r"D:\03-Work\MBSE\Code2Model-Auto\tools\sysmlv2tool-dist\sysmlv2-tool-fat.jar"
)
DEFAULT_LIBDIR = Path(
    r"D:\03-Work\MBSE\Code2Model-Auto\tools\sysmlv2tool-dist\sysml.library"
)
DEFAULT_JAVA = Path(r"C:\Program Files\Android\Android Studio\jbr\bin\java.exe")


def official_error_counts(java: Path, jar: Path, libdir: Path, workspace: Path) -> dict[str, int]:
    """跑官方 `validate`，返回 {文件名: 错误数}。"""
    result = subprocess.run(
        [str(java), "-jar", str(jar), "--libdir", str(libdir), "validate", str(workspace)],
        capture_output=True,
        text=True,
        encoding="utf-8",
        errors="replace",
    )
    counts: dict[str, int] = {}
    current = None
    for line in result.stdout.splitlines() + result.stderr.splitlines():
        validating = re.match(r"\s*Validating:\s*(.+?)\s*$", line)
        if validating:
            current = Path(validating.group(1).replace("\\", "/")).name
            counts.setdefault(current, 0)
            continue
        failed = re.match(r"\s*\[x\]\s+FAILED:\s*(\d+)\s+error", line)
        if failed and current is not None:
            counts[current] = int(failed.group(1))
    return counts


def our_error_counts(report: dict) -> dict[str, int]:
    """读我们自己的 `-Check -Report` 报告，返回 {文件名: 错误数}。"""
    counts: dict[str, int] = {}
    for entry in report["files"]:
        name = Path(urllib.parse.unquote(entry["file"])).name
        counts[name] = sum(1 for message in entry["messages"] if message.startswith("ERROR"))
    return counts


def compare_with_official(args, slugs: list[str]) -> tuple[int, list[tuple[str, str]]]:
    """逐工作区比对"我方诊断"与"官方 validate"的逐文件错误数。"""
    agree = 0
    disagree: list[tuple[str, str]] = []
    for slug in slugs:
        workspace = args.out / slug
        report = args.out / f"check-{slug}.json"
        subprocess.run(
            [
                "powershell", "-NoProfile", "-ExecutionPolicy", "Bypass",
                "-File", str(Path(__file__).with_name("run-view.ps1")),
                "-Workspace", str(workspace), "-Check", "-Report", str(report),
            ],
            capture_output=True,
            text=True,
        )
        if not report.is_file():
            disagree.append((slug, "我方报告缺失"))
            continue
        ours = our_error_counts(json.loads(report.read_text(encoding="utf-8")))
        theirs = official_error_counts(args.java, args.oracle_jar, args.libdir, workspace)
        if ours == theirs:
            agree += 1
        else:
            diff = {
                name: (ours.get(name), theirs.get(name))
                for name in set(ours) | set(theirs)
                if ours.get(name) != theirs.get(name)
            }
            disagree.append((slug, f"逐文件不一致: {diff}"))
    return agree, disagree


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
    parser.add_argument(
        "--run-views",
        action="store_true",
        help="生成后逐个导出工作区里所有视图的产物（产物级扩面验收）",
    )
    parser.add_argument(
        "--compare-failures",
        action="store_true",
        help="对没有通过的工作区，逐个与官方 `validate` 比对逐文件错误数",
    )
    parser.add_argument(
        "--compare-only",
        action="store_true",
        help="不重新生成，只读上一次留下的 failures.json 与官方 `validate` 比对",
    )
    parser.add_argument("--oracle-jar", type=Path, default=DEFAULT_ORACLE_JAR)
    parser.add_argument("--libdir", type=Path, default=DEFAULT_LIBDIR)
    parser.add_argument("--java", type=Path, default=DEFAULT_JAVA)
    args = parser.parse_args()

    if args.compare_only:
        failures_file = args.out / "failures.json"
        if not failures_file.is_file():
            raise SystemExit(f"没有失败清单可读（需要先跑 --run-views）: {failures_file}")
        if not args.oracle_jar.is_file():
            raise SystemExit(f"官方对照工具不存在: {args.oracle_jar}")
        slugs = [item["slug"] for item in json.loads(failures_file.read_text(encoding="utf-8"))]
        print(f"[compare] 待比对 {len(slugs)} 个工作区（官方 validate + 我方 -Check）")
        agree, disagree = compare_with_official(args, slugs)
        print(f"[compare] 与官方诊断一致 {agree} / {len(slugs)}")
        for slug, reason in disagree[:10]:
            print(f"  [diff] {slug}: {reason}")
        return 0 if not disagree else 1

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

    if not args.run and not args.run_views:
        return 0

    failed = []
    if args.run:
        ok = 0
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

    if args.run_views:
        # 产物级扩面验收：一次加载导出该工作区所有视图，再检查产物里没有 model-errors。
        # 这是 T3 真正想证明的东西——"能解析"不等于"能出产物"。
        ok_views = 0
        failed_views = []
        for slug, package, _ in generated:
            workspace = args.out / slug
            products = args.out / "products" / slug
            result = subprocess.run(
                [
                    "powershell", "-NoProfile", "-ExecutionPolicy", "Bypass",
                    "-File", str(Path(__file__).with_name("run-view.ps1")),
                    "-Workspace", str(workspace), "-AllViews", str(products),
                ],
                capture_output=True,
                text=True,
            )
            files = sorted(products.glob("*.json")) if products.is_dir() else []
            if result.returncode != 0 or not files:
                failed_views.append((slug, "产物生成失败"))
                continue
            broken = []
            for product in files:
                reasons = json.loads(product.read_text(encoding="utf-8"))["completeness"]["reasons"]
                if any(reason["code"] == "model-errors" for reason in reasons):
                    broken.append(product.name)
            if broken:
                failed_views.append((slug, f"产物带模型错误: {broken[0]}"))
                continue
            ok_views += 1

        print(f"[run-views] 通过 {ok_views} / {len(generated)}")
        for slug, reason in failed_views[:10]:
            print(f"  [fail] {slug}: {reason}")
        if len(failed_views) > 10:
            print(f"  ... 另有 {len(failed_views) - 10} 个失败")

        (args.out / "failures.json").write_text(
            json.dumps(
                [{"slug": slug, "reason": reason} for slug, reason in failed_views],
                ensure_ascii=False,
                indent=2,
            )
            + "\n",
            encoding="utf-8",
        )

        if args.compare_failures and failed_views:
            if not args.oracle_jar.is_file():
                raise SystemExit(f"官方对照工具不存在: {args.oracle_jar}")
            agree, disagree = compare_with_official(
                args, [slug for slug, _ in failed_views]
            )
            print(f"[compare] 与官方诊断一致 {agree} / {len(failed_views)}")
            for slug, reason in disagree[:10]:
                print(f"  [diff] {slug}: {reason}")
            if disagree:
                failed.extend(disagree)

        failed.extend(failed_views)

    return 0 if not failed else 1


if __name__ == "__main__":
    raise SystemExit(main())
