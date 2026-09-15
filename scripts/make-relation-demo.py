#!/usr/bin/env python3
"""生成"关系与追溯"Demo 页：G6 + 全模型索引 + 追溯矩阵。

Demo 页的能力（对应阶段 3 已有的语义能力，现在换 G6 展示）：

  1. 元素关系      点选元素 → 一跳邻居（出/入、关系类型、作者写的 or 推导）
  2. 局部关系图    常驻右下角的小图，以当前选中元素为中心、N 跳内，与主视图同步
  3. 追溯关系      只留 satisfy / verify / derive，按类型着色
  4. 影响范围      反向可达（排除 containment），按步数分层
  5. 追溯矩阵      需求行 × 满足方/验证方/派生，含覆盖率与缺口；单元格里的元素可点

交互约定（按使用反馈定的）：

  · 选中元素**不动视口**；要居中得显式动作（双击节点、点"居中"按钮、树上再点一次）
  · 点画布空白处或按 Esc 取消选中
  · 模式列表放在左侧栏下方（上方横栏留给标题与筛选），元素树可折叠
  · 边默认走直角折线（G6 polyline + router: orth），可切直线/圆滑

页面暴露 `window.__PROTO`（与 P1 原型同一套），因此可以直接用
`scripts/measure-proto.py` 脚本化操作并截图——文档里的实机图就是这么来的。

数据由 Java 侧产出（只跑小样例，别拿语料跑批）：

    run-view.ps1 -Workspace <ws> -Index  <data>/<slug>.index.json
    run-view.ps1 -Workspace <ws> -Matrix -Out <data>/<slug>.matrix.json

用法：

    python scripts/make-relation-demo.py --workspace samples/requirements \
        --out build/demo/requirements.html \
        --lib build/vendor/node_modules/@antv/g6/dist/g6.min.js
"""

from __future__ import annotations

import argparse
import json
import re
import subprocess
import sys
import time
from pathlib import Path

try:
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
except Exception:
    pass

REPO = Path(__file__).resolve().parent.parent
RUN_VIEW = REPO / "scripts" / "run-view.ps1"


def slug(text: str) -> str:
    return re.sub(r"[^A-Za-z0-9]+", "-", text).strip("-")[:60] or "workspace"


def run_java(args: list[str]) -> str:
    result = subprocess.run(
        ["powershell", "-NoProfile", "-ExecutionPolicy", "Bypass", "-File", str(RUN_VIEW)] + args,
        # 必须显式指定 UTF-8：默认按本地代码页（GBK）解码，Java 输出的中文会炸掉读取线程
        capture_output=True, text=True, encoding="utf-8", errors="replace",
    )
    if result.returncode != 0:
        raise SystemExit("run-view 失败：%s\n%s\n%s"
                         % (" ".join(args), result.stdout[-600:], result.stderr[-600:]))
    return result.stdout


def ensure_data(workspace: str, data_dir: Path, reuse: bool) -> dict:
    """取索引与追溯矩阵。缺文件时才跑 Java（单次约 10–20 秒，都是小样例）。"""
    data_dir.mkdir(parents=True, exist_ok=True)
    name = slug(Path(workspace).name)
    index_path = data_dir / ("%s.index.json" % name)
    matrix_path = data_dir / ("%s.matrix.json" % name)

    if not (reuse and index_path.is_file()):
        run_java(["-Workspace", workspace, "-Index", str(index_path)])
    if not (reuse and matrix_path.is_file()):
        run_java(["-Workspace", workspace, "-Matrix", "-Out", str(matrix_path)])

    index = json.loads(index_path.read_text(encoding="utf-8"))
    matrix = json.loads(matrix_path.read_text(encoding="utf-8")) if matrix_path.is_file() else None
    return {"index": index, "matrix": matrix, "workspace": workspace,
            "indexPath": index_path.name, "matrixPath": matrix_path.name}


PAGE = """<!doctype html>
<html lang="zh-CN">
<head>
<meta charset="utf-8">
<title>__TITLE__</title>
<script>__LIB__</script>
<style>
  :root { --line:#e3e3e3; --accent:#1a73e8; --muted:#777; --bg:#fafbfc; }
  * { box-sizing:border-box; }
  body { margin:0; font:13px/1.5 "Segoe UI",sans-serif; height:100vh; display:grid;
         grid-template-columns:300px 1fr 340px; grid-template-rows:44px 40px 1fr 26px; }
  header { grid-column:1/4; display:flex; align-items:center; gap:12px; padding:0 14px;
           border-bottom:1px solid var(--line); }
  header .title { font-weight:600; }
  header .ws { color:var(--muted); font-size:12px; }
  header .status { margin-left:auto; color:var(--muted); font-size:12px; }
  .toolbar { grid-column:1/4; display:flex; align-items:center; gap:14px; padding:0 14px;
             border-bottom:1px solid var(--line); background:var(--bg); font-size:12px; color:#333; }
  .toolbar label { display:flex; align-items:center; gap:4px; }
  .toolbar select { font:12px inherit; padding:2px 4px; }
  .toolbar button { font:12px inherit; padding:3px 10px; border:1px solid var(--line);
                    background:#fff; border-radius:4px; cursor:pointer; }
  .toolbar button:hover { border-color:var(--accent); color:var(--accent); }
  #sidebar { grid-column:1; grid-row:3/4; display:flex; flex-direction:column;
             border-right:1px solid var(--line); min-height:0; }
  #tree { flex:1; overflow:auto; padding:6px 4px; }
  #tree .row { display:flex; align-items:center; gap:2px; border-radius:3px; padding:1px 4px; }
  #tree .row:hover { background:#eef2f8; }
  #tree .row.active { background:#e8f0fe; font-weight:600; }
  #tree .caret { width:14px; text-align:center; color:#888; cursor:pointer; user-select:none;
                 flex:0 0 14px; }
  #tree .caret.leaf { visibility:hidden; }
  #tree .name { cursor:pointer; white-space:nowrap; overflow:hidden; text-overflow:ellipsis; }
  #tree .badge { color:#c0392b; font-size:10px; margin-left:4px; flex:0 0 auto; }
  #modes { border-top:1px solid var(--line); padding:8px; display:flex; flex-direction:column; gap:4px; }
  #modes .head { color:var(--muted); font-size:11px; margin-bottom:2px; }
  #modes button { text-align:left; padding:5px 10px; border:1px solid var(--line); background:#fff;
                  border-radius:4px; cursor:pointer; font:12px inherit; }
  #modes button.active { border-color:var(--accent); color:var(--accent); font-weight:600;
                         background:#f3f8ff; }
  #stage { grid-column:2; grid-row:3/4; position:relative; overflow:hidden; min-height:0; }
  #canvas { position:absolute; inset:0; }
  #mini { position:absolute; right:12px; bottom:12px; width:400px; height:250px; background:#fff;
          border:1px solid #cdd6e0; border-radius:6px; box-shadow:0 2px 10px rgba(0,0,0,.08);
          overflow:hidden; }
  #mini .bar { height:24px; display:flex; align-items:center; gap:6px; padding:0 8px;
               border-bottom:1px solid var(--line); background:var(--bg); font-size:11px; color:#444; }
  #mini .bar .muted { color:var(--muted); }
  #miniCanvas { position:absolute; top:24px; left:0; right:0; bottom:0; }
  #panel { grid-column:3; grid-row:3/4; border-left:1px solid var(--line); overflow:auto;
           padding:10px 12px; min-height:0; }
  #panel h3 { margin:2px 0 8px; font-size:13px; }
  #panel h4 { margin:12px 0 4px; font-size:12px; color:#444; }
  #panel table { border-collapse:collapse; width:100%; font-size:12px; }
  #panel td, #panel th { border-bottom:1px solid #f0f0f0; padding:3px 4px; text-align:left;
                         vertical-align:top; }
  #panel th { color:var(--muted); font-weight:500; }
  #panel .muted { color:var(--muted); }
  #matrix { grid-column:2/4; grid-row:3/4; overflow:auto; padding:14px 18px; display:none; }
  #matrix table { border-collapse:collapse; width:100%; font-size:12px; }
  #matrix th, #matrix td { border:1px solid #e6e6e6; padding:5px 8px; text-align:left;
                           vertical-align:top; }
  #matrix th { background:#f6f8fa; font-weight:600; }
  #matrix tr.gap { background:#fff6f6; }
  #matrix .tiles { display:flex; gap:10px; margin-bottom:12px; }
  #matrix .tile { border:1px solid var(--line); border-radius:6px; padding:6px 14px; background:#fff; }
  #matrix .tile b { display:block; font-size:18px; }
  #matrix .tile.gap b { color:#c0392b; }
  a.elink { color:var(--accent); text-decoration:none; border-bottom:1px dotted #9bc0f0;
            cursor:pointer; }
  a.elink:hover { text-decoration:underline; }
  .elink.plain { color:inherit; border-bottom:none; }
  footer { grid-column:1/4; border-top:1px solid var(--line); color:var(--muted); font-size:12px;
           display:flex; gap:16px; align-items:center; padding:0 14px; }
  #metrics { display:none; }
</style>
</head>
<body>
<header>
  <span class="title">关系与追溯 Demo</span>
  <span class="ws" id="ws"></span>
  <span class="status" id="status"></span>
</header>
<div class="toolbar">
  <label>局部图深度 <select id="depth">
    <option value="1">1 跳</option><option value="2" selected>2 跳</option><option value="3">3 跳</option>
  </select></label>
  <label>线型 <select id="edgeKind">
    <option value="orth" selected>直角折线</option>
    <option value="straight">直线</option>
    <option value="curve">圆滑曲线</option>
  </select></label>
  <label><input type="checkbox" id="hideAttributes" checked> 隐藏属性</label>
  <label><input type="checkbox" id="hideContainment" checked> 隐藏包含边</label>
  <label><input type="checkbox" id="dim" checked> 淡化无关元素</label>
  <span style="margin-left:auto"></span>
  <button id="centerBtn">居中选中元素</button>
  <button id="clearBtn">取消选中</button>
</div>
<nav id="sidebar">
  <div id="tree"></div>
  <div id="modes"><div class="head">视图模式</div></div>
</nav>
<div id="stage">
  <div id="canvas"></div>
  <div id="mini">
    <div class="bar"><b>局部关系图</b><span class="muted" id="miniInfo"></span>
      <span style="margin-left:auto" class="muted">点小图里的元素可切换中心</span></div>
    <div id="miniCanvas"></div>
  </div>
</div>
<aside id="panel"></aside>
<div id="matrix"></div>
<footer><span id="engine"></span><span id="counts"></span><span id="timings"></span></footer>
<pre id="metrics"></pre>
<script id="demo-data" type="application/json">__DATA__</script>
<script>
__JS__
</script>
</body>
</html>
"""


JS = r"""
// ============================================================================
// Demo 页：索引 + 追溯矩阵 → G6 图。
// 语义计算（邻居 / 子图 / 影响范围）照 Java 侧 ModelQuery 的算法实现，
// 页内结果与 `run-view.ps1 --query` 的输出可以对上（见 docs/RELATION-DEMO.md）。
// ============================================================================
const DEMO = JSON.parse(document.getElementById('demo-data').textContent);
const INDEX = DEMO.index;
const MATRIX = DEMO.matrix;
const problems = [];
const timings = {};
const history = [];

/** 影响范围跟随的边类型：与 ModelQuery.INFLUENCE_KINDS 一致（排除 containment）。 */
const INFLUENCE_KINDS = new Set(['typing', 'specialization', 'subsetting', 'redefinition',
  'satisfy', 'verify', 'derive', 'allocate', 'flow', 'connection', 'perform', 'succession']);
/** 追溯关系：需求与满足方/验证方/派生之间的关系。 */
const TRACE_KINDS = new Set(['satisfy', 'verify', 'derive']);

const byRef = {};
INDEX.elements.forEach(function (element) { byRef[element.ref] = element; });
const outgoing = {};
const incoming = {};
INDEX.relations.forEach(function (relation) {
  (outgoing[relation.source] = outgoing[relation.source] || []).push(relation);
  (incoming[relation.target] = incoming[relation.target] || []).push(relation);
});

const state = {
  mode: 'relation',       // relation | trace | impact | matrix
  center: null,
  depth: 2,
  hideAttributes: true,
  hideContainment: true,
  dim: true,
  edgeKind: 'orth',       // orth | straight | curve
  collapsed: {},          // 树里被折叠的 ref
};

let graph = null;
let miniGraph = null;
let view = null;
let busy = Promise.resolve();

window.addEventListener('error', function (event) {
  problems.push(String(event.message));
  paintStatus();
});

// --- 语义计算（照 Java ModelQuery 的算法） ---------------------------------------

function neighbors(ref) {
  const result = [];
  INDEX.relations.forEach(function (relation) {
    let isOutgoing;
    let other;
    if (ref === relation.source) { isOutgoing = true; other = relation.target; }
    else if (ref === relation.target) { isOutgoing = false; other = relation.source; }
    else { return; }
    const element = byRef[other] || {};
    result.push({ ref: other, name: element.name, metaclass: element.metaclass,
                  kind: relation.kind, outgoing: isOutgoing, authored: relation.authored });
  });
  result.sort(function (a, b) {
    if (a.kind !== b.kind) { return a.kind < b.kind ? -1 : 1; }
    return a.ref < b.ref ? -1 : (a.ref > b.ref ? 1 : 0);
  });
  return result;
}

/** 影响范围：从 ref 出发沿反向边做可达，只跟随 INFLUENCE_KINDS。 */
function impact(ref, maxDepth) {
  const visited = new Set([ref]);
  let frontier = [ref];
  const result = [];
  for (let depth = 1; depth <= maxDepth && frontier.length; depth++) {
    const next = [];
    frontier.forEach(function (current) {
      (incoming[current] || []).forEach(function (relation) {
        if (!INFLUENCE_KINDS.has(relation.kind)) { return; }
        if (visited.has(relation.source)) { return; }
        visited.add(relation.source);
        const element = byRef[relation.source] || {};
        result.push({ ref: relation.source, name: element.name, metaclass: element.metaclass,
                      depth: depth, viaKind: relation.kind });
        next.push(relation.source);
      });
    });
    frontier = next;
  }
  result.sort(function (a, b) {
    if (a.depth !== b.depth) { return a.depth - b.depth; }
    return a.ref < b.ref ? -1 : (a.ref > b.ref ? 1 : 0);
  });
  return result;
}

/** 局部关系图：以 ref 为中心、N 跳内的节点，以及两端都在集合里的边。 */
function subgraph(ref, depth) {
  if (!ref) { return { nodes: [], edges: [] }; }
  const adjacency = {};
  INDEX.relations.forEach(function (relation) {
    (adjacency[relation.source] = adjacency[relation.source] || new Set()).add(relation.target);
    (adjacency[relation.target] = adjacency[relation.target] || new Set()).add(relation.source);
  });
  const nodes = new Set([ref]);
  let frontier = [ref];
  for (let level = 0; level < depth && frontier.length; level++) {
    const next = [];
    frontier.forEach(function (current) {
      (adjacency[current] ? Array.from(adjacency[current]) : []).forEach(function (neighbour) {
        if (!nodes.has(neighbour)) { nodes.add(neighbour); next.push(neighbour); }
      });
    });
    frontier = next;
  }
  const edges = INDEX.relations.filter(function (relation) {
    return nodes.has(relation.source) && nodes.has(relation.target);
  });
  return { nodes: Array.from(nodes), edges: edges };
}

// --- 文案与样式 ------------------------------------------------------------------

function shortName(ref) {
  const index = ref.lastIndexOf('::');
  return index < 0 ? ref : ref.slice(index + 2).replace(/'/g, '');
}

function literalText(value) {
  return value === null || value === undefined ? '' : String(value);
}

/** URI 只留文件名：整条 file:/// 会把面板撑爆。 */
function shortUri(uri) {
  const text = literalText(uri).replace(/\/+$/, '');
  const index = text.lastIndexOf('/');
  return index < 0 ? text : text.slice(index + 1);
}

function categoryOf(element) {
  const metaclass = element ? (element.metaclass || '') : '';
  if (!metaclass) { return 'other'; }
  if (metaclass === 'RequirementUsage') { return 'requirement'; }
  if (metaclass.indexOf('RequirementDefinition') === 0) { return 'requirementDef'; }
  if (metaclass.indexOf('Verification') === 0) { return 'verification'; }
  if (metaclass.indexOf('Part') === 0) { return 'part'; }
  if (metaclass === 'Package') { return 'package'; }
  if (metaclass.indexOf('Attribute') >= 0) { return 'attribute'; }
  if (metaclass.indexOf('View') === 0) { return 'view'; }
  return 'other';
}

const NODE_STYLE = {
  requirement:    { stroke: '#1a73e8', lineWidth: 2,   fill: '#ffffff' },
  requirementDef: { stroke: '#5b8def', lineWidth: 1.4, fill: '#ffffff' },
  verification:   { stroke: '#8e44ad', lineWidth: 1.6, fill: '#fdf9ff' },
  part:           { stroke: '#444444', lineWidth: 1.4, fill: '#ffffff' },
  package:        { stroke: '#9aa5b1', lineWidth: 1,   fill: '#f7f9fc' },
  attribute:      { stroke: '#c3c9d0', lineWidth: 1,   fill: '#ffffff' },
  view:           { stroke: '#9aa5b1', lineWidth: 1,   fill: '#fbfbfd' },
  other:          { stroke: '#8d99a6', lineWidth: 1,   fill: '#ffffff' },
};

const EDGE_STYLE = {
  containment:  { stroke: '#d3d8de', lineWidth: 1,   label: '' },
  typing:       { stroke: '#9ab3c9', lineWidth: 1.1, label: 'typing' },
  subsetting:   { stroke: '#b9a6c9', lineWidth: 1.1, label: 'subsets' },
  redefinition: { stroke: '#c9a6a6', lineWidth: 1.1, label: 'redefines' },
  satisfy:      { stroke: '#2e8b57', lineWidth: 2,   label: 'satisfy' },
  verify:       { stroke: '#d68910', lineWidth: 2,   label: 'verify' },
  derive:       { stroke: '#7d3c98', lineWidth: 2,   label: 'derive' },
};

function edgeStyleOf(kind) {
  return EDGE_STYLE[kind] || { stroke: '#8d99a6', lineWidth: 1.1, label: kind };
}

function edgeTypeOf() {
  if (state.edgeKind === 'straight') { return 'line'; }
  if (state.edgeKind === 'curve') { return 'cubic-horizontal'; }
  return 'polyline';
}

function edgeExtraStyle() {
  // 直角折线：G6 的 polyline 支持 router；其余线型不需要
  return state.edgeKind === 'orth' ? { router: { type: 'orth', padding: 12 } } : {};
}

function labelOf(element) {
  if (!element) { return '?'; }
  if (element.reqId) { return '[' + element.reqId + '] ' + (element.name || shortName(element.ref)); }
  return element.name || shortName(element.ref);
}

/** 面板里的元素一律做成链接（可点即去），不是纯文本。 */
function linkHtml(ref, text) {
  const label = text === undefined ? labelOf(byRef[ref] || { ref: ref }) : text;
  return '<a class="elink" data-ref="' + escapeHtml(ref) + '">' + escapeHtml(label) + '</a>';
}

function gapRefs() {
  const gaps = {};
  if (!MATRIX) { return gaps; }
  MATRIX.rows.forEach(function (row) {
    if (!row.satisfiedBy.length && !row.verifiedBy.length) { gaps[row.ref] = true; }
  });
  return gaps;
}

function escapeHtml(text) {
  return String(text === null || text === undefined ? '' : text)
    .replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;');
}

/** 元素树：按限定名的命名空间层级搭，而不是按包含边——树要看的是名字空间。 */
function buildTreeModel() {
  const nodes = {};
  INDEX.elements.forEach(function (element) {
    if (element.origin === 'library') { return; }
    nodes[element.ref] = { ref: element.ref, element: element, children: [] };
  });
  const roots = [];
  Object.keys(nodes).forEach(function (ref) {
    const cut = ref.lastIndexOf('::');
    const parentRef = cut < 0 ? null : ref.slice(0, cut);
    if (parentRef && nodes[parentRef]) { nodes[parentRef].children.push(nodes[ref]); }
    else { roots.push(nodes[ref]); }
  });
  function sortLevel(list) {
    list.sort(function (a, b) {
      if (a.children.length !== b.children.length) { return b.children.length - a.children.length; }
      return a.ref < b.ref ? -1 : 1;
    });
    list.forEach(function (item) { sortLevel(item.children); });
  }
  sortLevel(roots);
  return roots;
}

const TREE = buildTreeModel();

// --- 元素树（可折叠） --------------------------------------------------------------

function renderTree() {
  const box = document.getElementById('tree');
  box.innerHTML = '';
  const gaps = gapRefs();

  function row(node, depth) {
    const hasChildren = node.children.length > 0;
    const collapsed = !!state.collapsed[node.ref];
    const holder = document.createElement('div');
    holder.className = 'row' + (node.ref === state.center ? ' active' : '');
    holder.style.paddingLeft = (4 + depth * 12) + 'px';

    const caret = document.createElement('span');
    caret.className = 'caret' + (hasChildren ? '' : ' leaf');
    caret.textContent = collapsed ? '\u25B8' : '\u25BE';
    if (hasChildren) {
      caret.onclick = function (event) {
        event.stopPropagation();
        state.collapsed[node.ref] = !collapsed;
        renderTree();
      };
    }
    holder.appendChild(caret);

    const name = document.createElement('span');
    name.className = 'name';
    name.textContent = labelOf(node.element);
    name.title = node.ref + '（再点一次居中）';
    name.onclick = function () { run('treePick', node.ref); };
    holder.appendChild(name);

    if (gaps[node.ref]) {
      const badge = document.createElement('span');
      badge.className = 'badge';
      badge.textContent = '缺口';
      holder.appendChild(badge);
    }
    box.appendChild(holder);
    if (hasChildren && !collapsed) {
      node.children.forEach(function (child) { row(child, depth + 1); });
    }
  }

  TREE.forEach(function (root) { row(root, 0); });
}

function setTreeCollapsed(flag) {
  state.collapsed = {};
  if (flag) {
    (function walk(list) {
      list.forEach(function (node) {
        if (node.children.length) { state.collapsed[node.ref] = true; walk(node.children); }
      });
    })(TREE);
  }
}

// --- 主图 ------------------------------------------------------------------------

function visibleElements() {
  return INDEX.elements.filter(function (element) {
    if (element.origin === 'library') { return false; }
    if (state.hideAttributes && categoryOf(element) === 'attribute') { return false; }
    return true;
  });
}

/** 当前模式下的节点与边集合（不含"选中"带来的淡化，那一步走增量更新）。 */
function graphData() {
  const gaps = gapRefs();
  const center = state.center;
  let allowedKinds = state.mode === 'trace' ? TRACE_KINDS : null;
  let keep = null;

  if (state.mode === 'trace') {
    keep = new Set();
    INDEX.relations.forEach(function (relation) {
      if (TRACE_KINDS.has(relation.kind)) { keep.add(relation.source); keep.add(relation.target); }
    });
  } else if (state.mode === 'impact' && center) {
    keep = new Set([center]);
    impact(center, state.depth).forEach(function (hit) { keep.add(hit.ref); });
  }

  let elements = visibleElements();
  if (keep) { elements = elements.filter(function (element) { return keep.has(element.ref); }); }
  const refs = new Set(elements.map(function (element) { return element.ref; }));

  const relations = INDEX.relations.filter(function (relation) {
    if (state.hideContainment && relation.kind === 'containment') { return false; }
    if (allowedKinds && !allowedKinds.has(relation.kind)) { return false; }
    return refs.has(relation.source) && refs.has(relation.target);
  });

  const nodes = elements.map(function (element) {
    const style = Object.assign({}, NODE_STYLE[categoryOf(element)]);
    return {
      id: element.ref,
      style: Object.assign(style, { label: labelOf(element) + (gaps[element.ref] ? '  (!)' : '') }),
    };
  });
  const edges = relations.map(function (relation, index) {
    const style = edgeStyleOf(relation.kind);
    return {
      id: 'e' + index,
      source: relation.source,
      target: relation.target,
      type: edgeTypeOf(),
      style: Object.assign({
        label: style.label, stroke: style.stroke, lineWidth: style.lineWidth,
      }, edgeExtraStyle()),
      data: { kind: relation.kind },
    };
  });
  return { nodes: nodes, edges: edges };
}

function createMainGraph(data, firstRender) {
  const container = document.getElementById('canvas');
  if (graph) { try { graph.destroy(); } catch (error) { /* 忽略 */ } graph = null; }
  container.innerHTML = '';
  graph = new G6.Graph({
    container: container,
    autoFit: firstRender ? 'view' : false,
    padding: 20,
    animation: data.nodes.length <= 60,
    data: data,
    node: {
      type: 'rect',
      style: {
        size: function (d) {
          const label = d.style.label || '';
          let width = 0;
          for (const ch of label) { width += ch.charCodeAt(0) > 0x2000 ? 13 : 7.2; }
          return [Math.max(90, Math.min(300, width + 22)), 32];
        },
        radius: 4,
        fill: function (d) { return d.style.fill; },
        stroke: function (d) { return d.style.stroke; },
        lineWidth: function (d) { return d.style.lineWidth; },
        labelText: function (d) { return d.style.label; },
        labelPlacement: 'bottom', labelFill: '#333', labelFontSize: 11,
      },
      state: {
        center: { stroke: '#1a73e8', lineWidth: 3 },
        impact: { fill: '#fff4e5', stroke: '#d68910', lineWidth: 2 },
        dim: { opacity: 0.16 },
      },
    },
    edge: {
      style: {
        stroke: function (d) { return d.style.stroke; },
        lineWidth: function (d) { return d.style.lineWidth; },
        endArrow: true, labelText: function (d) { return d.style.label || ''; },
        labelFontSize: 10, labelBackground: true, labelBackgroundFill: '#ffffff',
        labelBackgroundOpacity: 0.85,
        router: function (d) { return d.style.router; },
      },
    },
    // 自上而下比自左而右更紧凑：一层里放得下的节点更多，适配视口后字号还能看
    layout: { type: 'antv-dagre', rankdir: 'TB', nodesep: 16, ranksep: 70 },
    behaviors: ['zoom-canvas', 'drag-canvas', 'drag-element', 'click-select', 'hover-activate'],
  });
  graph.on('node:click', function (event) { run('center', event.target.id); });
  graph.on('node:dblclick', function (event) { run('centerView', event.target.id); });
  // 点空白处取消选中
  graph.on('canvas:click', function () { run('clear'); });
  return graph.render();
}

/**
 * 重建主图。**视口要保住**：除了第一次渲染，之后都恢复原来的缩放与平移，
 * 让人在切模式/改筛选时不会"画面整个跳走"。要居中得显式动作。
 */
function rebuildMain() {
  const saved = graph ? { zoom: graph.getZoom() } : null;
  const firstRender = !graph;
  const data = graphData();
  view = data;
  return createMainGraph(data, firstRender).then(function () {
    if (!saved) { return; }
    // 只恢复**缩放**，然后按内容重新居中：把旧的平移量也照搬的话，节点集合一变
    // （比如切到追溯/影响范围）内容就跑到画面外，看上去像"图空了"（实测踩到过）。
    return Promise.resolve(graph.zoomTo(saved.zoom)).then(function () {
      return nextFrame();
    }).then(function () {
      if (typeof graph.fitCenter === 'function') { graph.fitCenter(); }
    });
  }).then(function () {
    return applyHighlight();
  });
}

function nextFrame() {
  return new Promise(function (resolve) { requestAnimationFrame(function () { resolve(); }); });
}

/** 选中是增量更新：只改状态与边样式，不重建、不重排、不动视口。 */
function applyHighlight() {
  if (!graph) { return Promise.resolve(); }
  const states = {};
  const dimming = state.dim && state.center
    && (state.mode === 'relation' || state.mode === 'trace');
  const related = new Set();
  if (dimming) {
    related.add(state.center);
    (outgoing[state.center] || []).forEach(function (relation) { related.add(relation.target); });
    (incoming[state.center] || []).forEach(function (relation) { related.add(relation.source); });
  }
  if (state.mode === 'impact' && state.center) {
    impact(state.center, state.depth).forEach(function (hit) { states[hit.ref] = ['impact']; });
  }
  const data = graph.getData();
  data.nodes.forEach(function (node) {
    const list = states[node.id] || [];
    if (dimming && !related.has(node.id)) { list.push('dim'); }
    states[node.id] = list;
  });
  const edgeUpdates = data.edges.map(function (edge) {
    const base = edgeStyleOf(edge.data && edge.data.kind ? edge.data.kind : 'other');
    const isRelated = edge.source === state.center || edge.target === state.center;
    if (dimming && !isRelated) {
      return { id: edge.id, style: { label: '', stroke: '#eef0f2', lineWidth: 0.6 } };
    }
    return { id: edge.id, style: { label: base.label, stroke: base.stroke, lineWidth: base.lineWidth } };
  });
  graph.updateEdgeData(edgeUpdates);
  return Promise.resolve(graph.setElementState(states)).then(function () {
    return graph.draw();
  });
}

/** 显式居中：把视口移到某个元素上（带动画），缩放不动。 */
function centerView(ref) {
  if (!graph || !ref) { return Promise.resolve(); }
  // 当前视图里没有这个元素时直接跳过：focusElement 对不存在的元素会抛
  // "Cannot read properties of undefined (reading 'getRenderBounds')"（实测）
  const present = !!(view && view.nodes.some(function (node) { return node.id === ref; }));
  if (!present) { return Promise.resolve(); }
  if (typeof graph.focusElement === 'function') {
    try {
      return Promise.resolve(graph.focusElement(ref));
    } catch (error) {
      problems.push('居中失败: ' + error.message);
    }
  }
  return Promise.resolve();
}

// --- 右下角常驻的局部关系图 ---------------------------------------------------------

function renderMini() {
  const info = document.getElementById('miniInfo');
  const container = document.getElementById('miniCanvas');
  function drop() {
    if (miniGraph) { try { miniGraph.destroy(); } catch (error) { /* 忽略 */ } }
    miniGraph = null;
    container.innerHTML = '';
  }
  if (!state.center) {
    info.textContent = '未选中元素';
    drop();
    return Promise.resolve();
  }
  const sub = subgraph(state.center, state.depth);
  // 与主视图用同一套筛选（当前中心始终保留），否则小图里会冒出一堆主图上没有的属性/包含边
  const keepRefs = new Set(visibleElements().map(function (element) { return element.ref; }));
  keepRefs.add(state.center);
  const miniRefs = sub.nodes.filter(function (ref) { return keepRefs.has(ref); });
  const miniEdges = sub.edges.filter(function (relation) {
    return !(state.hideContainment && relation.kind === 'containment')
      && keepRefs.has(relation.source) && keepRefs.has(relation.target);
  });
  info.textContent = state.depth + ' 跳 · ' + miniRefs.length + ' 元素 / ' + miniEdges.length + ' 关系';
  drop();
  const nodes = miniRefs.map(function (ref) {
    const element = byRef[ref] || { ref: ref };
    return {
      id: ref,
      style: Object.assign({}, NODE_STYLE[categoryOf(element)], {
        label: (ref === state.center ? '▶ ' : '') + labelOf(element),
      }),
    };
  });
  const edges = miniEdges.map(function (relation, index) {
    const style = edgeStyleOf(relation.kind);
    return {
      id: 'm' + index, source: relation.source, target: relation.target,
      type: 'polyline',
      style: { stroke: style.stroke, lineWidth: style.lineWidth, router: { type: 'orth', padding: 8 } },
    };
  });
  miniGraph = new G6.Graph({
    container: container,
    autoFit: 'view',
    padding: 12,
    animation: false,
    data: { nodes: nodes, edges: edges },
    node: {
      type: 'rect',
      style: {
        size: function (d) {
          const label = d.style.label || '';
          let width = 0;
          for (const ch of label) { width += ch.charCodeAt(0) > 0x2000 ? 9 : 5; }
          return [Math.max(56, Math.min(160, width + 12)), 20];
        },
        radius: 3,
        fill: function (d) { return d.style.fill; },
        stroke: function (d) { return d.style.stroke; },
        lineWidth: function (d) { return d.style.lineWidth; },
        labelText: function (d) { return d.style.label; },
        labelFill: '#333', labelFontSize: 9,
      },
      state: { center: { stroke: '#1a73e8', lineWidth: 2.5 } },
    },
    edge: {
      style: {
        stroke: function (d) { return d.style.stroke; },
        lineWidth: function (d) { return d.style.lineWidth; },
        endArrow: true, router: function (d) { return d.style.router; },
      },
    },
    layout: { type: 'antv-dagre', rankdir: 'TB', nodesep: 8, ranksep: 34 },
    behaviors: ['zoom-canvas', 'drag-canvas'],
  });
  // 小图的容器是绝对定位的：等一帧再适配视口，否则按 0×0 尺寸算出来会画到面板外面
  const mini = miniGraph;
  mini.on('node:click', function (event) { run('center', event.target.id); });
  return mini.render().then(function () {
    return nextFrame();
  }).then(function () {
    if (typeof mini.fitView === 'function') { mini.fitView(); }
    return mini.setElementState({ [state.center]: ['center'] });
  }).catch(function (error) { problems.push('mini: ' + error.message); });
}

// --- 右侧面板 --------------------------------------------------------------------

/** 面板里的元素链接统一走委托：点谁就选中谁（矩阵模式会顺带切回关系视图）。 */
function bindLinks(scope) {
  Array.from(scope.querySelectorAll('a.elink')).forEach(function (link) {
    link.onclick = function (event) {
      event.stopPropagation();
      run('link', link.dataset.ref);
    };
  });
}

function renderPanel() {
  const panel = document.getElementById('panel');
  if (!state.center) {
    panel.innerHTML = '<h3>元素关系</h3>'
      + '<p class="muted">点左侧树的元素、或图上的节点作为中心。<br>'
      + '选中不会移动视口；要居中用工具栏的"居中选中元素"、双击节点，或在树上再点一次。</p>';
    return;
  }
  const element = byRef[state.center] || { ref: state.center };
  let html = '<h3>' + escapeHtml(labelOf(element)) + '</h3><table>'
    + '<tr><td class="muted">限定名</td><td>' + escapeHtml(element.ref) + '</td></tr>'
    + '<tr><td class="muted">元类</td><td>' + escapeHtml(literalText(element.metaclass)) + '</td></tr>'
    + '<tr><td class="muted">来源</td><td>' + escapeHtml(literalText(element.origin))
    + (element.source ? (' · ' + escapeHtml(shortUri(element.source.uri)) + ':' + element.source.line) : '')
    + '</td></tr>'
    + '<tr><td class="muted">出现在</td><td>'
    + ((element.views && element.views.length)
        ? element.views.map(function (view) { return escapeHtml(view); }).join('<br>')
        : '<span class="muted">—</span>')
    + '</td></tr></table>';

  if (state.mode === 'impact') { html += panelImpact(); }
  else if (state.mode === 'trace') { html += panelTrace(); }
  else { html += panelNeighbors(); }
  panel.innerHTML = html;
  bindLinks(panel);
}

function panelNeighbors() {
  const list = neighbors(state.center);
  const out = list.filter(function (item) { return item.outgoing; });
  const into = list.filter(function (item) { return !item.outgoing; });
  function block(title, items) {
    let html = '<h4>' + title + '（' + items.length + '）</h4><table>'
      + '<tr><th>关系</th><th>相关元素</th><th></th></tr>';
    items.forEach(function (item) {
      html += '<tr><td>' + escapeHtml(item.kind) + '</td>'
        + '<td>' + linkHtml(item.ref) + '</td>'
        + '<td class="muted">' + (item.authored ? '' : '推导') + '</td></tr>';
    });
    return html + '</table>';
  }
  return block('出边', out) + block('入边', into);
}

function panelTrace() {
  const trace = INDEX.relations.filter(function (relation) { return TRACE_KINDS.has(relation.kind); });
  const groups = {};
  trace.forEach(function (relation) { (groups[relation.kind] = groups[relation.kind] || []).push(relation); });
  let html = '<h4>追溯关系（全模型）</h4><table><tr><th>类型</th><th>条数</th></tr>'
    + Object.keys(groups).sort().map(function (kind) {
        return '<tr><td>' + escapeHtml(kind) + '</td><td>' + groups[kind].length + '</td></tr>';
      }).join('')
    + '</table>';
  Object.keys(groups).sort().forEach(function (kind) {
    html += '<h4>' + escapeHtml(kind) + '（' + groups[kind].length + '）</h4><table>';
    groups[kind].forEach(function (relation) {
      html += '<tr><td>' + linkHtml(relation.source) + '</td><td class="muted">→</td>'
        + '<td>' + linkHtml(relation.target) + '</td></tr>';
    });
    html += '</table>';
  });
  return html;
}

function panelImpact() {
  const hits = impact(state.center, state.depth);
  let html = '<h4>影响范围</h4><p class="muted">沿反向边可达（不含包含关系）：改动本元素会牵连 '
    + hits.length + ' 个元素。</p><table>'
    + '<tr><th>步数</th><th>经由</th><th>元素</th><th>位置</th></tr>';
  hits.forEach(function (hit) {
    const element = byRef[hit.ref] || {};
    const where = element.source ? (shortUri(element.source.uri) + ':' + element.source.line) : '';
    html += '<tr><td>' + hit.depth + '</td><td>' + escapeHtml(hit.viaKind) + '</td>'
      + '<td>' + linkHtml(hit.ref) + '</td>'
      + '<td class="muted">' + escapeHtml(where) + '</td></tr>';
  });
  return html + '</table>';
}

// --- 追溯矩阵 --------------------------------------------------------------------

function renderMatrix() {
  const box = document.getElementById('matrix');
  if (!MATRIX) { box.innerHTML = '<p class="muted">这个工作区没有矩阵数据。</p>'; return; }
  let html = '<div class="tiles">'
    + '<div class="tile"><span class="muted">需求</span><b>' + MATRIX.requirements + '</b></div>'
    + '<div class="tile"><span class="muted">已满足</span><b>' + MATRIX.satisfied + '</b></div>'
    + '<div class="tile"><span class="muted">已验证</span><b>' + MATRIX.verified + '</b></div>'
    + '<div class="tile' + (MATRIX.gaps ? ' gap' : '') + '"><span class="muted">缺口</span><b>'
    + MATRIX.gaps + '</b></div></div>'
    + '<p class="muted">表格里每个元素都是链接：点它就把该元素设为中心（并切回关系视图），'
    + '不是只能跳到需求行。</p>'
    + '<table><tr><th>需求</th><th>满足方</th><th>验证方</th><th>派生</th><th>位置</th></tr>';
  MATRIX.rows.forEach(function (row) {
    const gap = !row.satisfiedBy.length && !row.verifiedBy.length;
    const title = row.reqId
      ? ('[' + row.reqId + '] ' + (row.name || shortName(row.ref)))
      : (row.name || shortName(row.ref));
    html += '<tr class="' + (gap ? 'gap' : '') + '">'
      + '<td>' + linkHtml(row.ref, title)
      + (gap ? ' <b style="color:#c0392b">缺口</b>' : '') + '</td>'
      + '<td>' + (row.satisfiedBy.length
          ? row.satisfiedBy.map(function (ref) { return linkHtml(ref); }).join('<br>')
          : '<span class="muted">—</span>') + '</td>'
      + '<td>' + (row.verifiedBy.length
          ? row.verifiedBy.map(function (ref) { return linkHtml(ref); }).join('<br>')
          : '<span class="muted">—</span>') + '</td>'
      + '<td>'
      + (row.derivedFrom.length
          ? ('派生自 ' + row.derivedFrom.map(function (ref) { return linkHtml(ref); }).join(', ')) : '')
      + (row.derivedBy.length
          ? ('<br>派生出 ' + row.derivedBy.map(function (ref) { return linkHtml(ref); }).join(', ')) : '')
      + (row.derivedFrom.length || row.derivedBy.length ? '' : '<span class="muted">—</span>')
      + '</td>'
      + '<td class="muted">' + escapeHtml(literalText(row.file))
      + (row.line ? (':' + row.line) : '') + '</td></tr>';
  });
  box.innerHTML = html + '</table>';
  bindLinks(box);
}

// --- 模式列表、状态与脚本接口 -------------------------------------------------------

const MODES = [
  { id: 'relation', label: '元素关系' },
  { id: 'trace', label: '追溯关系' },
  { id: 'impact', label: '影响范围' },
  { id: 'matrix', label: '需求追溯矩阵' },
];

function renderModes() {
  const box = document.getElementById('modes');
  box.innerHTML = '<div class="head">视图模式</div>';
  MODES.forEach(function (mode) {
    const button = document.createElement('button');
    button.textContent = mode.label;
    button.className = state.mode === mode.id ? 'active' : '';
    button.onclick = function () { run('mode', mode.id); };
    box.appendChild(button);
  });
}

/** 矩阵模式用表格替掉图区；局部关系图也跟着收起（它是图区的附属）。 */
function layoutForMode() {
  const matrixMode = state.mode === 'matrix';
  document.getElementById('matrix').style.display = matrixMode ? 'block' : 'none';
  document.getElementById('stage').style.display = matrixMode ? 'none' : 'block';
  document.getElementById('panel').style.display = matrixMode ? 'none' : 'block';
}

function paintStatus() {
  const element = state.center ? (byRef[state.center] || {}) : null;
  document.getElementById('counts').textContent = (view && state.mode !== 'matrix')
    ? ('节点 ' + view.nodes.length + ' · 边 ' + view.edges.length
       + (state.center ? (' · 中心 ' + labelOf(element)) : ' · 未选中'))
    : ('模型 ' + INDEX.elements.length + ' 元素 / ' + INDEX.relations.length + ' 关系');
  document.getElementById('timings').textContent =
    timings.renderMs === undefined ? '' : ('渲染 ' + Math.round(timings.renderMs) + 'ms');
  document.getElementById('status').textContent = problems.length
    ? ('问题：' + problems[0]) : ('G6 ' + G6.version + ' · 索引由 Java 侧产出');
}

function snapshot() {
  const sub = state.center ? subgraph(state.center, state.depth) : { nodes: [], edges: [] };
  const hits = state.center ? impact(state.center, state.depth) : [];
  const list = state.center ? neighbors(state.center) : [];
  const byDepth = {};
  hits.forEach(function (hit) { byDepth[hit.depth] = (byDepth[hit.depth] || 0) + 1; });
  return {
    mode: state.mode,
    center: state.center,
    depth: state.depth,
    edgeKind: state.edgeKind,
    filters: { hideAttributes: state.hideAttributes, hideContainment: state.hideContainment,
               dim: state.dim },
    counts: {
      modelElements: INDEX.elements.length,
      modelRelations: INDEX.relations.length,
      drawnNodes: view ? view.nodes.length : 0,
      drawnEdges: view ? view.edges.length : 0,
      neighbors: list.length,
      subgraphNodes: sub.nodes.length,
      subgraphEdges: sub.edges.length,
      miniNodes: document.getElementById('miniInfo').textContent,
      impact: hits.length,
      impactByDepth: byDepth,
    },
    matrix: MATRIX ? { requirements: MATRIX.requirements, satisfied: MATRIX.satisfied,
                       verified: MATRIX.verified, gaps: MATRIX.gaps } : null,
    // 视口单列出来：用来证明"选中不动视口、只有显式居中才动"
    viewport: graph ? { zoom: Math.round(graph.getZoom() * 1000) / 1000,
                        position: graph.getPosition().map(function (v) { return Math.round(v); }) } : null,
    miniViewport: (function () {
      if (!miniGraph) { return null; }
      try {
        return { zoom: Math.round(miniGraph.getZoom() * 1000) / 1000,
                 size: miniGraph.getSize().map(function (v) { return Math.round(v); }),
                 position: miniGraph.getPosition().map(function (v) { return Math.round(v); }),
                 content: (function () {
                   const list = miniGraph.getNodeData();
                   const xs = list.map(function (n) { return miniGraph.getElementPosition(n.id)[0]; });
                   const ys = list.map(function (n) { return miniGraph.getElementPosition(n.id)[1]; });
                   return { nodes: list.length,
                            width: Math.round(Math.max.apply(null, xs) - Math.min.apply(null, xs)),
                            height: Math.round(Math.max.apply(null, ys) - Math.min.apply(null, ys)) };
                 })() };
      } catch (error) {
        return null;   // 小图可能刚被销毁（报快照时正处在重建之间）
      }
    })(),
    timings: Object.assign({}, timings),
    history: history.slice(-30),
    problems: problems.slice(0, 6),
  };
}

/** 选中、面板、小图、树、状态栏一起刷新。rebuild=true 时才重建主图。 */
function refresh(rebuild) {
  const started = performance.now();
  return (rebuild ? rebuildMain() : applyHighlight()).then(function () {
    timings.renderMs = performance.now() - started;
    return renderMini();
  }).then(function () {
    renderPanel();
    renderTree();
    paintStatus();
    document.getElementById('metrics').textContent = JSON.stringify(snapshot());
  });
}

function execute(name, arg) {
  if (name === 'center') {
    state.center = arg;
    // 影响范围模式的节点集合依赖中心，必须重建；关系/追溯只是改高亮
    return refresh(state.mode === 'impact');
  }
  if (name === 'link') {
    state.center = arg;
    if (state.mode === 'matrix') { return setMode('relation').then(function () { return refresh(false); }); }
    return refresh(state.mode === 'impact');
  }
  if (name === 'treePick') {
    if (state.center === arg) { return centerView(arg); }
    state.center = arg;
    return refresh(state.mode === 'impact');
  }
  if (name === 'clear') {
    state.center = null;
    if (state.mode === 'impact') { return setMode('relation'); }
    return refresh(false);
  }
  if (name === 'centerView') { return centerView(arg || state.center); }
  if (name === 'mode') { return setMode(arg); }
  if (name === 'depth') {
    state.depth = Number(arg);
    syncControls();
    return refresh(state.mode === 'impact');
  }
  if (name === 'filter') {
    const structural = ('hideAttributes' in (arg || {})) || ('hideContainment' in (arg || {}));
    Object.assign(state, arg || {});
    syncControls();
    if (state.mode === 'matrix') { renderMatrix(); paintStatus(); return Promise.resolve(); }
    return refresh(structural);
  }
  if (name === 'edgeKind') {
    state.edgeKind = arg;
    syncControls();
    return refresh(true);
  }
  if (name === 'collapseAll') { setTreeCollapsed(true); renderTree(); return Promise.resolve(); }
  if (name === 'expandAll') { setTreeCollapsed(false); renderTree(); return Promise.resolve(); }
  if (name === 'row') {
    const row = MATRIX && MATRIX.rows[Number(arg)];
    return row ? execute('link', row.ref) : Promise.resolve();
  }
  return Promise.resolve();
}

function setMode(mode) {
  state.mode = mode;
  renderModes();
  layoutForMode();
  if (mode === 'matrix') { renderMatrix(); paintStatus(); return Promise.resolve(); }
  return refresh(true);
}

function run(name, arg) {
  busy = busy.then(function () { return execute(name, arg); },
                   function () { return execute(name, arg); });
  return busy.then(function (result) {
    history.push({ step: name, arg: arg, center: state.center, mode: state.mode,
                   viewport: graph ? { zoom: Math.round(graph.getZoom() * 1000) / 1000,
                                       position: graph.getPosition().map(function (v) { return Math.round(v); }) } : null });
    return result;
  });
}

function syncControls() {
  document.getElementById('depth').value = String(state.depth);
  document.getElementById('edgeKind').value = state.edgeKind;
  document.getElementById('hideAttributes').checked = state.hideAttributes;
  document.getElementById('hideContainment').checked = state.hideContainment;
  document.getElementById('dim').checked = state.dim;
}

function bindControls() {
  document.getElementById('depth').onchange = function (event) { run('depth', event.target.value); };
  document.getElementById('edgeKind').onchange = function (event) { run('edgeKind', event.target.value); };
  document.getElementById('hideAttributes').onchange = function (event) {
    run('filter', { hideAttributes: event.target.checked });
  };
  document.getElementById('hideContainment').onchange = function (event) {
    run('filter', { hideContainment: event.target.checked });
  };
  document.getElementById('dim').onchange = function (event) {
    run('filter', { dim: event.target.checked });
  };
  document.getElementById('centerBtn').onclick = function () { run('centerView'); };
  document.getElementById('clearBtn').onclick = function () { run('clear'); };
  document.addEventListener('keydown', function (event) {
    if (event.key === 'Escape') { run('clear'); }
  });
}

function boot() {
  document.getElementById('ws').textContent = DEMO.workspace
    + ' · 索引 ' + DEMO.indexPath + '、矩阵 ' + DEMO.matrixPath + '（' + DEMO.generatedAt + '）';
  document.getElementById('engine').textContent = 'G6 ' + G6.version;
  // 默认中心：优先"作者写的需求"（有需求号）。不能随便挑一个 RequirementUsage——
  // `objective { verify X; }` 这类编译器生成的包装用法也是 RequirementUsage（索引里叫 obj）。
  const authored = INDEX.elements.filter(function (element) {
    return element.metaclass === 'RequirementUsage' && element.reqId;
  });
  const anyRequirement = INDEX.elements.filter(function (element) {
    return element.metaclass === 'RequirementUsage';
  });
  const fallback = INDEX.elements.filter(function (element) { return element.metaclass !== 'Package'; });
  state.center = ((authored[0] || anyRequirement[0] || fallback[0] || {}).ref) || null;
  syncControls();
  bindControls();
  renderModes();
  layoutForMode();
  const ready = refresh(true);
  window.__PROTO = { ready: ready, run: run, report: snapshot, state: state };
}

boot();
"""


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--workspace", required=True)
    parser.add_argument("--out", type=Path, required=True)
    parser.add_argument("--lib", type=Path, required=True)
    parser.add_argument("--data-dir", type=Path, default=REPO / "build" / "demo-data")
    parser.add_argument("--reuse", action="store_true")
    args = parser.parse_args()

    data = ensure_data(args.workspace, args.data_dir, args.reuse)
    data["generatedBy"] = "scripts/make-relation-demo.py"
    data["generatedAt"] = time.strftime("%Y-%m-%d %H:%M")

    lib = args.lib.read_text(encoding="utf-8")
    title = "关系与追溯 Demo · %s" % args.workspace
    html = (PAGE
            .replace("__TITLE__", title)
            .replace("__LIB__", lib)
            .replace("__DATA__", json.dumps(data, ensure_ascii=False))
            .replace("__JS__", JS))
    args.out.parent.mkdir(parents=True, exist_ok=True)
    args.out.write_text(html, encoding="utf-8")
    print("[demo] %s -> %s (%.1f KB；索引 %d 元素 / %d 关系)"
          % (args.workspace, args.out, args.out.stat().st_size / 1024,
             len(data["index"]["elements"]), len(data["index"]["relations"])))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
