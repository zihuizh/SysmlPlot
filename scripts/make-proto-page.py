#!/usr/bin/env python3
"""生成"单文件模型页"原型（G6 / Cytoscape 各一份），用于框架 P0 对比。

它复用我们自己的管线：先用 `run-view.ps1` 出 `product.json` + `layout.json`，
再把两者与前端库一起**内联**进一个 HTML —— 双击即可打开、不联网、不起服务。

用法：
    python scripts/make-proto-page.py --engine g6 \
        --workspace samples/interconnection \
        --views "PowerViews::'power interconnection'" "PowerViews::'power structure'" \
        --lib build/vendor/node_modules/@antv/g6/dist/g6.min.js \
        --out build/proto/g6.html

    python scripts/make-proto-page.py --engine cytoscape \
        --workspace samples/interconnection \
        --views "PowerViews::'power interconnection'" \
        --lib build/vendor/node_modules/cytoscape/dist/cytoscape.min.js \
        --out build/proto/cytoscape.html
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


def run_view(workspace: str, view: str, out_dir: Path) -> dict:
    out_dir.mkdir(parents=True, exist_ok=True)
    base = out_dir / slug(view)
    product = base.with_suffix(".product.json")
    layout = base.with_suffix(".layout.json")
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
    """按限定名层级拼模型结构树（索引里补 parent 之前的临时做法）。"""
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
  :root { --line:#e3e3e3; --accent:#1a73e8; }
  * { box-sizing: border-box; }
  body { margin:0; font:13px/1.5 "Segoe UI",sans-serif; height:100vh; display:grid;
         grid-template-columns: 260px 1fr 320px; grid-template-rows: 42px 1fr 26px; }
  header { grid-column:1/4; display:flex; align-items:center; gap:12px; padding:0 12px;
           border-bottom:1px solid var(--line); }
  header .title { font-weight:600; }
  header .views button { margin-right:6px; padding:3px 10px; border:1px solid var(--line);
                         background:#fff; border-radius:4px; cursor:pointer; }
  header .views button.active { border-color:var(--accent); color:var(--accent); }
  #tree { border-right:1px solid var(--line); overflow:auto; padding:8px 4px; }
  #tree ul { list-style:none; margin:0; padding-left:12px; }
  #tree li { margin:1px 0; }
  #tree .label { cursor:pointer; padding:1px 4px; border-radius:3px; }
  #tree .label:hover { background:#eef2f8; }
  #canvas { position:relative; overflow:hidden; }
  #inspector { border-left:1px solid var(--line); overflow:auto; padding:8px 10px; }
  #inspector h3 { margin:4px 0; font-size:13px; }
  #inspector table { border-collapse:collapse; width:100%; }
  #inspector td { border-bottom:1px solid #f0f0f0; padding:2px 4px; vertical-align:top; }
  #inspector td:first-child { color:#777; width:34%; }
  footer { grid-column:1/4; border-top:1px solid var(--line); color:#666;
           display:flex; gap:16px; align-items:center; padding:0 12px; font-size:12px; }
  #metrics { display:none; }
</style>
</head>
<body>
<header>
  <span class="title">__TITLE__</span>
  <span class="views" id="views"></span>
  <span style="margin-left:auto;color:#888" id="status"></span>
</header>
<nav id="tree"></nav>
<div id="canvas"></div>
<aside id="inspector"><h3>检查器</h3><div id="details">点选一个元素</div></aside>
<footer><span id="engine"></span><span id="counts"></span></footer>
<pre id="metrics"></pre>
<script id="view-data" type="application/json">__DATA__</script>
<script>
__ENGINE_JS__
</script>
</body>
</html>
"""


SHARED_JS = r"""
const DATA = JSON.parse(document.getElementById('view-data').textContent);
const started = performance.now();
let current = DATA.views[0];
let firstRenderMs = null;
let panBroken = false;
const problems = [];

window.addEventListener('error', function (event) {
  problems.push(String(event.message));
  document.getElementById('status').textContent = 'JS ERROR: ' + event.message;
});

function panSafe(now) {
  try { panTick(now); } catch (error) { panBroken = true; problems.push('pan: ' + error.message); }
}

function labelOf(node) {
  const candidates = [node.data('label'), node.data('name'), node.data('ref')];
  for (const c of candidates) { if (c) { return c; } }
  return '(anonymous)';
}

function renderViews() {
  const box = document.getElementById('views');
  box.innerHTML = '';
  DATA.views.forEach(function (view, index) {
    const button = document.createElement('button');
    button.textContent = view.ref.split('::').slice(-1)[0].replace(/'/g, '');
    button.className = index === DATA.views.indexOf(current) ? 'active' : '';
    button.onclick = function () { current = view; renderViews(); draw(current); };
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
  span.onclick = function () { focusRef(node.ref); };
  li.appendChild(span);
  if (node.children && node.children.length) {
    const kids = document.createElement('ul');
    node.children.forEach(function (child) { kids.appendChild(treeItem(child)); });
    li.appendChild(kids);
  }
  return li;
}

function compartmentSummary(product, id) {
  const node = product.nodes.filter(function (n) { return n.id === id; })[0];
  if (!node || !node.compartments) { return ''; }
  return node.compartments.map(function (c) {
    return c.title + ': ' + c.entries.map(function (e) { return e.text; }).join(', ');
  }).join('\n');
}

/** 端口/载荷特征所属的 combo：往上找到第一个非边界祖先（载荷特征挂在端口上，要再往上一层）。 */
function comboOwnerOf(node, product) {
  let parentId = node.parent;
  let guard = 0;
  while (parentId && guard++ < 16) {
    const parent = product.nodes.filter(function (m) { return m.id === parentId; })[0];
    if (!parent) { return undefined; }
    if (parent.placement !== 'boundary') { return 'c-' + parent.id; }
    parentId = parent.parent;
  }
  return undefined;
}

function showDetails(product, id) {
  const box = document.getElementById('details');
  const node = product.nodes.filter(function (n) { return n.id === id; })[0];
  if (!node) { box.textContent = '点选一个元素'; return; }
  const related = product.relationships.filter(function (r) {
    return r.source === id || r.target === id;
  });
  const rows = [
    ['名字', node.name || '(anonymous)'],
    ['限定名', node.ref || '—'],
    ['元类', node.metaclass],
    ['来源', node.origin + (node.placement === 'boundary' ? ' · 边界' : '')],
    ['仓格', (node.compartments || []).map(function (c) { return c.title; }).join(', ') || '—'],
  ];
  let html = '<h3>' + (node.name || node.ref || id) + '</h3><table>';
  rows.forEach(function (row) {
    html += '<tr><td>' + row[0] + '</td><td>' + String(row[1]).replace(/</g, '&lt;') + '</td></tr>';
  });
  html += '</table><h3>关系（' + related.length + '）</h3><table>';
  related.slice(0, 40).forEach(function (r) {
    const otherId = r.source === id ? r.target : r.source;
    const other = product.nodes.filter(function (n) { return n.id === otherId; })[0] || {};
    html += '<tr><td>' + r.kind + '</td><td>'
      + (r.source === id ? '→ ' : '← ') + (other.name || other.ref || otherId) + '</td></tr>';
  });
  html += '</table>';
  box.innerHTML = html;
}

/**
 * 三个可复现的数字（不依赖 vsync，所以无头环境里也可比）：
 *   initMs      —— 脚本开始 → 第一次渲染完成（含建图）
 *   rerenderMs  —— 同一份数据再建一次图（切视图/重排的近似成本）
 *   updateMs    —— 连续 200 次缩放（连续平移/缩放时每步的 JS+canvas 成本）
 * 真实帧率请人工在浏览器里看，无头 Chrome 会把 rAF 节流成个位数帧，不可比。
 */
function collectMetrics(graphName, nodeCount, edgeCount) {
  // 等两帧再计时：G6 的 canvas 是异步绘制，只测 JS 调用会得到"2ms"这种假数字
  const initMs = Math.round(performance.now() - started);
  const rerenderStart = performance.now();
  paint(current);
  const rerenderMs = Math.round(performance.now() - rerenderStart);
  const updateStart = performance.now();
  for (let i = 0; i < 200; i++) { panSafe(i * 8); }
  const updateMs = Math.round(performance.now() - updateStart);
  const metrics = {
    engine: graphName, nodes: nodeCount, edges: edgeCount,
    initMs: initMs, rerenderMs: rerenderMs, update200Ms: updateMs,
    panSupported: !panBroken, problems: problems,
  };
  document.getElementById('metrics').textContent = JSON.stringify(metrics);
  document.getElementById('status').textContent =
    '建图 ' + metrics.initMs + 'ms · 重建 ' + metrics.rerenderMs + 'ms · 200 次缩放 '
    + metrics.update200Ms + 'ms' + (problems.length ? (' · 问题：' + problems[0]) : '');
}

/** 切视图 / 重画入口：引擎各实现 paint()，这里统一挂计数与指标。 */
function draw(view) {
  paint(view);
  document.getElementById('counts').textContent =
    'nodes=' + view.product.nodes.length + ' edges=' + view.product.relationships.length;
  requestAnimationFrame(function () {
    requestAnimationFrame(function () {
      collectMetrics(engineName, view.product.nodes.length, view.product.relationships.length);
    });
  });
}
"""


G6_JS = r"""
const G6NS = window.G6 || window.g6;
const engineName = 'G6 ' + (G6NS.version || '?');
let graph = null;

function createGraph(container) {
  return new G6NS.Graph({
  container: container,
  autoFit: 'view',
  padding: 20,
  data: { nodes: [], edges: [], combos: [] },
  node: {
    type: 'rect',
    style: {
      size: function (d) { return d.style.size; },
      radius: 4,
      fill: '#fff',
      stroke: '#444',
      lineWidth: 1.2,
      labelText: function (d) { return d.style.label; },
      labelPlacement: 'top',
      labelFill: '#333',
      labelFontSize: 11,
      ports: function (d) { return d.style.ports || []; },
    },
  },
  combo: {
    type: 'rect',
    style: { fill: '#f7f9fc', stroke: '#9bb', lineWidth: 1, radius: 6,
             labelText: function (d) { return d.style.label; }, labelPlacement: 'top' },
  },
  edge: {
    type: 'polyline',
    style: { stroke: '#889', lineWidth: 1.1, endArrow: true,
             labelText: function (d) { return d.style.label || ''; }, labelFontSize: 10,
             labelBackground: true, labelBackgroundFill: '#fff' },
  },
  layout: { type: 'preset' },
  });
}

function paint(view) {
  const product = view.product, layout = view.layout;
  // 每次都重建实例：一是让"重建"这个耗时数字有意义，二是避免同一 canvas 上叠两次渲染
  const container = document.getElementById('canvas');
  if (graph && typeof graph.destroy === 'function') {
    try { graph.destroy(); } catch (error) { problems.push('destroy: ' + error.message); }
  }
  container.innerHTML = '';
  graph = createGraph(container);
  const byId = {};
  product.nodes.forEach(function (n) { byId[n.id] = n; });
  const relations = product.relationships.filter(function (r) { return r.kind !== 'containment'; });
  const endpoints = {};
  relations.forEach(function (r) { endpoints[r.source] = true; endpoints[r.target] = true; });

  // 映射规则（踩过两次坑之后定的）：
  //  · 端口/载荷特征 → 用 G6 **原生 port**（style.ports + 边的 sourcePort/targetPort），不是子节点；
  //  · 容器 → 只有"不参与任何非包含关系"的节点才能当 combo —— G6 的边不能以 combo 为端点
  //    （实测 `Node not found for id: n211`），参与关系的容器只能留在节点形态；
  //  · part 同时当节点和 combo、或 combo 与节点同位置绘制，都会产生重影（实测）。
  // 第一遍：哪些节点可以当 combo（有子节点、且不参与非包含关系 —— G6 的边不能以 combo 为端点）
  const comboIds = {};
  product.nodes.forEach(function (n) {
    if (n.placement === 'boundary' || endpoints[n.id]) { return; }
    // 只有"含非边界子节点"的才是容器；只含端口/载荷特征的节点必须留在节点形态，
    // 否则它的端口无处可挂、以端口为端点的边会整条丢掉（实测）。
    const hasChildNode = product.nodes.some(function (m) {
      return m.parent === n.id && m.placement !== 'boundary';
    });
    if (!hasChildNode) { return; }
    comboIds[n.id] = true;
  });

  const combos = [];
  product.nodes.forEach(function (n) {
    if (!comboIds[n.id]) { return; }
    const box = layout.nodes[n.id];
    const parentCombo = n.parent && comboIds[n.parent] ? 'c-' + n.parent : undefined;
    combos.push({ id: 'c-' + n.id, combo: parentCombo,
                  style: { label: n.name || n.ref, x: box.x, y: box.y,
                           width: box.width, height: box.height } });
  });

  // 端口 = 节点底下**所有**边界后代（含端口上的载荷特征），一律走 G6 原生 port
  const boundaryDescendants = function (ownerId) {
    return product.nodes.filter(function (m) {
      if (m.placement !== 'boundary') { return false; }
      let parentId = m.parent, guard = 0;
      while (parentId && guard++ < 16) {
        if (parentId === ownerId) { return true; }
        const parent = byId[parentId];
        if (!parent || parent.placement !== 'boundary') { return false; }
        parentId = parent.parent;
      }
      return false;
    });
  };

  const skipped = [];
  const nodes = [];
  product.nodes.forEach(function (n) {
    if (n.placement === 'boundary') {
      return;
    }
    if (comboIds[n.id]) { return; }
    const box = layout.nodes[n.id];
    const ports = boundaryDescendants(n.id).map(function (m) {
      const portBox = layout.nodes[m.id];
      return {
        key: m.id,
        placement: [
          Math.min(1, Math.max(0, (portBox.x + portBox.width / 2 - box.x) / box.width)),
          Math.min(1, Math.max(0, (portBox.y + portBox.height / 2 - box.y) / box.height)),
        ],
        r: 5, fill: '#eef2f8', stroke: '#567',
      };
    });
    nodes.push({
      id: n.id,
      combo: n.parent && comboIds[n.parent] ? 'c-' + n.parent : undefined,
      style: {
        x: box.x + box.width / 2, y: box.y + box.height / 2,
        size: [Math.max(80, box.width), Math.max(40, box.height)],
        label: (n.name || n.ref)
          + ((n.compartments || []).length ? '\n' + compartmentSummary(product, n.id) : ''),
        port: ports.length > 0, ports: ports,
      },
      data: { ref: n.ref, name: n.name },
    });
  });

  const nodeIds = {};
  nodes.forEach(function (n) { nodeIds[n.id] = true; });

  /** 端点解析：节点直接用 id；端口/载荷特征解析成"所属节点 + port key"。 */
  function resolveEndpoint(id) {
    if (nodeIds[id]) { return { id: id }; }
    let parentId = byId[id] ? byId[id].parent : null;
    let guard = 0;
    while (parentId && guard++ < 16) {
      if (nodeIds[parentId]) { return { id: parentId, port: id }; }
      parentId = byId[parentId] ? byId[parentId].parent : null;
    }
    return null;
  }

  let dropped = 0;
  const edges = [];
  relations.forEach(function (r) {
    const source = resolveEndpoint(r.source);
    const target = resolveEndpoint(r.target);
    if (!source || !target) { dropped++; return; }
    edges.push({ id: r.id, source: source.id, target: target.id,
                 style: { label: r.kind, endArrow: true,
                          sourcePort: source.port, targetPort: target.port } });
  });
  graph.setData({ nodes: nodes, edges: edges, combos: combos });
  graph.render();
  graph.on('node:click', function (event) {
    showDetails(product, event.target.id);
  });
  problems.length = 0;
  if (dropped) { problems.push('未画的边：' + dropped + ' 条（端点在 combo 里）'); }
}

function panTick(now) {
  const zoom = 1 + 0.08 * Math.sin(now / 400);
  if (typeof graph.zoomTo === 'function') {
    graph.zoomTo(zoom, false);
  } else if (typeof graph.zoom === 'function') {
    graph.zoom(zoom);
  }
}

function focusRef(ref) {
  const node = current.product.nodes.filter(function (n) { return n.ref === ref; })[0];
  if (!node) { return; }
  showDetails(current.product, node.id);
  graph.focusElement(node.id);
}

renderViews();
renderTree();
draw(current);
"""


CYTOSCAPE_JS = r"""
const cy = cytoscape({
  container: document.getElementById('canvas'),
  style: [
    { selector: 'node', style: {
        'shape': 'round-rectangle', 'background-color': '#fff', 'border-color': '#444',
        'border-width': 1.2, 'label': 'data(label)', 'font-size': 10, 'color': '#333',
        'text-valign': 'center', 'text-halign': 'center', 'width': 'data(w)', 'height': 'data(h)',
        'text-wrap': 'wrap', 'text-max-width': 'data(w)' } },
    { selector: 'node.boundary', style: {
        'shape': 'rectangle', 'background-color': '#eef2f8', 'border-color': '#567',
        'width': 18, 'height': 18, 'font-size': 9 } },
    { selector: 'node.parent', style: {
        'background-opacity': 0.06, 'border-color': '#9bb', 'border-width': 1,
        'label': 'data(label)', 'text-valign': 'top', 'text-margin-y': 2 } },
    { selector: 'edge', style: {
        'curve-style': 'round-taxi', 'taxi-direction': 'auto', 'width': 1.1,
        'line-color': '#889', 'target-arrow-color': '#889', 'target-arrow-shape': 'triangle',
        'font-size': 9, 'color': '#666', 'label': 'data(label)', 'text-background-color': '#fff',
        'text-background-opacity': 1 } },
    { selector: ':selected', style: { 'border-color': '#1a73e8', 'border-width': 2 } },
  ],
  layout: { name: 'preset' },
  wheelSensitivity: 0.2,
});

const engineName = 'Cytoscape ' + cytoscape.version;

function paint(view) {
  const product = view.product, layout = view.layout;
  const elements = [];
  const inner = product.nodes.filter(function (n) { return n.placement !== 'boundary'; });
  product.nodes.forEach(function (n) {
    const box = layout.nodes[n.id];
    const boundary = n.placement === 'boundary';
    const text = boundary ? (n.name || '') : (n.name || n.ref) + '\n'
      + ((n.compartments || []).map(function (c) { return c.title; }).join(', ') || '');
    elements.push({ data: { id: n.id, label: text, ref: n.ref || '', name: n.name || '',
                            w: boundary ? 18 : Math.max(90, box.width),
                            h: boundary ? 18 : Math.max(44, box.height) },
                    classes: boundary ? 'boundary' : '',
                    position: { x: box.x + box.width / 2, y: box.y + box.height / 2 } });
  });
  product.relationships.filter(function (r) { return r.kind !== 'containment'; })
    .forEach(function (r) {
      elements.push({ data: { id: r.id, source: r.source, target: r.target, label: r.kind } });
    });
  cy.elements().remove();
  cy.add(elements);
  cy.layout({ name: 'preset' }).run();
  // 注意：这里**不用** compound 父子关系 —— Cytoscape 的父节点位置/尺寸由子节点算出，
  // 不能直接吃我们 layout.json 的坐标（G6 的 combo 可以显式给 x/y/w/h）。
  cy.fit(undefined, 30);
  if (cy.zoom() > 1) { cy.zoom(1); cy.center(); }
  cy.off('tap');
  cy.on('tap', 'node', function (event) { showDetails(product, event.target.id()); });
}

function panTick(now) {
  const zoom = cy.zoom() * (1 + 0.002 * Math.sin(now / 400));
  cy.zoom({ level: zoom, renderedPosition: { x: cy.width() / 2, y: cy.height() / 2 } });
}

function focusRef(ref) {
  const found = cy.nodes().filter(function (n) { return n.data('ref') === ref; });
  if (!found.length) { return; }
  showDetails(current.product, found[0].id());
  cy.animate({ center: { eles: found[0] }, zoom: 1.1 }, { duration: 200 });
}

renderViews();
renderTree();
draw(current);
"""


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--engine", choices=["g6", "cytoscape"], required=True)
    parser.add_argument("--workspace", required=True)
    parser.add_argument("--views", nargs="+", required=True)
    parser.add_argument("--lib", type=Path, required=True)
    parser.add_argument("--out", type=Path, required=True)
    parser.add_argument("--data-dir", type=Path, default=REPO / "build" / "proto" / "data")
    args = parser.parse_args()

    views = [run_view(args.workspace, view, args.data_dir) for view in args.views]
    tree = build_tree(views)
    data = {"views": views, "tree": tree,
            "workspace": args.workspace, "generatedBy": "scripts/make-proto-page.py"}

    lib = args.lib.read_text(encoding="utf-8")
    engine_js = G6_JS if args.engine == "g6" else CYTOSCAPE_JS
    title = "%s 原型 · %s" % (args.engine, args.workspace)
    html = (PAGE
            .replace("__TITLE__", title)
            .replace("__LIB__", lib)
            .replace("__DATA__", json.dumps(data, ensure_ascii=False))
            .replace("__ENGINE_JS__", SHARED_JS + "\n" + engine_js))
    args.out.parent.mkdir(parents=True, exist_ok=True)
    args.out.write_text(html, encoding="utf-8")
    print("[proto] %s -> %s (%.1f KB，含内联库 %.1f KB)" % (
        args.engine, args.out, args.out.stat().st_size / 1024, args.lib.stat().st_size / 1024))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
