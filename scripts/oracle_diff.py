#!/usr/bin/env python3
"""把我们的视图产物与官方渲染输出做差集对比。

输入：我们的产物 JSON + 官方 PlantUML 文本。
输出：官方画了而我们没有的节点（missing）、我们投影了而官方没画的节点（extra），
以及边数量的对比。

刻意不做"必须完全相等"的断言：官方渲染是它自己的投影选择（会递归进已暴露元素的内部、
会重复画同一元素、会省略无名元素），差异需要人来判断。这个脚本的职责是把差异摆出来。
"""

import argparse
import json
import re
import sys

NODE_LINE = re.compile(r'^\s*([A-Za-z]+)\s+(?:usage|def)?\s*"([^"]*)"\s+as\s+(\w+)')
EDGE_LINE = re.compile(r'^\s*(E\d+)\s+\S+\s+(E\d+)\b')


def normalize_label(label: str) -> str:
    text = label.strip()
    if text.startswith("^"):
        text = text[1:]
    if ":" in text:
        text = text.split(":", 1)[0]
    return text.strip()


def parse_puml(text: str):
    nodes = {}
    edges = []
    for line in text.splitlines():
        match = NODE_LINE.match(line)
        if match:
            _, label, alias = match.groups()
            name = normalize_label(label)
            if name:
                nodes[alias] = name
            continue
        match = EDGE_LINE.match(line)
        if match:
            edges.append(match.groups())
    return nodes, edges


def product_names(product: dict):
    names = []
    for node in product.get("nodes", []):
        name = node.get("name")
        if not name and node.get("ref"):
            name = node["ref"].split("::")[-1].strip("'")
        if name:
            names.append(name)
    return names


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--product", required=True)
    parser.add_argument("--puml", required=True)
    parser.add_argument("--label", default="")
    args = parser.parse_args()

    product = json.load(open(args.product, encoding="utf-8"))
    raw = open(args.puml, encoding="utf-8", errors="replace").read()
    start = raw.find("@startuml")
    end = raw.find("@enduml")
    puml_text = raw[start:end] if start >= 0 and end > start else raw

    oracle_nodes, oracle_edges = parse_puml(puml_text)
    oracle_names = sorted(set(oracle_nodes.values()))
    our_names = product_names(product)

    missing = sorted(set(oracle_names) - set(our_names))
    extra = sorted(set(our_names) - set(oracle_names))

    print(f"== {args.label or args.product}")
    print(f"   官方节点 {len(oracle_names)}（去重后） / 边 {len(oracle_edges)}")
    print(f"   我方节点 {len(our_names)} / 边 {len(product.get('relationships', []))}")
    if missing:
        print(f"   [missing] 官方画了、我方没有: {', '.join(missing)}")
    if extra:
        print(f"   [extra]   我方画了、官方没有: {', '.join(extra)}")
    if not missing and not extra:
        print("   节点集合一致")
    return 1 if missing else 0


if __name__ == "__main__":
    sys.exit(main())

