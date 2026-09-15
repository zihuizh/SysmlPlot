#!/usr/bin/env python3
"""生成 P1 单文件原型页：图库框架的**原生布局 + 交互 + 动画**验证台。

与 P0（`scripts/make-proto-page.py`）的区别：

  P0：preset 布局吃 Java 的 layout.json 坐标，只为比较"能不能表达端口/嵌套"。
  P1：**不吃坐标**，布局、交互、动画全部交给框架；产物只当语义输入。

页面同时是测量台（见 `scripts/measure-proto.py`）：

  window.__PROTO = { ready, report, run(name, arg) }
      run('view', i)                切换视图
      run('layout', 'dagre-lr')     换布局（量重排耗时）
      run('filter', {…})            改筛选（量筛选 + 重排耗时）
      run('search', 'text')         搜索高亮
      run('select', id)             选中
      run('focus', ref)             聚焦（带动画）
      run('animation', false)       关动画（测量用）

用法：

    python scripts/make-proto-page-p1.py --engine g6 \
        --workspace samples/interconnection \
        --views "PowerViews::'power interconnection'" "PowerViews::'power structure'" \
        --lib build/vendor/node_modules/@antv/g6/dist/g6.min.js \
        --out build/proto/g6-p1-small.html
"""

from __future__ import annotations

import argparse
import json
import re
import subprocess
import sys
from pathlib import Path

try:
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
except Exception:
    pass

REPO = Path(__file__).resolve().parent.parent
RUN_VIEW = REPO / "scripts" / "run-view.ps1"


def slug(text: str) -> str:
    return re.sub(r"[^A-Za-z0-9]+", "-", text).strip("-")[:60]


def run_view(workspace: str, view: str, out_dir: Path, reuse: bool = False) -> dict:
    """出产物。P1 不需要 layout.json，但仍生成一份，便于页内做 preset 对照。"""
    out_dir.mkdir(parents=True, exist_ok=True)
    base = out_dir / slug(view)
    product = base.with_suffix(".product.json")
    layout = base.with_suffix(".layout.json")
    if reuse and product.is_file() and layout.is_file():
        return {"ref": view,
                "product": json.loads(product.read_text(encoding="utf-8")),
                "layout": json.loads(layout.read_text(encoding="utf-8"))}
    result = subprocess.run(
        ["powershell", "-NoProfile", "-ExecutionPolicy", "Bypass", "-File", str(RUN_VIEW),
         "-Workspace", workspace, "-View", view, "-Out", str(product), "-EmitLayout", str(layout)],
        capture_output=True, text=True,
    )
    if result.returncode != 0 or not product.is_file():
        raise SystemExit("run-view 失败：%s\n%s" % (view, result.stdout[-800:]))
    return {
        "ref": view,
        "product": json.loads(product.read_text(encoding="utf-8")),
        "layout": json.loads(layout.read_text(encoding="utf-8")),
    }


def build_tree(views: list[dict]) -> list[dict]:
    """按限定名层级拼模型结构树。"""
    nodes: dict[str, dict] = {}
    for view in views:
        for node in view["product"]["nodes"]:
            ref = node.get("ref")
            if not ref:
                continue
            nodes.setdefault(ref, {"ref": ref, "name": node.get("name"),
                                   "metaclass": node.get("metaclass"),
                                   "children": []})
    roots: list[dict] = []
    for ref in sorted(nodes):
        parts = ref.split("::")
        parent = "::".join(parts[:-1])
        if parent and parent in nodes:
            nodes[parent]["children"].append(nodes[ref])
        else:
            roots.append(nodes[ref])
    return roots


PAGE = """<!doctype html>
<html lang="zh-CN">
<head>
<meta charset="utf-8">
<title>__TITLE__</title>
<script>__LIB__</script>
<style>
  :root { --line:#e3e3e3; --accent:#1a73e8; --muted:#777; }
  * { box-sizing:border-box; }
  body { margin:0; font:13px/1.5 "Segoe UI",sans-serif; height:100vh; display:grid;
         grid-template-columns:250px 1fr 320px; grid-template-rows:46px 34px 1fr 26px; }
  header { grid-column:1/4; display:flex; align-items:center; gap:10px; padding:0 12px;
           border-bottom:1px solid var(--line); }
  header .title { font-weight:600; }
  header .views button { margin-right:4px; padding:3px 10px; border:1px solid var(--line);
                         background:#fff; border-radius:4px; cursor:pointer; }
  header .views button.active { border-color:var(--accent); color:var(--accent); }
  header .status { margin-left:auto; color:var(--muted); font-size:12px; }
  #controls { grid-column:1/4; display:flex; align-items:center; gap:14px; padding:0 12px;
              border-bottom:1px solid var(--line); font-size:12px; color:#333; }
  #controls label { display:flex; align-items:center; gap:4px; }
  #controls select, #controls input[type=search] { font:12px inherit; padding:2px 4px; }
  #controls input[type=search] { width:150px; }
  #controls .spacer { margin-left:auto; }
  #tree { border-right:1px solid var(--line); overflow:auto; padding:8px 4px; }
  #tree ul { list-style:none; margin:0; padding-left:12px; }
  #tree .label { cursor:pointer; padding:1px 4px; border-radius:3px; }
  #tree .label:hover { background:#eef2f8; }
  #canvas { position:relative; overflow:hidden; transition:opacity .18s ease; }
  #canvas.fade { opacity:0.25; }
  #inspector { border-left:1px solid var(--line); overflow:auto; padding:8px 10px; }
  #inspector h3 { margin:4px 0; font-size:13px; }
  #inspector table { border-collapse:collapse; width:100%; }
  #inspector td { border-bottom:1px solid #f0f0f0; padding:2px 4px; vertical-align:top; }
  #inspector td:first-child { color:var(--muted); width:34%; }
  footer { grid-column:1/4; border-top:1px solid var(--line); color:var(--muted);
           display:flex; gap:16px; align-items:center; padding:0 12px; font-size:12px; }
  #metrics { display:none; }
  .hint { color:var(--muted); }
</style>
</head>
<body>
<header>
  <span class="title">__TITLE__</span>
  <span class="views" id="views"></span>
  <span class="status" id="status"></span>
</header>
<div id="controls">
  <label>布局 <select id="layout"></select></label>
  <label>边 <select id="edgeMode">
    <option value="all">全部关系</option>
    <option value="connectors">只看连接类</option>
  </select></label>
  <label><input type="checkbox" id="hideImplicit"> 隐藏隐式</label>
  <label><input type="checkbox" id="hideLibrary"> 隐藏库元素</label>
  <label><input type="search" id="search" placeholder="搜索名字/限定名"></label>
  <label><input type="checkbox" id="animation" checked> 动画</label>
  <label><input type="checkbox" id="collapse"> 折叠容器</label>
  <label><input type="checkbox" id="fit" checked> 适配视口</label>
  <span class="spacer"></span>
  <span class="hint" id="hint"></span>
</div>
<nav id="tree"></nav>
<div id="canvas"></div>
<aside id="inspector"><h3>检查器</h3><div id="details">点选一个元素</div></aside>
<footer><span id="engine"></span><span id="counts"></span><span id="timings"></span></footer>
<pre id="metrics"></pre>
<script id="view-data" type="application/json">__DATA__</script>
<script>
__ENGINE_JS__
</script>
</body>
</html>
"""


SHARED_JS = r"""
// ============================================================================
// 共享层：语义产物 → 展示模型（引擎无关），面板、控制和测量都在这一层。
// 引擎层只需要实现 Engine.render(model, options) 等几个方法。
// ============================================================================
const DATA = JSON.parse(document.getElementById('view-data').textContent);
const problems = [];
const timings = {};
const history = [];
const state = {
  viewIndex: 0,
  layout: 'auto',
  edgeMode: 'connectors',   // 默认藏掉 typing/subsetting 这类类型边：大图上它们贡献的交叉最多
  hideImplicit: false,
  hideLibrary: false,
  query: '',
  animation: true,
  fit: true,
};
let model = null;
let busy = Promise.resolve();

window.addEventListener('error', function (event) { problems.push(String(event.message)); paintStatus(); });
window.addEventListener('unhandledrejection', function (event) {
  problems.push('rejection: ' + String(event.reason && event.reason.message || event.reason));
  paintStatus();
});

const TYPING_EDGES = { typing: 1, specialization: 1, subsetting: 1, redefinition: 1 };

function currentView() { return DATA.views[state.viewIndex]; }
function textWidth(text) {
  // 粗略按"半角 7px、全角 14px"估宽——原生布局需要我们给定尺寸，Java 那边的坐标不再参与。
  let width = 0;
  for (const ch of String(text)) { width += ch.charCodeAt(0) > 0x2000 ? 13 : 7.2; }
  return width;
}

function rowsOf(node) {
  // 标签兜底顺序：名字 → 限定名 → 元类。**不拿产物内部的 id 当标签**（n97 这种只在一个产物内有效，
  // 不该出现在人眼前——P0 页面在大图上是这么显示的，P1 改掉）。
  const rows = [node.name || node.ref || ('(' + node.metaclass + ')')];
  (node.compartments || []).forEach(function (compartment) {
    if (!compartment.entries.length) { return; }
    rows.push(compartment.title + ': ' + compartment.entries.map(function (e) { return e.text; }).join(', '));
  });
  return rows;
}

function sizeOf(rows, isPort) {
  if (isPort) { return { w: Math.max(30, Math.min(150, textWidth(rows[0]) + 16)), h: 22 }; }
  let widest = 0;
  rows.forEach(function (row) { widest = Math.max(widest, textWidth(row)); });
  return {
    w: Math.max(96, Math.min(340, widest + 24)),
    h: Math.max(34, 18 + rows.length * 16),
  };
}

/**
 * 适配层：把 view-product 翻成"引擎无关"的展示模型。
 * 这里**不许**出现引擎专有概念（combo、compound、port 坐标…），那些在引擎层映射。
 */
function adapt(product, options) {
  const started = performance.now();
  const byId = {};
  product.nodes.forEach(function (n) { byId[n.id] = n; });

  const blocked = {};
  product.nodes.forEach(function (n) {
    if (options.hideImplicit && n.origin === 'implicit') { blocked[n.id] = true; }
    if (options.hideLibrary && n.origin === 'library') { blocked[n.id] = true; }
  });
  // 祖先被裁掉的后代也裁掉：否则会留下没有附着点的端口和无父的孤儿
  function alive(id) {
    let node = byId[id];
    let guard = 0;
    while (node && guard++ < 64) {
      if (blocked[node.id]) { return false; }
      node = node.parent ? byId[node.parent] : null;
    }
    return true;
  }

  const kept = product.nodes.filter(function (n) { return alive(n.id); });
  const keptIds = {};
  kept.forEach(function (n) { keptIds[n.id] = true; });

  // 包含关系由**嵌套**表达，不再重复画成边；其余关系才进边表
  const containmentEdges = product.relationships.filter(function (r) { return r.kind === 'containment'; }).length;
  const relationships = product.relationships.filter(function (r) {
    if (!keptIds[r.source] || !keptIds[r.target]) { return false; }
    if (r.kind === 'containment') { return false; }
    if (options.edgeMode === 'connectors' && TYPING_EDGES[r.kind]) { return false; }
    return true;
  });

  // 边界元素（端口/有向特征）= 挂在属主边界上的点，不是独立方框
  const ports = {};
  kept.forEach(function (n) {
    if (n.placement !== 'boundary') { return; }
    let owner = n.parent ? byId[n.parent] : null;
    let guard = 0;
    while (owner && owner.placement === 'boundary' && guard++ < 32) {
      owner = owner.parent ? byId[owner.parent] : null;
    }
    if (!owner || !keptIds[owner.id]) { return; }
    ports[n.id] = { id: n.id, ownerId: owner.id, label: n.name || n.ref || n.id, side: null,
                    metaclass: n.metaclass };
  });
  // 端口贴哪一侧：连接类边的端序（语义层的端口方向还没实现，见 docs/BACKLOG.md）
  relationships.forEach(function (r) {
    if (TYPING_EDGES[r.kind]) { return; }
    if (ports[r.source] && !ports[r.source].side) { ports[r.source].side = 'right'; }
    if (ports[r.target] && !ports[r.target].side) { ports[r.target].side = 'left'; }
  });
  Object.keys(ports).forEach(function (id) { ports[id].side = ports[id].side || 'bottom'; });

  const nodes = [];
  kept.forEach(function (n) {
    if (n.placement === 'boundary') { return; }
    const rows = rowsOf(n);
    const children = kept.filter(function (m) { return m.parent === n.id && m.placement !== 'boundary'; });
    const ownPorts = Object.keys(ports).filter(function (id) { return ports[id].ownerId === n.id; });
    nodes.push({
      id: n.id, name: n.name, ref: n.ref, metaclass: n.metaclass, origin: n.origin,
      rows: rows, size: sizeOf(rows, false), compartments: n.compartments || [],
      parent: (n.parent && keptIds[n.parent] && byId[n.parent].placement !== 'boundary') ? n.parent : null,
      isContainer: children.length > 0, childCount: children.length, portCount: ownPorts.length,
    });
  });

  const nodeIds = {};
  nodes.forEach(function (n) { nodeIds[n.id] = true; });

  // 端点解析：端口 → (属主, 端口)；普通节点 → 自身
  function endpoint(id) {
    if (ports[id]) { return { node: ports[id].ownerId, port: id }; }
    if (nodeIds[id]) { return { node: id }; }
    return null;
  }

  // 谁参与关系（端口算在属主头上）
  const edgeEndpoints = {};
  relationships.forEach(function (r) {
    const source = endpoint(r.source);
    const target = endpoint(r.target);
    if (source) { edgeEndpoints[source.node] = true; }
    if (target) { edgeEndpoints[target.node] = true; }
  });

  // 哪些容器用**嵌套**表达。规则（实测逼出来的，见 docs/GRAPH-LIB-P1.md）：
  //   ① 带端口的容器留在节点形态——端口要挂在节点上；
  //   ② 参与关系的容器留在节点形态——G6 的 combo 不能当边端点（真实语料上 `Node not found`）；
  //   ③ 父容器不作嵌套时，子容器也不能嵌套——否则那条包含边会指向一个 combo，同样画不出来。
  // 所以嵌套是一棵**从顶层连续往下**的树。
  const depth = {};
  nodes.forEach(function (n) {
    let level = 0;
    let cursor = n;
    const seen = {};
    while (cursor && cursor.parent && !seen[cursor.id]) {
      seen[cursor.id] = true;
      level++;
      cursor = nodes.filter(function (m) { return m.id === cursor.parent; })[0];
    }
    depth[n.id] = level;
  });
  const nested = {};
  nodes.slice().sort(function (a, b) { return depth[a.id] - depth[b.id]; }).forEach(function (n) {
    if (!n.isContainer || n.portCount > 0 || edgeEndpoints[n.id]) { return; }
    if (n.parent && !nested[n.parent]) { return; }
    nested[n.id] = true;
  });

  const dropped = [];
  const edges = [];
  relationships.forEach(function (r) {
    const source = endpoint(r.source);
    const target = endpoint(r.target);
    if (!source || !target) { dropped.push(r.id + ' ' + r.kind); return; }
    edges.push({ id: r.id, kind: r.kind, label: r.kind, authored: r.authored,
                 source: source.node, sourcePort: source.port,
                 target: target.node, targetPort: target.port });
  });
  // 不作嵌套的容器：包含关系没有别的表达方式，保留包含边，否则子元素失去归属
  product.relationships.forEach(function (r) {
    if (r.kind !== 'containment' || nested[r.source]) { return; }
    if (!keptIds[r.source] || !keptIds[r.target] || !nodeIds[r.source] || !nodeIds[r.target]) { return; }
    edges.push({ id: r.id, kind: r.kind, label: r.kind, authored: r.authored,
                 source: r.source, target: r.target, sourcePort: null, targetPort: null });
  });

  const query = (options.query || '').trim().toLowerCase();
  const matched = {};
  if (query) {
    nodes.forEach(function (n) {
      const hay = ((n.name || '') + ' ' + (n.ref || '')).toLowerCase();
      if (hay.indexOf(query) >= 0) { matched[n.id] = true; }
    });
  }

  const adapterMs = performance.now() - started;
  return {
    nodes: nodes, edges: edges, ports: ports, matched: matched, dropped: dropped, nested: nested,
    stats: {
      nodes: nodes.length, edges: edges.length, containers: nodes.filter(function (n) { return n.isContainer; }).length,
      nested: Object.keys(nested).length,
      ports: Object.keys(ports).length, droppedEdges: dropped.length, matched: Object.keys(matched).length,
      containmentEdges: containmentEdges,
      hiddenByFilter: Object.keys(blocked).length,
      totalNodes: product.nodes.length,
    },
    adapterMs: adapterMs,
  };
}

// --- 面板 ---------------------------------------------------------------------

function renderViews() {
  const box = document.getElementById('views');
  box.innerHTML = '';
  DATA.views.forEach(function (view, index) {
    const button = document.createElement('button');
    button.textContent = view.ref.split('::').slice(-1)[0].replace(/'/g, '');
    button.className = index === state.viewIndex ? 'active' : '';
    button.onclick = function () { run('view', index); };
    box.appendChild(button);
  });
}

function renderTree() {
  const box = document.getElementById('tree');
  box.innerHTML = '';
  const list = document.createElement('ul');
  DATA.tree.forEach(function (item) { list.appendChild(treeItem(item)); });
  box.appendChild(list);
}

function treeItem(node) {
  const li = document.createElement('li');
  const span = document.createElement('span');
  span.className = 'label';
  span.textContent = node.name || node.ref.split('::').slice(-1)[0];
  span.title = node.ref;
  span.onclick = function () { run('focus', node.ref); };
  li.appendChild(span);
  if (node.children && node.children.length) {
    const kids = document.createElement('ul');
    node.children.forEach(function (child) { kids.appendChild(treeItem(child)); });
    li.appendChild(kids);
  }
  return li;
}

function showDetails(id) {
  const box = document.getElementById('details');
  const node = model && model.nodes.filter(function (n) { return n.id === id; })[0];
  if (!node) { box.textContent = '点选一个元素'; return; }
  const related = model.edges.filter(function (e) { return e.source === id || e.target === id; });
  const rows = [
    ['名字', node.name || '(anonymous)'],
    ['限定名', node.ref || '—'],
    ['元类', node.metaclass],
    ['来源', node.origin],
    ['结构', node.isContainer ? ('容器 · ' + node.childCount + ' 个子元素 · ' + node.portCount + ' 个端口')
                              : (node.portCount ? (node.portCount + ' 个端口') : '普通节点')],
    ['仓格', node.compartments.map(function (c) { return c.title; }).join(', ') || '—'],
  ];
  let html = '<h3>' + escapeHtml(node.name || node.ref || id) + '</h3><table>';
  rows.forEach(function (row) {
    html += '<tr><td>' + row[0] + '</td><td>' + escapeHtml(String(row[1])) + '</td></tr>';
  });
  html += '</table><h3>关系（' + related.length + '）</h3><table>';
  related.slice(0, 40).forEach(function (r) {
    const otherId = r.source === id ? r.target : r.source;
    const other = model.nodes.filter(function (n) { return n.id === otherId; })[0] || {};
    html += '<tr><td>' + r.kind + '</td><td>'
      + (r.source === id ? '→ ' : '← ') + escapeHtml(other.name || other.ref || otherId) + '</td></tr>';
  });
  html += '</table>';
  box.innerHTML = html;
}

function escapeHtml(text) {
  return String(text).replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;');
}

function paintStatus() {
  if (!model) { return; }
  const stats = model.stats;
  document.getElementById('counts').textContent =
    '节点 ' + stats.nodes + ' · 容器 ' + stats.containers + ' · 端口 ' + stats.ports
    + ' · 边 ' + stats.edges + (stats.matched ? (' · 命中 ' + stats.matched) : '');
  const parts = [];
  if (timings.adapterMs !== undefined) { parts.push('适配 ' + Math.round(timings.adapterMs) + 'ms'); }
  if (timings.renderMs !== undefined) { parts.push('建图 ' + Math.round(timings.renderMs) + 'ms'); }
  if (timings.firstFrameMs !== undefined) { parts.push('首帧 ' + Math.round(timings.firstFrameMs) + 'ms'); }
  if (timings.relayoutMs !== undefined) { parts.push('重排 ' + Math.round(timings.relayoutMs) + 'ms'); }
  if (timings.filterMs !== undefined) { parts.push('筛选重排 ' + Math.round(timings.filterMs) + 'ms'); }
  if (timings.searchMs !== undefined) { parts.push('搜索 ' + Math.round(timings.searchMs) + 'ms'); }
  if (timings.heapMB !== undefined) { parts.push('堆 ' + timings.heapMB + 'MB'); }
  document.getElementById('timings').textContent = parts.join(' · ');
  const dropped = stats.droppedEdges ? (' · 未画边 ' + stats.droppedEdges) : '';
  document.getElementById('status').textContent =
    (problems.length ? ('问题：' + problems[0]) : (Engine.name + ' · ' + currentView().ref + dropped));
}

// --- 指标与脚本接口 -------------------------------------------------------------

function nextFrame() {
  return new Promise(function (resolve) { requestAnimationFrame(function () { resolve(); }); });
}

function memoryMB() {
  if (!performance.memory) { return null; }
  return Math.round(performance.memory.usedJSHeapSize / 1048576 * 10) / 10;
}

function snapshot() {
  return {
    engine: Engine.name,
    view: currentView().ref,
    viewKind: currentView().product.view.kind,
    layout: Engine.currentLayout ? Engine.currentLayout() : state.layout,
    edgeMode: state.edgeMode,
    hideImplicit: state.hideImplicit,
    hideLibrary: state.hideLibrary,
    animation: state.animation,
    query: state.query,
    stats: model ? model.stats : null,
    timings: Object.assign({}, timings),
    viewport: Engine.viewport ? Engine.viewport() : null,
    layoutTimeouts: Engine.timeoutCount ? Engine.timeoutCount() : 0,
    usedHeapMB: memoryMB(),
    history: history.slice(-40),
    problems: problems.slice(0, 8),
  };
}

function draw() {
  model = adapt(currentView().product, state);
  timings.adapterMs = model.adapterMs;
  delete timings.relayoutMs;
  delete timings.firstFrameMs;
  const started = performance.now();
  return withTimeout(Engine.render(model, state), 20000, 'render').then(function () {
    timings.renderMs = performance.now() - started;
    return nextFrame();
  }).then(function () {
    timings.firstFrameMs = performance.now() - started;
    timings.heapMB = memoryMB();
    paintStatus();
    document.getElementById('metrics').textContent = JSON.stringify(snapshot());
    return snapshot();
  });
}

/**
 * 兜底：引擎的布局/动画 promise 可能不结算（实测大图上会挂住）。宁可给一个"超时"结论，
 * 也不要让测量台和 `window.__PROTO.ready` 永远悬着。
 */
function withTimeout(promise, ms, label) {
  return new Promise(function (resolve) {
    let settled = false;
    const timer = setTimeout(function () {
      if (!settled) {
        settled = true;
        problems.push(label + ' 超时 ' + ms + 'ms（布局或动画没结算）');
        resolve('timeout');
      }
    }, ms);
    Promise.resolve(promise).then(function (value) {
      if (!settled) { settled = true; clearTimeout(timer); resolve(value); }
    }, function (error) {
      if (!settled) {
        settled = true;
        clearTimeout(timer);
        problems.push(label + ' 失败: ' + (error && error.message));
        resolve('error');
      }
    });
  });
}

function enqueue(task) {
  busy = busy.then(task, task);
  return busy;
}

function run(name, arg) {
  return enqueue(function () {
    return execute(name, arg).then(function (result) {
      history.push({ step: name, arg: arg, timings: Object.assign({}, timings),
                     stats: model ? Object.assign({}, model.stats) : null });
      return result;
    });
  });
}

function execute(name, arg) {
  return (function () {
    if (name === 'view') {
      state.viewIndex = typeof arg === 'number' ? arg
        : Math.max(0, DATA.views.findIndex(function (v) { return v.ref === arg; }));
      renderViews();
      document.getElementById('canvas').classList.add('fade');
      return draw().then(function () {
        return nextFrame().then(function () { document.getElementById('canvas').classList.remove('fade'); });
      });
    }
    if (name === 'layout') {
      state.layout = arg;
      // preset 要吃 Java 坐标，必须重建（数据里才有坐标）
      if (arg === 'preset' || !Engine.relayout) {
        const started = performance.now();
        return draw().then(function () {
          timings.relayoutMs = performance.now() - started;
          paintStatus();
          return snapshot();
        });
      }
      return Engine.relayout ? Engine.relayout(state).then(function (ms) {
        timings.relayoutMs = ms;
        paintStatus();
        return snapshot();
      }) : Promise.resolve(snapshot());
    }
    if (name === 'filter') {
      Object.assign(state, arg || {});
      syncControls();
      const started = performance.now();
      return draw().then(function () {
        timings.filterMs = performance.now() - started;
        paintStatus();
        return snapshot();
      });
    }
    if (name === 'search') {
      state.query = arg || '';
      syncControls();
      const started = performance.now();
      model = adapt(currentView().product, state);
      return Promise.resolve(Engine.highlight(model)).then(function () {
        timings.searchMs = performance.now() - started;
        paintStatus();
        return snapshot();
      });
    }
    if (name === 'select') { return Promise.resolve(Engine.select(arg)); }
    if (name === 'focus') { return Promise.resolve(Engine.focus(arg)); }
    if (name === 'collapse') {
      const started = performance.now();
      return Promise.resolve(Engine.collapseAll ? Engine.collapseAll(!!arg) : null).then(function () {
        timings.collapseMs = performance.now() - started;
        paintStatus();
        return snapshot();
      });
    }
    if (name === 'fit') {
      state.fit = !!arg;
      syncControls();
      return Promise.resolve(Engine.setFit ? Engine.setFit(state.fit) : null).then(function () {
        paintStatus();
        return snapshot();
      });
    }
    if (name === 'animation') { state.animation = !!arg; syncControls(); return Promise.resolve(snapshot()); }
    return Promise.resolve(null);
  })();
}

function syncControls() {
  document.getElementById('edgeMode').value = state.edgeMode;
  document.getElementById('hideImplicit').checked = state.hideImplicit;
  document.getElementById('hideLibrary').checked = state.hideLibrary;
  document.getElementById('search').value = state.query;
  document.getElementById('animation').checked = state.animation;
  document.getElementById('collapse').checked = !!state.collapsed;
  document.getElementById('fit').checked = state.fit;
  const layoutSelect = document.getElementById('layout');
  layoutSelect.innerHTML = '';
  Engine.layouts().forEach(function (option) {
    const element = document.createElement('option');
    element.value = option.id;
    element.textContent = option.label;
    layoutSelect.appendChild(element);
  });
  layoutSelect.value = state.layout;
}

function bindControls() {
  document.getElementById('layout').onchange = function (event) { run('layout', event.target.value); };
  document.getElementById('edgeMode').onchange = function (event) { run('filter', { edgeMode: event.target.value }); };
  document.getElementById('hideImplicit').onchange = function (event) { run('filter', { hideImplicit: event.target.checked }); };
  document.getElementById('hideLibrary').onchange = function (event) { run('filter', { hideLibrary: event.target.checked }); };
  document.getElementById('animation').onchange = function (event) { run('animation', event.target.checked); };
  document.getElementById('collapse').onchange = function (event) {
    state.collapsed = event.target.checked;
    run('collapse', event.target.checked);
  };
  document.getElementById('fit').onchange = function (event) { run('fit', event.target.checked); };
  document.getElementById('search').oninput = function (event) { run('search', event.target.value); };
}

function boot() {
  document.getElementById('engine').textContent = Engine.name;
  const product = currentView().product;
  state.layout = Engine.defaultLayout(product.view.kind);
  // 大图默认关动画：动画在大图上既慢又干扰阅读（实测 221 节点上 render 一直不结算）
  state.animation = product.nodes.length <= 120;
  // 大图默认不适配视口：168 节点适配到一屏后缩放只有 0.06，标签实际 0.7px，等于不可读
  state.fit = product.nodes.length <= 120;
  renderViews();
  renderTree();
  syncControls();
  bindControls();
  const ready = draw();
  window.__PROTO = {
    ready: ready,
    run: run,
    report: snapshot,
    state: state,
  };
}
"""


G6_JS = r"""
// ============================================================================
// G6 引擎层：combo 祖容器 / 原生端口 / 内置 html 节点（仓格）/ 原生布局。
// ============================================================================
const G6NS = window.G6 || window.g6;

const G6_LAYOUTS = [
  { id: 'dagre-tb', label: 'dagre 自上而下' },
  { id: 'dagre-lr', label: 'dagre 自左而右' },
  { id: 'tree-compact', label: '树布局 compact-box（层级用 children）' },
  { id: 'tree-indented', label: '树布局 indented（层级缩进）' },
  { id: 'combo-combined', label: 'combo-combined（容器内外两层）' },
  { id: 'force', label: '力导向 d3-force' },
  { id: 'concentric', label: '同心圆 concentric' },
  { id: 'radial', label: '径向 radial' },
  { id: 'grid', label: '网格 grid' },
  { id: 'preset', label: 'Java 布局（对照，读 layout.json）' },
];

let graph = null;
let container = null;
let elementIds = {};
let comboList = [];
const TREE_LAYOUTS = { 'tree-compact': 1, 'tree-indented': 1 };
function isTreeLayout(id) { return !!TREE_LAYOUTS[id]; }

function comboKey(id) { return 'combo-' + id; }

function layoutOptions(id, state) {
  switch (id) {
    case 'dagre-lr': return { type: 'antv-dagre', rankdir: 'LR', sortByCombo: true, nodesep: 18, ranksep: 70 };
    case 'dagre-tb': return { type: 'antv-dagre', rankdir: 'TB', sortByCombo: true, nodesep: 30, ranksep: 60 };
    // 树布局直接吃 children：层级不再靠 combo/包含边表达，也就绕开了"combo 不能当边端点"的限制
    case 'tree-compact': return {
      type: 'compact-box', direction: 'TB',
      getWidth: function (d) { return (d.style && d.style.size ? d.style.size[0] : 150); },
      getHeight: function (d) { return (d.style && d.style.size ? d.style.size[1] : 30); },
      getVGap: function () { return 10; }, getHGap: function () { return 24; },
    };
    case 'tree-indented': return {
      type: 'indented', direction: 'LR', indent: 24,
      getWidth: function (d) { return (d.style && d.style.size ? d.style.size[0] : 150); },
      getHeight: function (d) { return (d.style && d.style.size ? d.style.size[1] : 30); },
    };
    case 'combo-combined': return {
      type: 'combo-combined',
      comboLayout: [{ type: 'antv-dagre', rankdir: 'TB' }],
      layout: { type: 'antv-dagre', rankdir: 'TB' },
    };
    case 'force': return { type: 'd3-force', link: { distance: 90 }, manyBody: { strength: -220 } };
    case 'concentric': return { type: 'concentric', nodeSize: 60 };
    case 'radial': return { type: 'radial', unitRadius: 130, preventOverlap: true, nodeSize: 70 };
    case 'grid': return { type: 'grid' };
    case 'preset': return { type: 'preset' };
    default: return { type: 'antv-dagre', rankdir: 'TB', sortByCombo: true };
  }
}

function isPreset(state) { return state.layout === 'preset'; }

function comboAncestorOf(node, comboIds, byId) {
  let parentId = node.parent;
  let guard = 0;
  while (parentId && guard++ < 32) {
    if (comboIds[parentId]) { return parentId; }
    const parent = byId[parentId];
    parentId = parent ? parent.parent : null;
  }
  return null;
}

/** 展示模型 → G6 数据。容器用 combo；带仓格的节点用内置 html 节点；端口挂在属主上。 */
function toG6Data(model, state, javaLayout) {
  const tree = isTreeLayout(state.layout);
  // 树布局用 children 表达层级，不出 combo；其余布局用适配层定好的嵌套集合
  const comboIds = tree ? {} : model.nested;
  const byId = {};
  model.nodes.forEach(function (n) { byId[n.id] = n; });

  const combos = [];
  model.nodes.forEach(function (n) {
    if (!comboIds[n.id]) { return; }
    const combo = { id: comboKey(n.id), style: { label: n.rows[0] } };
    const ancestor = comboAncestorOf(n, comboIds, byId);
    if (ancestor) { combo.combo = comboKey(ancestor); }
    if (isPreset(state) && javaLayout && javaLayout.nodes[n.id]) {
      const box = javaLayout.nodes[n.id];
      Object.assign(combo.style, { x: box.x + box.width / 2, y: box.y + box.height / 2,
                                   width: box.width, height: box.height });
    }
    combos.push(combo);
  });

  const nodes = [];
  model.nodes.forEach(function (n) {
    if (comboIds[n.id]) { return; }   // 容器由 combo 表达，不再重复画一个节点（否则重影）
    const ports = Object.keys(model.ports)
      .filter(function (id) { return model.ports[id].ownerId === n.id; })
      .map(function (id) {
        return { key: id, placement: model.ports[id].side, r: 5, fill: '#eef2f8', stroke: '#567' };
      });
    const ancestor = comboAncestorOf(n, comboIds, byId);
    const node = {
      id: n.id,
      combo: ancestor ? comboKey(ancestor) : undefined,
      data: { ref: n.ref, name: n.name },
      style: { size: [n.size.w, n.size.h], label: n.rows[0], ports: ports },
    };
    if (tree) {
      node.data.children = model.nodes
        .filter(function (m) { return m.parent === n.id; })
        .map(function (m) { return m.id; });
    }
    if (n.compartments.length) {
      // 仓格：内置 html 节点（实测可用，不需要扩展包）。多行文本折行由 HTML 自己做。
      node.type = 'html';
      node.style.innerHTML = compartmentHtml(n);
    }
    if (isPreset(state) && javaLayout && javaLayout.nodes[n.id]) {
      const box = javaLayout.nodes[n.id];
      node.style.x = box.x + box.width / 2;
      node.style.y = box.y + box.height / 2;
    }
    nodes.push(node);
  });

  const edges = model.edges.filter(function (e) {
    return !(tree && e.kind === 'containment');   // 树已经表达了包含关系
  }).map(function (e) {
    return {
      id: e.id, source: e.source, target: e.target,
      style: { label: e.label, sourcePort: e.sourcePort, targetPort: e.targetPort },
    };
  });
  return { nodes: nodes, edges: edges, combos: combos };
}

function compartmentHtml(node) {
  let html = '<div style="box-sizing:border-box;width:100%;height:100%;border:1px solid #444;'
    + 'border-radius:4px;background:#fff;font:11px/1.35 \'Segoe UI\',sans-serif;overflow:hidden">'
    + '<div style="padding:2px 6px;font-weight:600">' + escapeHtml(node.rows[0]) + '</div>';
  node.compartments.forEach(function (compartment) {
    if (!compartment.entries.length) { return; }
    html += '<div style="border-top:1px solid #e6e6e6;padding:1px 6px;color:#555">'
      + '<span style="color:#888">' + escapeHtml(compartment.title) + '</span>: '
      + compartment.entries.slice(0, 4).map(function (entry) { return escapeHtml(entry.text); }).join(', ')
      + (compartment.entries.length > 4 ? ' …' : '') + '</div>';
  });
  return html + '</div>';
}

const Engine = {
  name: 'G6 ' + (G6NS.version || '?'),
  layouts: function () { return G6_LAYOUTS; },
  defaultLayout: function (kind) {
    if (kind === 'general' || kind === 'browser' || kind === 'grid') { return 'dagre-tb'; }
    return 'dagre-lr';
  },
  currentLayout: function () { return state.layout; },
  render: function (model, options) {
    container = document.getElementById('canvas');
    if (graph) { try { graph.destroy(); } catch (error) { problems.push('destroy: ' + error.message); } graph = null; }
    container.innerHTML = '';
    const javaLayout = isPreset(options) ? currentView().layout : null;
    const data = toG6Data(model, options, javaLayout);
    comboList = data.combos;
    elementIds = {};
    data.nodes.forEach(function (n) { elementIds[n.id] = true; });
    data.combos.forEach(function (c) { elementIds[c.id] = true; });
    graph = new G6NS.Graph({
      container: container,
      autoFit: options.fit ? 'view' : false,
      padding: 24,
      animation: options.animation,
      data: data,
      node: {
        type: 'rect',
        style: {
          size: function (d) { return d.style.size; },
          radius: 4, fill: '#fff', stroke: '#444', lineWidth: 1.2,
          labelText: function (d) { return d.style.label; },
          labelPlacement: 'top', labelFill: '#333', labelFontSize: 11,
          ports: function (d) { return d.style.ports || []; },
        },
        state: {
          matched: { stroke: '#e8a33d', lineWidth: 3 },
          selected: { stroke: '#1a73e8', lineWidth: 2.5 },
        },
      },
      combo: {
        type: 'rect',
        style: {
          fill: '#f7f9fc', stroke: '#9bb', lineWidth: 1, radius: 6,
          labelText: function (d) { return d.style.label; }, labelPlacement: 'top',
        },
      },
      edge: {
        type: 'polyline',
        style: {
          stroke: '#889', lineWidth: 1.1, endArrow: true,
          labelText: function (d) { return d.style.label || ''; }, labelFontSize: 10,
          labelBackground: true, labelBackgroundFill: '#fff',
        },
      },
      layout: layoutOptions(options.layout, options),
      behaviors: ['zoom-canvas', 'drag-canvas', 'drag-element', 'click-select', 'hover-activate', 'focus-element'],
    });
    graph.on('node:click', function (event) { showDetails(event.target.id); });
    graph.on('canvas:click', function () { showDetails(null); });
    graph.on('node:dblclick', function (event) { Engine.focus(event.target.id); });
    return Promise.resolve(graph.render()).then(function () {
      if (!options.fit) { Engine.setFit(false); }
    });
  },
  relayout: function (options) {
    if (!graph) { return Promise.resolve(0); }
    const started = performance.now();
    graph.setOptions({ layout: layoutOptions(options.layout, options) });
    const result = graph.layout();
    return Promise.resolve(result).then(function () {
      // 排布换了以后重新适配视口：否则缩放比还是上一次的，量不出新布局的紧凑度
      try { if (typeof graph.fitView === 'function') { graph.fitView(); } } catch (error) { /* 忽略 */ }
      return nextFrame();
    }).then(function () { return performance.now() - started; });
  },
  highlight: function (model) {
    if (!graph) { return; }
    // 逐节点调用 setElementState 会每个节点触发一次重绘：实测 104 节点要 1.7 秒。
    // G6 支持一次传一张 id→states 的表，用批量形式。
    const states = {};
    model.nodes.forEach(function (n) {
      if (elementIds[n.id]) { states[n.id] = model.matched[n.id] ? ['matched'] : []; }
    });
    return graph.setElementState(states);
  },
  select: function (id) {
    if (!graph || !id || !elementIds[id]) { return; }
    try { graph.setElementState(id, ['selected']); } catch (error) { problems.push('select: ' + error.message); }
    showDetails(id);
  },
  focus: function (ref) {
    if (!graph) { return; }
    const node = currentView().product.nodes.filter(function (n) { return n.ref === ref; })[0];
    if (!node || !elementIds[node.id]) { return; }
    showDetails(node.id);
    try {
      if (typeof graph.focusElement === 'function') {
        const result = graph.focusElement(node.id);
        if (result && result.then) { return result; }
      }
    } catch (error) { problems.push('focus: ' + error.message); }
  },
  /** 折叠顶层容器：大图上"先看骨架、再展开"的第一步。 */
  collapseAll: function (flag) {
    if (!graph) { return Promise.resolve(); }
    const method = flag ? 'collapseElement' : 'expandElement';
    if (typeof graph[method] !== 'function') { problems.push('G6 没有 ' + method); return Promise.resolve(); }
    const targets = comboList.filter(function (c) { return !c.combo; });
    return Promise.all(targets.map(function (c) {
      try { return Promise.resolve(graph[method](c.id)); } catch (error) { problems.push(method + ': ' + error.message); return null; }
    })).then(function () {
      return nextFrame();
    });
  },
  viewport: function () {
    if (!graph) { return null; }
    const size = graph.getSize ? graph.getSize() : [0, 0];
    return {
      zoom: Math.round((graph.getZoom() || 1) * 1000) / 1000,
      canvas: [Math.round(size[0]), Math.round(size[1])],
      nodeLabelPx: Math.round(11 * (graph.getZoom() || 1) * 10) / 10,
    };
  },
  setFit: function (flag) {
    if (!graph) { return; }
    if (flag) {
      if (typeof graph.fitView === 'function') { graph.fitView(); }
    } else {
      if (typeof graph.zoomTo === 'function') { graph.zoomTo(1); }
      if (typeof graph.fitCenter === 'function') { graph.fitCenter(); }
    }
    return nextFrame();
  },
};

boot();
"""


CYTOSCAPE_JS = r"""
// ============================================================================
// Cytoscape 引擎层：compound 表达容器、子节点模拟端口、多行标签表达仓格。
// ============================================================================
const CYTO_LAYOUTS = [
  { id: 'breadthfirst', label: 'breadthfirst（层级，自上而下）' },
  { id: 'cose', label: 'cose（力导向，认 compound）' },
  { id: 'concentric', label: '同心圆 concentric' },
  { id: 'grid', label: '网格 grid' },
  { id: 'circle', label: '环形 circle' },
  { id: 'preset', label: 'Java 布局（对照，读 layout.json）' },
];

let cy = null;
let timeouts = 0;

function cyLayoutOptions(id) {
  switch (id) {
    case 'breadthfirst': return { name: 'breadthfirst', directed: true, spacingFactor: 1.15, animate: false };
    case 'cose': return { name: 'cose', animate: false, nodeRepulsion: 20000, idealEdgeLength: 90,
                          nestingFactor: 0.15, numIter: 800 };
    case 'concentric': return { name: 'concentric', animate: false, minNodeSpacing: 20 };
    case 'grid': return { name: 'grid', animate: false, avoidOverlap: true, condense: false };
    case 'circle': return { name: 'circle', animate: false };
    case 'preset': return { name: 'preset' };
    default: return { name: 'breadthfirst', directed: true, animate: false };
  }
}

/** 展示模型 → Cytoscape 数据。容器用 compound（父框位置由子节点算出）。 */
function toCytoElements(model, state, javaLayout) {
  const preset = state.layout === 'preset';
  const byId = {};
  model.nodes.forEach(function (n) { byId[n.id] = n; });
  // 与 G6 侧共用同一套规则（适配层的 model.nested）：带端口或参与关系的容器留在节点形态
  const containerIds = model.nested;
  function ancestorOf(node) {
    let parentId = node.parent;
    let guard = 0;
    while (parentId && guard++ < 32) {
      if (containerIds[parentId]) { return parentId; }
      const parent = byId[parentId];
      parentId = parent ? parent.parent : null;
    }
    return null;
  }

  const elements = [];
  // 父节点必须先入：否则 Cytoscape 会为缺失的父自动补一个空壳
  const ordered = model.nodes.slice().sort(function (a, b) {
    return (a.parent ? 1 : 0) - (b.parent ? 1 : 0);
  });
  ordered.forEach(function (n) {
    const text = [n.rows[0]].concat(n.compartments.map(function (c) {
      return c.entries.length ? (c.title + ': ' + c.entries.slice(0, 3).map(function (e) { return e.text; }).join(', ')) : null;
    }).filter(Boolean)).join('\n');
    const data = {
      id: n.id, label: text, ref: n.ref || '', name: n.name || '',
      w: n.size.w, h: n.size.h, rows: n.rows.length, origin: n.origin,
    };
    if (!preset && containerIds[n.id]) { data.isParent = true; }
    // preset 对照不用 compound：Cytoscape 的父框位置由子节点算，吃不了 Java 坐标（P0 实测）
    const ancestor = ancestorOf(n);
    if (!preset && ancestor) { data.parent = ancestor; }
    const element = { data: data };
    if (preset && javaLayout && javaLayout.nodes[n.id]) {
      const box = javaLayout.nodes[n.id];
      element.position = { x: box.x + box.width / 2, y: box.y + box.height / 2 };
    }
    elements.push(element);
  });

  Object.keys(model.ports).forEach(function (id) {
    const port = model.ports[id];
    // 端口在 Cytoscape 里只能拿子节点模拟：尺寸压到最小、不放标签（否则大图上就是一片圈和字）
    const data = { id: id, label: '', w: 12, h: 12, ref: '', isPort: true };
    if (!preset) { data.parent = port.ownerId; }
    const element = { data: data, classes: 'port' };
    if (preset && javaLayout && javaLayout.nodes[id]) {
      const box = javaLayout.nodes[id];
      element.position = { x: box.x + box.width / 2, y: box.y + box.height / 2 };
    }
    elements.push(element);
  });

  model.edges.forEach(function (e) {
    elements.push({ data: { id: e.id, source: e.sourcePort || e.source, target: e.targetPort || e.target,
                            label: e.label, kind: e.kind } });
  });
  return elements;
}

const Engine = {
  name: 'Cytoscape ' + cytoscape.version,
  layouts: function () { return CYTO_LAYOUTS; },
  timeoutCount: function () { return timeouts; },
  defaultLayout: function (kind) {
    return (kind === 'general' || kind === 'browser' || kind === 'grid') ? 'breadthfirst' : 'cose';
  },
  currentLayout: function () { return state.layout; },
  render: function (model, options) {
    const host = document.getElementById('canvas');
    host.innerHTML = '';
    const preset = options.layout === 'preset';
    const javaLayout = preset ? currentView().layout : null;
    if (cy) { try { cy.destroy(); } catch (error) { problems.push('destroy: ' + error.message); } cy = null; }
    cy = cytoscape({
      container: host,
      elements: toCytoElements(model, options, javaLayout),
      style: [
        { selector: 'node', style: {
          'shape': 'round-rectangle', 'background-color': '#fff', 'border-color': '#444',
          'border-width': 1.2, 'label': 'data(label)', 'font-size': 10, 'color': '#333',
          'text-valign': 'center', 'text-halign': 'center', 'text-wrap': 'wrap',
          'text-max-width': 'data(w)', 'width': 'data(w)', 'height': 'data(h)' } },
        { selector: 'node[?isParent]', style: {
          'background-opacity': 0.06, 'border-color': '#9bb', 'border-width': 1,
          'label': 'data(label)', 'text-valign': 'top', 'text-margin-y': 2 } },
        { selector: 'node.port', style: {
          'shape': 'ellipse', 'background-color': '#eef2f8', 'border-color': '#567',
          'width': 12, 'height': 12, 'font-size': 0 } },
        { selector: 'node.matched', style: { 'border-color': '#e8a33d', 'border-width': 3 } },
        { selector: 'node:selected', style: { 'border-color': '#1a73e8', 'border-width': 2.5 } },
        { selector: 'edge', style: {
          'curve-style': 'bezier', 'width': 1.1, 'line-color': '#889',
          'target-arrow-color': '#889', 'target-arrow-shape': 'triangle',
          'font-size': 9, 'color': '#666', 'label': 'data(label)',
          'text-background-color': '#fff', 'text-background-opacity': 1,
          'text-rotation': 'autorotate' } },
      ],
      // 不在构造里跑布局：那样 layoutstop 会在监听器挂上之前就派发，只能靠超时兜底（实测把 3 个节点
      // 的建图时间虚报成 410ms）。统一走下面的 Engine.relayout()。
      layout: { name: 'preset' },
      wheelSensitivity: 0.2,
    });
    cy.on('tap', 'node', function (event) { showDetails(event.target.id()); });
    cy.on('tap', function (event) { if (event.target === cy) { showDetails(null); } });
    cy.on('dbltap', 'node', function (event) { Engine.focus(event.target.id()); });
    return Engine.relayout(options).then(function () {
      Engine.setFit(options.fit);
    });
  },
  relayout: function (options) {
    if (!cy) { return Promise.resolve(0); }
    const started = performance.now();
    return new Promise(function (resolve) {
      let settled = false;
      function finish() { if (!settled) { settled = true; resolve(performance.now() - started); } }
      const layout = cy.layout(cyLayoutOptions(options.layout));
      layout.one('layoutstop', finish);
      setTimeout(function () {
        if (!settled) { timeouts++; finish(); }
      }, 2000);
      layout.run();
    }).then(function (ms) {
      cy.fit(undefined, 30);
      return ms;
    });
  },
  highlight: function (model) {
    if (!cy) { return; }
    cy.nodes().forEach(function (node) {
      if (model.matched[node.id()]) { node.addClass('matched'); } else { node.removeClass('matched'); }
    });
  },
  select: function (id) {
    if (!cy || !id) { return; }
    const node = cy.$id(id);
    if (node && node.length) { node.select(); showDetails(id); }
  },
  focus: function (ref) {
    if (!cy) { return Promise.resolve(); }
    const node = currentView().product.nodes.filter(function (n) { return n.ref === ref; })[0];
    if (!node) { return Promise.resolve(); }
    showDetails(node.id);
    const target = cy.$id(node.id);
    if (!target || !target.length) { return Promise.resolve(); }
    return new Promise(function (resolve) {
      cy.animate({ center: { eles: target }, zoom: 1.1 }, { duration: 250, complete: resolve });
    });
  },
  /** Cytoscape 侧折叠：复合父节点的后代整体隐藏（它没有"折叠"这个概念，只有显示/隐藏）。 */
  collapseAll: function (flag) {
    if (!cy) { return Promise.resolve(); }
    cy.nodes('[?isParent]').forEach(function (parent) {
      const descendants = parent.descendants();
      descendants.style('display', flag ? 'none' : 'element');
    });
    return Promise.resolve();
  },
  viewport: function () {
    if (!cy) { return null; }
    return {
      zoom: Math.round(cy.zoom() * 1000) / 1000,
      canvas: [Math.round(cy.width()), Math.round(cy.height())],
      nodeLabelPx: Math.round(10 * cy.zoom() * 10) / 10,
    };
  },
  setFit: function (flag) {
    if (!cy) { return; }
    if (flag) { cy.fit(undefined, 30); } else { cy.zoom(1); cy.center(); }
  },
};

boot();
"""


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--engine", choices=["g6", "cytoscape"], required=True)
    parser.add_argument("--workspace", required=True)
    parser.add_argument("--views", nargs="+", required=True)
    parser.add_argument("--lib", type=Path, required=True)
    parser.add_argument("--out", type=Path, required=True)
    parser.add_argument("--data-dir", type=Path, default=REPO / "build" / "proto" / "data-p1")
    parser.add_argument("--reuse", action="store_true", help="复用 data-dir 里已有的产物，不再跑 run-view")
    args = parser.parse_args()

    views = [run_view(args.workspace, view, args.data_dir, args.reuse) for view in args.views]
    tree = build_tree(views)
    data = {"views": views, "tree": tree,
            "workspace": args.workspace, "generatedBy": "scripts/make-proto-page-p1.py"}

    lib = args.lib.read_text(encoding="utf-8")
    engine_js = G6_JS if args.engine == "g6" else CYTOSCAPE_JS
    title = "%s P1 原型 · %s" % (args.engine, args.workspace)
    html = (PAGE
            .replace("__TITLE__", title)
            .replace("__LIB__", lib)
            .replace("__DATA__", json.dumps(data, ensure_ascii=False))
            .replace("__ENGINE_JS__", SHARED_JS + "\n" + engine_js))
    args.out.parent.mkdir(parents=True, exist_ok=True)
    args.out.write_text(html, encoding="utf-8")
    print("[proto-p1] %s -> %s (%.1f KB，含内联库 %.1f KB)" % (
        args.engine, args.out, args.out.stat().st_size / 1024, args.lib.stat().st_size / 1024))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
