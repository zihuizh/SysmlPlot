#!/usr/bin/env python3
"""生成"关系与追溯"Demo 页：G6 + 全模型索引 + 追溯矩阵。

Demo 页覆盖五件事（对应阶段 3 已有的语义能力，现在换 G6 展示）：

  1. 元素关系      点选元素 → 一跳邻居（出/入、关系类型、作者写 or 推导）
  2. 局部关系图    以某个元素为中心、N 跳内的子图（Java 侧另有 --local-view 产物）
  3. 追溯关系      只留 satisfy / verify / derive，按类型着色
  4. 影响范围      反向可达（排除 containment），按步数分层
  5. 追溯矩阵      需求行 × 满足方/验证方/派生，含覆盖率与缺口

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
        # 必须显式指定 UTF-8：默认按本地代码页（GBK）解码，Java 输出的中文会直接炸掉读取线程
        capture_output=True, text=True, encoding="utf-8", errors="replace",
    )
    if result.returncode != 0:
        raise SystemExit("run-view 失败：%s\n%s\n%s" % (" ".join(args), result.stdout[-600:], result.stderr[-600:]))
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
    # 矩阵可能为空表（没有需求行），此时 Java 仍会写出文件
    matrix = json.loads(matrix_path.read_text(encoding="utf-8")) if matrix_path.is_file() else None
    return {"index": index, "matrix": matrix, "workspace": workspace,
            "dataDir": str(data_dir), "indexPath": index_path.name, "matrixPath": matrix_path.name}


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
         grid-template-columns:250px 1fr 360px; grid-template-rows:46px 40px 1fr 28px; }
  header { grid-column:1/4; display:flex; align-items:center; gap:12px; padding:0 14px;
           border-bottom:1px solid var(--line); }
  header .title { font-weight:600; }
  header .ws { color:var(--muted); font-size:12px; }
  header .status { margin-left:auto; color:var(--muted); font-size:12px; }
  nav.modes { grid-column:1/4; display:flex; align-items:center; gap:6px; padding:0 14px;
              border-bottom:1px solid var(--line); background:var(--bg); }
  nav.modes button { padding:4px 12px; border:1px solid var(--line); background:#fff;
                     border-radius:4px; cursor:pointer; font:12px inherit; }
  nav.modes button.active { border-color:var(--accent); color:var(--accent); font-weight:600; }
  nav.modes .ctl { margin-left:auto; display:flex; align-items:center; gap:12px; font-size:12px; color:#333; }
  nav.modes label { display:flex; align-items:center; gap:4px; }
  nav.modes select { font:12px inherit; padding:2px 4px; }
  #tree { border-right:1px solid var(--line); overflow:auto; padding:8px 6px; }
  #tree .group { color:var(--muted); font-size:11px; margin:8px 0 2px; }
  #tree .item { cursor:pointer; padding:2px 5px; border-radius:3px; white-space:nowrap;
                overflow:hidden; text-overflow:ellipsis; }
  #tree .item:hover { background:#eef2f8; }
  #tree .item.center { background:#e8f0fe; font-weight:600; }
  #tree .badge { color:#c0392b; font-size:10px; margin-left:4px; }
  #canvas { position:relative; overflow:hidden; }
  #panel { border-left:1px solid var(--line); overflow:auto; padding:10px 12px; }
  #panel h3 { margin:2px 0 8px; font-size:13px; }
  #panel h4 { margin:12px 0 4px; font-size:12px; color:#444; }
  #panel table { border-collapse:collapse; width:100%; font-size:12px; }
  #panel td, #panel th { border-bottom:1px solid #f0f0f0; padding:3px 4px; text-align:left;
                         vertical-align:top; }
  #panel th { color:var(--muted); font-weight:500; }
  #panel tr.click { cursor:pointer; }
  #panel tr.click:hover { background:#f3f7fd; }
  #panel .muted { color:var(--muted); }
  #panel .pill { display:inline-block; padding:0 6px; border-radius:8px; background:#eef2f8;
                 font-size:11px; margin:0 4px 3px 0; }
  #matrix { grid-column:2/4; overflow:auto; padding:14px 18px; display:none; }
  #matrix table { border-collapse:collapse; width:100%; font-size:12px; }
  #matrix th, #matrix td { border:1px solid #e6e6e6; padding:5px 8px; text-align:left;
                           vertical-align:top; }
  #matrix th { background:#f6f8fa; font-weight:600; }
  #matrix tr.gap { background:#fff6f6; }
  #matrix tr.click { cursor:pointer; }
  #matrix tr.click:hover { background:#eef4ff; }
  #matrix .tiles { display:flex; gap:10px; margin-bottom:12px; }
  #matrix .tile { border:1px solid var(--line); border-radius:6px; padding:6px 14px; background:#fff; }
  #matrix .tile b { display:block; font-size:18px; }
  #matrix .tile.gap b { color:#c0392b; }
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
<nav class="modes" id="modes">
  <div class="ctl" id="controls">
    <label>深度 <select id="depth"><option value="1">1 跳</option><option value="2" selected>2 跳</option><option value="3">3 跳</option></select></label>
    <label><input type="checkbox" id="hideAttributes"> 隐藏属性</label>
    <label><input type="checkbox" id="hideContainment"> 隐藏包含边</label>
    <label><input type="checkbox" id="dim" checked> 淡化无关元素</label>
  </div>
</nav>
<nav id="tree"></nav>
<div id="canvas"></div>
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
// Demo 页：索引 + 追溯矩阵 → G6 图。五个模式共用一份语义计算，不猜语义。
// 语义计算（邻居 / 子图 / 影响范围）照 Java 侧 ModelQuery 的算法实现，
// 页内结果与 `run-view.ps1 --query` 的输出可以对上（见 docs/RELATION-DEMO.md）。
// ============================================================================
const DEMO = JSON.parse(document.getElementById('demo-data').textContent);
const INDEX = DEMO.index;
const MATRIX = DEMO.matrix;
const problems = [];
const timings = {};

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
  mode: 'relation',
  center: null,
  depth: 2,
  // 默认藏掉属性与包含边：Demo 讲的是关系与追溯，包含边和属性只会把图铺开
  hideAttributes: true,
  hideContainment: true,
  dim: true,
};
let graph = null;
let view = null;
let busy = Promise.resolve();

window.addEventListener('error', function (event) { problems.push(String(event.message)); paintStatus(); });

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

/** URI 只留文件名：整条 file:/// 会把面板撑爆。 */
function shortUri(uri) {
  const text = literalText(uri).replace(/\/+$/, '');
  const index = text.lastIndexOf('/');
  return index < 0 ? text : text.slice(index + 1);
}

function literalText(value) {
  return value === null || value === undefined ? '' : String(value);
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

function labelOf(element) {
  if (!element) { return '?'; }
  if (element.reqId) { return '[' + element.reqId + '] ' + (element.name || shortName(element.ref)); }
  return element.name || shortName(element.ref);
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

// --- 画图 ------------------------------------------------------------------------

function visibleElements() {
  return INDEX.elements.filter(function (element) {
    if (element.origin === 'library') { return false; }
    if (state.hideAttributes && categoryOf(element) === 'attribute') { return false; }
    return true;
  });
}

function visibleRelations(allowedKinds) {
  return INDEX.relations.filter(function (relation) {
    if (state.hideContainment && relation.kind === 'containment') { return false; }
    if (allowedKinds && !allowedKinds.has(relation.kind)) { return false; }
    return true;
  });
}

/** 按模式算出这一帧要画的节点、边与高亮状态。 */
function buildView() {
  const gaps = gapRefs();
  const center = state.center;
  const highlight = { states: {} };
  let allowedKinds = null;
  let keep = null;

  if (state.mode === 'trace') {
    allowedKinds = TRACE_KINDS;
    keep = new Set();
    INDEX.relations.forEach(function (relation) {
      if (TRACE_KINDS.has(relation.kind)) { keep.add(relation.source); keep.add(relation.target); }
    });
  } else if (state.mode === 'local' && center) {
    keep = new Set(subgraph(center, state.depth).nodes);
  } else if (state.mode === 'impact' && center) {
    keep = new Set([center]);
    impact(center, state.depth).forEach(function (hit) { keep.add(hit.ref); });
  }

  let elements = visibleElements();
  if (keep) {
    elements = elements.filter(function (element) { return keep.has(element.ref); });
  }
  const elementRefs = new Set(elements.map(function (element) { return element.ref; }));

  const relations = visibleRelations(allowedKinds).filter(function (relation) {
    return elementRefs.has(relation.source) && elementRefs.has(relation.target);
  });

  const nodes = elements.map(function (element) {
    const style = Object.assign({}, NODE_STYLE[categoryOf(element)]);
    return {
      id: element.ref,
      style: Object.assign(style, {
        label: labelOf(element) + (gaps[element.ref] ? '  (!)' : ''),
      }),
      data: { element: element },
    };
  });
  const edges = relations.map(function (relation, index) {
    const style = edgeStyleOf(relation.kind);
    return {
      id: 'e' + index,
      source: relation.source,
      target: relation.target,
      style: { label: style.label, stroke: style.stroke, lineWidth: style.lineWidth },
      data: { relation: relation },
    };
  });

  if (state.mode === 'impact' && center) {
    impact(center, state.depth).forEach(function (hit) { highlight.states[hit.ref] = ['impact']; });
  }
  // 关系/追溯模式：与中心无关的元素淡化，让一遍关系跳出来
  if (state.dim && center && (state.mode === 'relation' || state.mode === 'trace')) {
    const related = new Set([center]);
    (outgoing[center] || []).forEach(function (relation) { related.add(relation.target); });
    (incoming[center] || []).forEach(function (relation) { related.add(relation.source); });
    nodes.forEach(function (node) {
      if (!related.has(node.id)) { highlight.states[node.id] = ['dim']; }
    });
    edges.forEach(function (edge) {
      if (edge.source !== center && edge.target !== center) {
        edge.style = { label: '', stroke: '#eef0f2', lineWidth: 0.6 };
      }
    });
  }
  return { nodes: nodes, edges: edges, highlight: highlight, gaps: gaps };
}

function render() {
  const started = performance.now();
  view = buildView();
  const container = document.getElementById('canvas');
  if (graph) { try { graph.destroy(); } catch (error) { /* 忽略销毁异常 */ } graph = null; }
  container.innerHTML = '';
  graph = new G6.Graph({
    container: container,
    autoFit: 'view',
    padding: 20,
    animation: view.nodes.length <= 60,
    data: { nodes: view.nodes, edges: view.edges },
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
      type: 'polyline',
      style: {
        stroke: function (d) { return d.style.stroke; },
        lineWidth: function (d) { return d.style.lineWidth; },
        endArrow: true,
        labelText: function (d) { return d.style.label || ''; },
        labelFontSize: 10, labelBackground: true, labelBackgroundFill: '#ffffff',
        labelBackgroundOpacity: 0.85,
      },
    },
    // 自上而下比自左而右更紧凑：一层里放得下的节点更多，适配视口后字号还能看
    layout: { type: 'antv-dagre', rankdir: 'TB', nodesep: 16, ranksep: 70 },
    behaviors: ['zoom-canvas', 'drag-canvas', 'drag-element', 'click-select', 'hover-activate'],
  });
  graph.on('node:click', function (event) {
    state.center = event.target.id;
    renderTree();
    render().then(renderPanel);
  });
  graph.on('node:dblclick', function (event) {
    state.center = event.target.id;
    setMode('local');
  });
  return Promise.resolve(graph.render()).then(function () {
    const states = view.highlight.states;
    if (states && Object.keys(states).length) { return graph.setElementState(states); }
  }).then(function () {
    timings.renderMs = performance.now() - started;
    paintStatus();
    document.getElementById('metrics').textContent = JSON.stringify(snapshot());
  });
}

// --- 左侧列表 / 右侧面板 / 矩阵 ----------------------------------------------------

function renderTree() {
  const box = document.getElementById('tree');
  box.innerHTML = '';
  const gaps = gapRefs();
  const groups = {};
  INDEX.elements.forEach(function (element) {
    if (element.origin === 'library') { return; }
    const owner = element.ref.indexOf('::') < 0
      ? '(root)' : element.ref.slice(0, element.ref.lastIndexOf('::'));
    (groups[owner] = groups[owner] || []).push(element);
  });
  Object.keys(groups).sort().forEach(function (owner) {
    const title = document.createElement('div');
    title.className = 'group';
    title.textContent = shortName(owner);
    box.appendChild(title);
    groups[owner].sort(function (a, b) { return a.ref < b.ref ? -1 : 1; }).forEach(function (element) {
      const item = document.createElement('div');
      item.className = 'item' + (element.ref === state.center ? ' center' : '');
      item.textContent = labelOf(element);
      item.title = element.ref;
      if (gaps[element.ref]) {
        const badge = document.createElement('span');
        badge.className = 'badge';
        badge.textContent = '缺口';
        item.appendChild(badge);
      }
      item.onclick = function () { run('center', element.ref); };
      box.appendChild(item);
    });
  });
}

function bindRowClicks(scope) {
  Array.from(scope.querySelectorAll('tr.click')).forEach(function (row) {
    row.onclick = function () { run('center', row.dataset.ref); };
  });
}

function renderPanel() {
  const panel = document.getElementById('panel');
  if (!state.center) {
    panel.innerHTML = '<h3>元素关系</h3><p class="muted">点左侧列表或图上的元素作为中心。</p>';
    return;
  }
  const element = byRef[state.center] || { ref: state.center };
  let html = '<h3>' + escapeHtml(labelOf(element)) + '</h3><table>'
    + '<tr><td class="muted">限定名</td><td>' + escapeHtml(element.ref) + '</td></tr>'
    + '<tr><td class="muted">元类</td><td>' + escapeHtml(literalText(element.metaclass)) + '</td></tr>'
    + '<tr><td class="muted">来源</td><td>' + escapeHtml(literalText(element.origin))
    + (element.source ? (' · ' + escapeHtml(shortUri(element.source.uri)) + ':' + element.source.line) : '')
    + '</td></tr></table>';

  if (state.mode === 'relation') { html += panelNeighbors(); }
  else if (state.mode === 'local') { html += panelLocal(); }
  else if (state.mode === 'trace') { html += panelTrace(); }
  else if (state.mode === 'impact') { html += panelImpact(); }
  else if (state.mode === 'matrix') { html += '<p class="muted">矩阵在中间区域，点行可跳到该需求。</p>'; }
  panel.innerHTML = html;
  bindRowClicks(panel);
}

function panelNeighbors() {
  const list = neighbors(state.center);
  const out = list.filter(function (item) { return item.outgoing; });
  const into = list.filter(function (item) { return !item.outgoing; });
  function block(title, items) {
    let html = '<h4>' + title + '（' + items.length + '）</h4><table>'
      + '<tr><th>关系</th><th>元素</th><th></th></tr>';
    items.forEach(function (item) {
      html += '<tr class="click" data-ref="' + escapeHtml(item.ref) + '">'
        + '<td>' + escapeHtml(item.kind) + '</td>'
        + '<td>' + escapeHtml(labelOf(byRef[item.ref] || { ref: item.ref })) + '</td>'
        + '<td class="muted">' + (item.authored ? '' : '推导') + '</td></tr>';
    });
    return html + '</table>';
  }
  return block('出边', out) + block('入边', into);
}

function panelLocal() {
  const sub = subgraph(state.center, state.depth);
  return '<h4>局部关系图</h4>'
    + '<p class="muted">以本元素为中心、' + state.depth + ' 跳内的子图：'
    + sub.nodes.length + ' 个元素 / ' + sub.edges.length + ' 条关系。'
    + 'Java 侧同一算法的产物出口是 <code>--local-view</code>。</p>'
    + '<h4>子图内元素</h4><table>'
    + sub.nodes.map(function (ref) {
        const element = byRef[ref] || { ref: ref };
        return '<tr class="click" data-ref="' + escapeHtml(ref) + '"><td>'
          + escapeHtml(labelOf(element)) + '</td><td class="muted">'
          + escapeHtml(literalText(element.metaclass)) + '</td></tr>';
      }).join('')
    + '</table>';
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
      html += '<tr><td class="click" data-ref="' + escapeHtml(relation.source) + '">'
        + escapeHtml(labelOf(byRef[relation.source] || { ref: relation.source })) + '</td>'
        + '<td class="muted">→</td>'
        + '<td class="click" data-ref="' + escapeHtml(relation.target) + '">'
        + escapeHtml(labelOf(byRef[relation.target] || { ref: relation.target })) + '</td></tr>';
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
    html += '<tr class="click" data-ref="' + escapeHtml(hit.ref) + '">'
      + '<td>' + hit.depth + '</td><td>' + escapeHtml(hit.viaKind) + '</td>'
      + '<td>' + escapeHtml(labelOf(element)) + '</td>'
      + '<td class="muted">' + escapeHtml(where) + '</td></tr>';
  });
  return html + '</table>';
}

function renderMatrix() {
  const box = document.getElementById('matrix');
  if (!MATRIX) { box.innerHTML = '<p class="muted">这个工作区没有矩阵数据。</p>'; return; }
  let html = '<div class="tiles">'
    + '<div class="tile"><span class="muted">需求</span><b>' + MATRIX.requirements + '</b></div>'
    + '<div class="tile"><span class="muted">已满足</span><b>' + MATRIX.satisfied + '</b></div>'
    + '<div class="tile"><span class="muted">已验证</span><b>' + MATRIX.verified + '</b></div>'
    + '<div class="tile' + (MATRIX.gaps ? ' gap' : '') + '"><span class="muted">缺口</span><b>'
    + MATRIX.gaps + '</b></div></div>'
    + '<table><tr><th>需求</th><th>满足方</th><th>验证方</th><th>派生</th><th>位置</th></tr>';
  MATRIX.rows.forEach(function (row) {
    const gap = !row.satisfiedBy.length && !row.verifiedBy.length;
    const title = row.reqId
      ? ('[' + row.reqId + '] ' + (row.name || shortName(row.ref)))
      : (row.name || shortName(row.ref));
    html += '<tr class="click' + (gap ? ' gap' : '') + '" data-ref="' + escapeHtml(row.ref) + '">'
      + '<td>' + escapeHtml(title) + (gap ? ' <b style="color:#c0392b">缺口</b>' : '') + '</td>'
      + '<td>' + row.satisfiedBy.map(shortName).map(escapeHtml).join('<br>') + '</td>'
      + '<td>' + row.verifiedBy.map(shortName).map(escapeHtml).join('<br>') + '</td>'
      + '<td>'
      + (row.derivedFrom.length ? ('派生自 ' + row.derivedFrom.map(shortName).map(escapeHtml).join(', ')) : '')
      + (row.derivedBy.length ? ('<br>派生出 ' + row.derivedBy.map(shortName).map(escapeHtml).join(', ')) : '')
      + '</td>'
      + '<td class="muted">' + escapeHtml(literalText(row.file))
      + (row.line ? (':' + row.line) : '') + '</td></tr>';
  });
  box.innerHTML = html + '</table>';
  bindRowClicks(box);
}

// --- 模式、状态与脚本接口 ----------------------------------------------------------

const MODES = [
  { id: 'relation', label: '元素关系' },
  { id: 'local', label: '局部关系图' },
  { id: 'trace', label: '追溯关系' },
  { id: 'impact', label: '影响范围' },
  { id: 'matrix', label: '需求追溯矩阵' },
];

function layoutForMode() {
  const matrixMode = state.mode === 'matrix';
  document.getElementById('matrix').style.display = matrixMode ? 'block' : 'none';
  document.getElementById('canvas').style.display = matrixMode ? 'none' : 'block';
  document.getElementById('panel').style.display = matrixMode ? 'none' : 'block';
}

function renderModes() {
  const nav = document.getElementById('modes');
  const controls = document.getElementById('controls');
  Array.from(nav.querySelectorAll('button')).forEach(function (button) { button.remove(); });
  MODES.forEach(function (mode) {
    const button = document.createElement('button');
    button.textContent = mode.label;
    button.className = state.mode === mode.id ? 'active' : '';
    button.onclick = function () { run('mode', mode.id); };
    nav.insertBefore(button, controls);
  });
}

function setMode(mode) {
  state.mode = mode;
  renderModes();
  layoutForMode();
  if (mode === 'matrix') { renderMatrix(); paintStatus(); return Promise.resolve(); }
  return render().then(renderPanel);
}

function paintStatus() {
  const element = state.center ? (byRef[state.center] || {}) : null;
  document.getElementById('counts').textContent = (view && state.mode !== 'matrix')
    ? ('节点 ' + view.nodes.length + ' · 边 ' + view.edges.length
       + (state.center ? (' · 中心 ' + labelOf(element)) : ''))
    : ('模型 ' + INDEX.elements.length + ' 元素 / ' + INDEX.relations.length + ' 关系');
  document.getElementById('timings').textContent =
    timings.renderMs === undefined ? '' : ('渲染 ' + Math.round(timings.renderMs) + 'ms');
  document.getElementById('status').textContent = problems.length
    ? ('问题：' + problems[0]) : ('G6 ' + G6.version + ' · 索引由 Java 侧产出');
}

function snapshot() {
  const sub = state.center ? subgraph(state.center, state.depth) : null;
  const hits = state.center ? impact(state.center, state.depth) : [];
  const list = state.center ? neighbors(state.center) : [];
  const byDepth = {};
  hits.forEach(function (hit) { byDepth[hit.depth] = (byDepth[hit.depth] || 0) + 1; });
  return {
    mode: state.mode,
    center: state.center,
    depth: state.depth,
    filters: { hideAttributes: state.hideAttributes, hideContainment: state.hideContainment,
               dim: state.dim },
    counts: {
      modelElements: INDEX.elements.length,
      modelRelations: INDEX.relations.length,
      drawnNodes: view ? view.nodes.length : 0,
      drawnEdges: view ? view.edges.length : 0,
      neighbors: list.length,
      subgraphNodes: sub ? sub.nodes.length : 0,
      subgraphEdges: sub ? sub.edges.length : 0,
      impact: hits.length,
      impactByDepth: byDepth,
    },
    matrix: MATRIX ? { requirements: MATRIX.requirements, satisfied: MATRIX.satisfied,
                       verified: MATRIX.verified, gaps: MATRIX.gaps } : null,
    timings: Object.assign({}, timings),
    problems: problems.slice(0, 6),
  };
}

function execute(name, arg) {
  if (name === 'mode') { return setMode(arg); }
  if (name === 'center') {
    state.center = arg;
    if (state.mode === 'matrix') { return setMode('relation'); }
    renderTree();
    return render().then(renderPanel);
  }
  if (name === 'depth') {
    state.depth = Number(arg);
    syncControls();
    return state.mode === 'matrix' ? Promise.resolve() : render().then(renderPanel);
  }
  if (name === 'filter') {
    Object.assign(state, arg || {});
    syncControls();
    return state.mode === 'matrix' ? Promise.resolve() : render().then(renderPanel);
  }
  if (name === 'row') {
    const row = MATRIX && MATRIX.rows[Number(arg)];
    return row ? execute('center', row.ref) : Promise.resolve();
  }
  return Promise.resolve();
}

function run(name, arg) {
  busy = busy.then(function () { return execute(name, arg); },
                   function () { return execute(name, arg); });
  return busy;
}

function syncControls() {
  document.getElementById('depth').value = String(state.depth);
  document.getElementById('hideAttributes').checked = state.hideAttributes;
  document.getElementById('hideContainment').checked = state.hideContainment;
  document.getElementById('dim').checked = state.dim;
}

function bindControls() {
  document.getElementById('depth').onchange = function (event) { run('depth', event.target.value); };
  document.getElementById('hideAttributes').onchange = function (event) {
    run('filter', { hideAttributes: event.target.checked });
  };
  document.getElementById('hideContainment').onchange = function (event) {
    run('filter', { hideContainment: event.target.checked });
  };
  document.getElementById('dim').onchange = function (event) {
    run('filter', { dim: event.target.checked });
  };
}

function boot() {
  document.getElementById('ws').textContent = DEMO.workspace
    + ' · 索引进 ' + DEMO.indexPath + '，矩阵进 ' + DEMO.matrixPath + '（' + DEMO.generatedAt + '）';
  // 默认中心：优先挑"作者写的需求"（有需求号的），其次任意需求，最后任意非包元素。
  // 不能随便挑一个 RequirementUsage——`objective { verify X; }` 这类编译器生成的包装用法
  // 也是 RequirementUsage（索引里叫 obj），拿它当默认中心会让人一打开就对着一个空壳。
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
  renderTree();
  renderPanel();
  window.__PROTO = { ready: render(), run: run, report: snapshot, state: state };
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
