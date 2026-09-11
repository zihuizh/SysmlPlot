package io.github.zihuizh.sysmlplot.render;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import io.github.zihuizh.sysmlplot.view.ViewProduct;

/**
 * 交互式渲染器：把视图产物输出成一个自包含的 HTML 文件。
 *
 * <p>它刻意复用 {@link SvgRenderer} 的图形与布局，只在上面加交互层——这样正好检验契约：
 * 交互所需要的东西（稳定 id、语义引用、来源标记、源码位置、父子关系）是否都已经在产物里。
 * 产物里没有的东西，这层不会去猜。
 *
 * <p>输出不引用任何外部资源（无 CDN、无字体、无脚本），可离线打开。
 */
public final class HtmlRenderer {

    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();

    private HtmlRenderer() {
    }

    public static String render(ViewProduct.Product product) {
        return render(product, null);
    }

    public static String render(ViewProduct.Product product, Layout.LayoutFile provided) {
        SvgRenderer.Result base = SvgRenderer.render(product, provided);
        Map<String, String> tokens = new LinkedHashMap<>();
        tokens.put("__TITLE__", escape(titleOf(product)));
        tokens.put("__SUMMARY__", escape(summaryOf(product)));
        tokens.put("__SVG__", base.svgElement());
        tokens.put("__PRODUCT_JSON__", jsonForScript(product));
        tokens.put("__LAYOUT_JSON__", jsonForScript(base.layout()));
        tokens.put("__DOCUMENTS_JSON__", jsonForScript(documentUris(product)));
        return substitute(TEMPLATE, tokens);
    }

    private static String titleOf(ViewProduct.Product product) {
        return product.view().ref() == null ? "view" : product.view().ref();
    }

    private static String summaryOf(ViewProduct.Product product) {
        long workspace = product.nodes().stream().filter(node -> "workspace".equals(node.origin())).count();
        long library = product.nodes().stream().filter(node -> "library".equals(node.origin())).count();
        long implicit = product.nodes().stream().filter(node -> "implicit".equals(node.origin())).count();
        StringBuilder summary = new StringBuilder();
        summary.append(product.nodes().size()).append(" 节点 · ")
                .append(product.relationships().size()).append(" 边 · ")
                .append("来源 workspace ").append(workspace)
                .append(" / library ").append(library)
                .append(" / implicit ").append(implicit);
        if (!product.completeness().complete()) {
            summary.append(" · 不完整：").append(product.completeness().reasons().stream()
                    .map(ViewProduct.Reason::code)
                    .collect(Collectors.joining(", ")));
        }
        return summary.toString();
    }

    private static List<String> documentUris(ViewProduct.Product product) {
        return product.documents().stream()
                .map(ViewProduct.DocumentRef::uri)
                .toList();
    }

    /** JSON 内联进 HTML 时把 {@code <} 转义，避免 {@code </script>} 提前闭合。 */
    private static String jsonForScript(Object value) {
        return GSON.toJson(value).replace("<", "\\u003c");
    }

    private static String substitute(String template, Map<String, String> tokens) {
        String result = template;
        for (Map.Entry<String, String> token : tokens.entrySet()) {
            result = result.replace(token.getKey(), token.getValue());
        }
        return result;
    }

    private static String escape(String value) {
        if (value == null) {
            return "";
        }
        return value
                .replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;");
    }

    private static final String TEMPLATE = """
            <!DOCTYPE html>
            <html lang="zh">
            <head>
            <meta charset="utf-8">
            <meta name="viewport" content="width=device-width, initial-scale=1">
            <title>SysmlPlot · __TITLE__</title>
            <style>
              :root { color-scheme: light; }
              * { box-sizing: border-box; }
              body { margin: 0; font-family: system-ui, "Segoe UI", sans-serif; color: #111;
                     display: flex; flex-direction: column; height: 100vh; }
              header { padding: 10px 16px; border-bottom: 1px solid #e3e3e3; display: flex;
                       gap: 16px; align-items: baseline; flex-wrap: wrap; }
              header .title { font-weight: 600; font-size: 14px; }
              header .summary { font-size: 12px; color: #666; }
              header .controls { margin-left: auto; display: flex; gap: 12px; align-items: center;
                                 font-size: 12px; color: #444; }
              header button { font: inherit; padding: 3px 10px; border: 1px solid #ccc;
                               border-radius: 4px; background: #fafafa; cursor: pointer; }
              main { flex: 1; display: flex; min-height: 0; }
              #canvas { flex: 1; min-width: 0; background: #fbfbfb; cursor: grab;
                        position: relative; overflow: hidden; }
              #canvas.dragging { cursor: grabbing; }
              #canvas svg { display: block; width: 100%; height: 100%; }
              aside { width: 320px; border-left: 1px solid #e3e3e3; padding: 12px 14px;
                      overflow: auto; font-size: 12px; line-height: 1.6; }
              aside h2 { font-size: 13px; margin: 0 0 8px; }
              aside dl { display: grid; grid-template-columns: 88px 1fr; gap: 2px 8px; margin: 0; }
              aside dt { color: #777; }
              aside dd { margin: 0; word-break: break-all; }
              aside .hint { color: #888; }
              aside .section { margin-top: 12px; }
              aside .snippet { background: #f6f8fa; border: 1px solid #e5e5e5; border-radius: 4px;
                               padding: 6px 8px; font-size: 11px; margin: 8px 0 0;
                               white-space: pre-wrap; word-break: break-word; }
              aside .editor-link { display: inline-block; margin-top: 10px; color: #1a73e8;
                                   text-decoration: none; }
              aside .source-path { color: #888; font-size: 11px; margin-top: 2px;
                                   word-break: break-all; }
              #outline-panel { width: 220px; border-right: 1px solid #e3e3e3; overflow: auto;
                               padding: 10px 12px; font-size: 12px; }
              .panel-title { color: #666; font-size: 11px; margin-bottom: 6px; }
              #outline { list-style: none; margin: 0; padding: 0; }
              #outline li { margin: 0; }
              #outline .outline-label { display: block; padding: 2px 4px; border-radius: 3px;
                                        cursor: pointer; white-space: nowrap; overflow: hidden;
                                        text-overflow: ellipsis; }
              #outline .outline-label:hover { background: #eef2f8; }
              #outline .outline-label.boundary { color: #456; font-style: italic; }
              #layout-panel { position: fixed; right: 340px; bottom: 16px; width: 460px;
                              background: #fff; border: 1px solid #ccc; border-radius: 6px;
                              padding: 10px; box-shadow: 0 4px 16px rgba(0,0,0,.12); }
              #layout-json { width: 100%; height: 200px; font-size: 11px; font-family: monospace;
                             border: 1px solid #e0e0e0; border-radius: 4px; resize: vertical; }
              .node.selected .box { stroke: #1a73e8; stroke-width: 2; }
            </style>
            </head>
            <body>
            <header>
              <span class="title">__TITLE__</span>
              <span class="summary">__SUMMARY__</span>
              <span class="controls">
                <button id="fit" type="button">适配</button>
                <button id="reset" type="button">重置</button>
                <button id="export-layout" type="button">导出布局</button>
                <label><input type="checkbox" id="hide-library"> 隐藏库元素</label>
                <label><input type="checkbox" id="hide-implicit"> 隐藏隐式元素</label>
              </span>
            </header>
            <main>
              <nav id="outline-panel">
                <div class="panel-title">模型大纲</div>
                <ul id="outline"></ul>
              </nav>
              <div id="canvas">__SVG__</div>
              <aside id="inspector"><p class="hint">点击节点查看元素信息</p></aside>
            </main>
            <div id="layout-panel" hidden>
              <div class="panel-title">布局 JSON（另存为 layout.json，可用 -Layout 重新载入）</div>
              <textarea id="layout-json" spellcheck="false" readonly></textarea>
            </div>
            <script id="view-product" type="application/json">__PRODUCT_JSON__</script>
            <script id="view-layout" type="application/json">__LAYOUT_JSON__</script>
            <script id="view-documents" type="application/json">__DOCUMENTS_JSON__</script>
            <script>
            (function () {
              var product = JSON.parse(document.getElementById('view-product').textContent);
              var layout = JSON.parse(document.getElementById('view-layout').textContent);
              var canvas = document.getElementById('canvas');
              var svg = canvas.querySelector('svg');
              var viewport = document.getElementById('viewport');
              var inspector = document.getElementById('inspector');

              // 让 SVG 铺满容器，使用像素坐标；缩放与平移由我们自己控制。
              svg.removeAttribute('viewBox');
              svg.setAttribute('width', '100%');
              svg.setAttribute('height', '100%');

              var scale = 1, tx = 0, ty = 0, panning = null, selected = null;

              function apply() {
                viewport.setAttribute('transform',
                  'translate(' + tx + ' ' + ty + ') scale(' + scale + ')');
              }

              function bounds() {
                var minX = Infinity, minY = Infinity, maxX = -Infinity, maxY = -Infinity;
                Object.keys(layout.nodes).forEach(function (id) {
                  var box = layout.nodes[id];
                  minX = Math.min(minX, box.x); minY = Math.min(minY, box.y);
                  maxX = Math.max(maxX, box.x + box.width);
                  maxY = Math.max(maxY, box.y + box.height);
                });
                return { minX: minX, minY: minY, maxX: maxX, maxY: maxY };
              }

              function fit() {
                var rect = canvas.getBoundingClientRect();
                var box = bounds(), w = box.maxX - box.minX, h = box.maxY - box.minY;
                scale = Math.min(rect.width / (w + 64), rect.height / (h + 64), 1.5);
                tx = (rect.width - w * scale) / 2 - box.minX * scale;
                ty = (rect.height - h * scale) / 2 - box.minY * scale;
                apply();
              }

              function reset() {
                scale = 1; tx = 0; ty = 0; apply();
              }

              var drag = null;

              function groupOf(id) {
                return svg.querySelector('.node[data-id="' + id + '"]');
              }

              canvas.addEventListener('pointerdown', function (event) {
                var node = event.target.closest('.node');
                if (!node) {
                  panning = { x: event.clientX, y: event.clientY, tx: tx, ty: ty };
                  canvas.classList.add('dragging');
                  capturePointer(event.pointerId);
                  return;
                }
                // 边界元素（端口、参数）跟着所属节点走，不单独拖动
                if (node.classList.contains('placement-boundary')) { return; }
                var ids = [node.dataset.id];
                product.nodes.forEach(function (item) {
                  if (item.parent === node.dataset.id && item.placement === 'boundary') { ids.push(item.id); }
                });
                drag = {
                  startX: event.clientX,
                  startY: event.clientY,
                  origins: ids.map(function (id) {
                    return { id: id, x: layout.nodes[id].x, y: layout.nodes[id].y };
                  })
                };
                capturePointer(event.pointerId);
              });

              /** 指针捕获在无头环境或合成事件下可能抛异常，失败不影响拖动本身。 */
              function capturePointer(pointerId) {
                try { canvas.setPointerCapture(pointerId); } catch (ignored) { /* 忽略 */ }
              }

              canvas.addEventListener('pointermove', function (event) {
                if (drag) {
                  var dx = (event.clientX - drag.startX) / scale;
                  var dy = (event.clientY - drag.startY) / scale;
                  drag.origins.forEach(function (origin) {
                    var box = layout.nodes[origin.id];
                    box.x = origin.x + dx;
                    box.y = origin.y + dy;
                    var group = groupOf(origin.id);
                    if (group) { group.setAttribute('transform', 'translate(' + box.x + ' ' + box.y + ')'); }
                  });
                  routeEdges();
                  return;
                }
                if (!panning) { return; }
                tx = panning.tx + (event.clientX - panning.x);
                ty = panning.ty + (event.clientY - panning.y);
                apply();
              });

              canvas.addEventListener('pointerup', function () {
                drag = null;
                panning = null;
                canvas.classList.remove('dragging');
              });

              /** 节点位置变了就重算连线（端口附着不画线，因此这里只处理真实边）。 */
              function routeEdges() {
                svg.querySelectorAll('.edge').forEach(function (path) {
                  var source = layout.nodes[path.dataset.source];
                  var target = layout.nodes[path.dataset.target];
                  if (!source || !target) { return; }
                  var x1 = source.x + source.width / 2, y1 = source.y + source.height;
                  var x2 = target.x + target.width / 2, y2 = target.y;
                  var midY = (y1 + y2) / 2;
                  path.setAttribute('d', 'M ' + x1 + ' ' + y1 + ' L ' + x1 + ' ' + midY
                    + ' L ' + x2 + ' ' + midY + ' L ' + x2 + ' ' + y2);
                });
              }
              canvas.addEventListener('wheel', function (event) {
                event.preventDefault();
                var rect = canvas.getBoundingClientRect();
                var mx = event.clientX - rect.left, my = event.clientY - rect.top;
                var next = Math.min(4, Math.max(0.15, scale * Math.exp(-event.deltaY * 0.0015)));
                var factor = next / scale;
                tx = mx - (mx - tx) * factor;
                ty = my - (my - ty) * factor;
                scale = next;
                apply();
              }, { passive: false });

              svg.addEventListener('click', function (event) {
                var group = event.target.closest('.node');
                select(group ? group.dataset.id : null);
              });
              document.addEventListener('keydown', function (event) {
                if (event.key === 'Escape') { select(null); }
              });

              function nodeById(id) {
                return product.nodes.filter(function (node) { return node.id === id; })[0];
              }

              function select(id) {
                if (selected) {
                  var previous = svg.querySelector('.node[data-id="' + selected + '"]');
                  if (previous) { previous.classList.remove('selected'); }
                }
                selected = id;
                if (!id) { inspector.innerHTML = '<p class="hint">点击节点查看元素信息</p>'; return; }
                var group = svg.querySelector('.node[data-id="' + id + '"]');
                if (group) { group.classList.add('selected'); }
                showInspector(nodeById(id));
              }

              function row(list, label, value) {
                if (value === undefined || value === null || value === '') { return; }
                var dt = document.createElement('dt'); dt.textContent = label;
                var dd = document.createElement('dd'); dd.textContent = value;
                list.push(dt, dd);
              }

              function showInspector(node) {
                if (!node) { return; }
                inspector.textContent = '';
                var heading = document.createElement('h2');
                heading.textContent = node.name || node.ref || node.metaclass;
                inspector.appendChild(heading);

                var list = document.createElement('dl');
                var rows = [];
                row(rows, '限定名', node.ref);
                row(rows, '元类', node.metaclass);
                row(rows, '图形', node.graphic);
                row(rows, '来源', node.origin);
                row(rows, '类型', (node.types || []).join(', '));
                row(rows, '父节点', node.parent);
                if (node.source) {
                  row(rows, '位置', '第 ' + node.source.line + ' 行');
                }
                rows.forEach(function (cell) { list.appendChild(cell); });
                inspector.appendChild(list);

                if (node.source) {
                  var uri = documentUris[node.source.document];
                  var link = document.createElement('a');
                  link.className = 'editor-link';
                  link.href = editorUri(uri, node.source.line);
                  link.textContent = '在编辑器中打开';
                  var pathText = document.createElement('div');
                  pathText.className = 'source-path';
                  pathText.textContent = uri;
                  inspector.appendChild(link);
                  inspector.appendChild(pathText);
                  if (node.source.snippet) {
                    var snippet = document.createElement('pre');
                    snippet.className = 'snippet';
                    snippet.textContent = node.source.snippet;
                    inspector.appendChild(snippet);
                  }
                }

                var section = document.createElement('div');
                section.className = 'section';
                var related = product.relationships.filter(function (relationship) {
                  return relationship.source === node.id || relationship.target === node.id;
                });
                var text = related.length === 0
                  ? '无出边或入边'
                  : related.map(function (relationship) {
                      var outgoing = relationship.source === node.id;
                      var other = nodeById(outgoing ? relationship.target : relationship.source);
                      var label = other ? (other.name || other.ref || other.metaclass) : '?';
                      var arrow = outgoing ? '\u2192 ' : '\u2190 ';
                      return relationship.kind + ' ' + arrow + label + (relationship.authored ? '' : '（推导）');
                    }).join('\\n');
                section.textContent = text;
                section.style.whiteSpace = 'pre-line';
                var caption = document.createElement('div');
                caption.textContent = '关系';
                caption.style.color = '#777';
                caption.style.marginTop = '10px';
                inspector.appendChild(caption);
                inspector.appendChild(section);
              }

              function applyFilters() {
                var hideLibrary = document.getElementById('hide-library').checked;
                var hideImplicit = document.getElementById('hide-implicit').checked;
                var hidden = {};
                svg.querySelectorAll('.node').forEach(function (group) {
                  var origin = group.dataset.origin;
                  var hide = (hideLibrary && origin === 'library') || (hideImplicit && origin === 'implicit');
                  hidden[group.dataset.id] = hide;
                  group.style.display = hide ? 'none' : '';
                });
                svg.querySelectorAll('.edge').forEach(function (path) {
                  var hide = hidden[path.dataset.source] || hidden[path.dataset.target];
                  path.style.display = hide ? 'none' : '';
                });
              }

              function editorUri(uri, line) {
                var path = uri.split('file://').join('');
                return 'vscode://file/' + path + ':' + line;
              }

              document.getElementById('fit').addEventListener('click', fit);
              document.getElementById('reset').addEventListener('click', reset);
              document.getElementById('hide-library').addEventListener('change', applyFilters);
              document.getElementById('hide-implicit').addEventListener('change', applyFilters);

              /** 模型大纲：按 parent 关系还原成树，点条目 = 选中并居中。 */
              function buildOutline() {
                var byParent = {};
                product.nodes.forEach(function (node) {
                  var key = node.parent || '';
                  (byParent[key] = byParent[key] || []).push(node);
                });
                var list = document.getElementById('outline');
                (byParent[''] || []).forEach(function (node) {
                  list.appendChild(outlineItem(node, byParent, 0));
                });
              }

              function outlineItem(node, byParent, depth) {
                var item = document.createElement('li');
                var label = document.createElement('span');
                label.className = 'outline-label' + (node.placement === 'boundary' ? ' boundary' : '');
                label.style.paddingLeft = (4 + depth * 10) + 'px';
                label.textContent = node.name || node.ref || node.metaclass;
                label.title = node.ref || node.metaclass;
                label.addEventListener('click', function () {
                  select(node.id);
                  centerOn(node.id);
                });
                item.appendChild(label);
                (byParent[node.id] || []).forEach(function (child) {
                  item.appendChild(outlineItem(child, byParent, depth + 1));
                });
                return item;
              }

              /** 把节点移到画布中央（保持当前缩放）。 */
              function centerOn(id) {
                var box = layout.nodes[id];
                if (!box) { return; }
                var rect = canvas.getBoundingClientRect();
                tx = rect.width / 2 - (box.x + box.width / 2) * scale;
                ty = rect.height / 2 - (box.y + box.height / 2) * scale;
                apply();
              }

              document.getElementById('export-layout').addEventListener('click', function () {
                var panel = document.getElementById('layout-panel');
                if (!panel.hidden) { panel.hidden = true; return; }
                document.getElementById('layout-json').value = JSON.stringify({
                  schemaVersion: 0,
                  modelDigest: product.modelDigest,
                  viewRef: product.view.ref,
                  nodes: layout.nodes
                }, null, 2);
                panel.hidden = false;
              });

              var documentUris = JSON.parse(document.getElementById('view-documents').textContent);
              buildOutline();
              fit();
            })();
            </script>
            </body>
            </html>
            """;
}
