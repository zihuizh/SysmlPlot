package io.github.zihuizh.sysmlplot.cli;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import io.github.zihuizh.sysmlplot.view.SourceLookup;
import io.github.zihuizh.sysmlplot.view.ViewProduct;
import io.github.zihuizh.sysmlplot.view.ViewProductBuilder;
import io.github.zihuizh.sysmlplot.view.WorkspaceIndex;
import io.github.zihuizh.sysmlplot.view.ModelQuery;
import io.github.zihuizh.sysmlplot.view.LocalViewBuilder;
import io.github.zihuizh.sysmlplot.view.TraceMatrix;
import io.github.zihuizh.sysmlplot.engine.SysMLWorkspace;
import io.github.zihuizh.sysmlplot.render.HtmlRenderer;
import org.omg.sysml.lang.sysml.ViewUsage;

/**
 * 本地预览服务：把交互式页面用 HTTP 提供出来，并开放一个"光标"通道。
 *
 * <p>存在的意义是把**反向联动**做成编辑器无关的：任何编辑器（VS Code 扩展、其他 IDE、
 * 甚至一个快捷键脚本）只要请求一次光标位置，页面就会高亮对应节点。
 *
 * <pre>
 * GET  /                              交互式页面
 * GET  /cursor                        当前光标解析结果 {"at": …, "ids": [...]}
 * GET  /cursor?path=…&line=…&col=…    设置光标位置（编辑器调用这个）
 * GET  /views?ref=…                   出现在哪些视图
 * GET  /neighbors?ref=…               一跳邻居
 * GET  /impact?ref=…&depth=N          影响范围（表格页）
 * GET  /local?ref=…&depth=N           以该元素为中心的局部关系视图（HTML）
 * GET  /view?ref=&lt;视图&gt;&amp;focus=&lt;元素&gt;          切到另一个视图并聚焦到该元素
 * GET  /matrix?gaps=1                 需求追溯矩阵（gaps=1 只看有缺口的行）
 * </pre>
 *
 * <p>只监听 127.0.0.1。刻意用阻塞式 socket 手写，而不是 {@code com.sun.net.httpserver}：
 * JDK 的 HttpServer 依赖 Selector，而 Selector 初始化时要开一对回环 socket 作唤醒管道，
 * 在受限环境里这一步会被拒绝（实测 {@code Selector.open()} 抛
 * "Unable to establish loopback connection"）。阻塞式 ServerSocket 没有这个依赖。
 */
public final class PreviewServer {

    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();

    private final String html;
    private final ViewProduct.Product product;
    private final Path workspaceRoot;
    private final SysMLWorkspace workspace;
    private final WorkspaceIndex.Index index;
    private final Map<String, ViewUsage> viewsByRef = new HashMap<>();
    private final Map<String, String> pageCache = new HashMap<>();
    private volatile String cursor;

    private PreviewServer(SysMLWorkspace workspace,
                          WorkspaceIndex.Index index,
                          ViewProduct.Product product,
                          String html) {
        this.workspace = workspace;
        this.index = index;
        this.product = product;
        this.html = html;
        this.workspaceRoot = workspace.workspaceRoot();
        for (ViewUsage view : workspace.views()) {
            if (view.getQualifiedName() != null) {
                viewsByRef.put(view.getQualifiedName(), view);
            }
        }
    }

    /** 在后台线程上启动服务并立即返回；由调用方负责阻塞进程。 */
    public static PreviewServer start(int port,
                                      SysMLWorkspace workspace,
                                      WorkspaceIndex.Index index,
                                      ViewProduct.Product product,
                                      String html) throws IOException {
        PreviewServer server = new PreviewServer(workspace, index, product, html);
        ServerSocket serverSocket = new ServerSocket();
        serverSocket.setReuseAddress(true);
        serverSocket.bind(new InetSocketAddress("127.0.0.1", port));

        Thread acceptor = new Thread(() -> server.acceptLoop(serverSocket), "sysmlplot-preview");
        acceptor.setDaemon(true);
        acceptor.start();

        System.out.printf("[serve] http://127.0.0.1:%d/  (视图 %s)%n", port, product.view().ref());
        System.out.println("[serve] 编辑器把光标位置发到 /cursor?path=<文件>&line=<行>&col=<列> 即可驱动高亮");
        return server;
    }

    private void acceptLoop(ServerSocket serverSocket) {
        while (!serverSocket.isClosed()) {
            try {
                Socket socket = serverSocket.accept();
                Thread worker = new Thread(() -> handle(socket), "sysmlplot-preview-worker");
                worker.setDaemon(true);
                worker.start();
            } catch (IOException e) {
                return;
            }
        }
    }

    private void handle(Socket socket) {
        try (socket;
             BufferedReader reader = new BufferedReader(
                     new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
             OutputStream out = socket.getOutputStream()) {
            String requestLine = reader.readLine();
            if (requestLine == null) {
                return;
            }
            String header;
            while ((header = reader.readLine()) != null && !header.isEmpty()) {
                // 请求头用不到，读到空行为止
            }

            String[] parts = requestLine.split(" ");
            String path = parts.length > 1 ? parts[1] : "/";
            String query = null;
            int question = path.indexOf('?');
            if (question >= 0) {
                query = path.substring(question + 1);
                path = path.substring(0, question);
            }

            if ("/cursor".equals(path)) {
                Map<String, String> params = parseQuery(query);
                if (params.containsKey("path")) {
                    cursor = params.get("path") + ":" + params.getOrDefault("line", "1")
                            + (params.containsKey("col") ? ":" + params.get("col") : "");
                }
                respond(out, 200, "application/json; charset=utf-8", resolve().getBytes(StandardCharsets.UTF_8));
            } else if ("/views".equals(path)) {
                String ref = parseQuery(query).get("ref");
                respond(out, 200, "application/json; charset=utf-8",
                        json(ModelQuery.viewsOf(index, ref)).getBytes(StandardCharsets.UTF_8));
            } else if ("/neighbors".equals(path)) {
                String ref = parseQuery(query).get("ref");
                respond(out, 200, "application/json; charset=utf-8",
                        json(ModelQuery.neighbors(index, ref)).getBytes(StandardCharsets.UTF_8));
            } else if ("/impact".equals(path)) {
                Map<String, String> params = parseQuery(query);
                int depth = parseDepth(params.get("depth"), 2);
                respond(out, 200, "text/html; charset=utf-8",
                        impactPage(params.get("ref"), depth).getBytes(StandardCharsets.UTF_8));
            } else if ("/local".equals(path)) {
                Map<String, String> params = parseQuery(query);
                int depth = parseDepth(params.get("depth"), 1);
                respond(out, 200, "text/html; charset=utf-8",
                        localPage(params.get("ref"), depth).getBytes(StandardCharsets.UTF_8));
            } else if ("/view".equals(path)) {
                Map<String, String> params = parseQuery(query);
                respond(out, 200, "text/html; charset=utf-8",
                        viewPage(params.get("ref"), params.get("focus")).getBytes(StandardCharsets.UTF_8));
            } else if ("/matrix".equals(path)) {
                Map<String, String> params = parseQuery(query);
                TraceMatrix.Matrix matrix = TraceMatrix.build(index);
                if ("1".equals(params.get("gaps")) || "true".equals(params.get("gaps"))) {
                    matrix = TraceMatrix.gapsOnly(matrix);
                }
                respond(out, 200, "application/json; charset=utf-8",
                        json(matrix).getBytes(StandardCharsets.UTF_8));
            } else if ("/".equals(path) || "/index.html".equals(path)) {
                respond(out, 200, "text/html; charset=utf-8", html.getBytes(StandardCharsets.UTF_8));
            } else {
                respond(out, 404, "text/plain; charset=utf-8", "not found".getBytes(StandardCharsets.UTF_8));
            }
        } catch (IOException ignored) {
            // 连接被客户端中断是常态
        }
    }

    private static int parseDepth(String value, int fallback) {
        try {
            int depth = Integer.parseInt(value);
            return depth < 1 ? fallback : Math.min(depth, 5);
        } catch (Exception ignored) {
            return fallback;
        }
    }

    private String json(Object value) {
        return GSON.toJson(value);
    }

    /** 切到另一个视图。focus 由前端读 location.search 决定选中哪个元素。 */
    private String viewPage(String viewRef, String focus) {
        if (viewRef == null) {
            return errorPage("缺少 ref 参数");
        }
        String cached = pageCache.get("view:" + viewRef);
        if (cached == null) {
            ViewUsage view = viewsByRef.get(viewRef);
            if (view == null) {
                return errorPage("未知视图: " + viewRef);
            }
            cached = HtmlRenderer.render(ViewProductBuilder.build(workspace, view));
            pageCache.put("view:" + viewRef, cached);
        }
        return cached;
    }

    private String localPage(String ref, int depth) {
        if (ref == null) {
            return errorPage("缺少 ref 参数");
        }
        return HtmlRenderer.render(LocalViewBuilder.build(index, ref, depth));
    }

    /** 影响范围用表格呈现——它是"清单"不是"图"，表格比图清楚。 */
    private String impactPage(String ref, int depth) {
        if (ref == null) {
            return errorPage("缺少 ref 参数");
        }
        List<ModelQuery.Impact> impacts = ModelQuery.impact(index, ref, depth);
        StringBuilder page = new StringBuilder();
        page.append("<!DOCTYPE html><html lang=\"zh\"><head><meta charset=\"utf-8\">")
                .append("<title>影响范围</title><style>")
                .append("body{font-family:system-ui,'Segoe UI',sans-serif;margin:24px;color:#111}")
                .append("table{border-collapse:collapse;font-size:13px}")
                .append("td,th{border-bottom:1px solid #eee;padding:6px 10px;text-align:left}")
                .append("a{color:#1a73e8;text-decoration:none}a:hover{text-decoration:underline}")
                .append("</style></head><body>");
        page.append("<h2>影响范围：").append(escapeHtml(ref)).append("</h2>");
        page.append("<p>改动它会牵连下列元素（反向可达，depth ≤ ").append(depth)
                .append("；已排除 containment 这类结构边）。共 <b>").append(impacts.size())
                .append("</b> 个。</p>");
        if (impacts.isEmpty()) {
            page.append("<p>没有受影响元素。</p>");
        } else {
            page.append("<table><tr><th>跳数</th><th>通过关系</th><th>元素</th><th>元类</th><th>源码</th></tr>");
            for (ModelQuery.Impact impact : impacts) {
                page.append("<tr><td>").append(impact.depth()).append("</td><td>")
                        .append(escapeHtml(impact.viaKind())).append("</td><td><a href=\"/local?ref=")
                        .append(url(impact.ref())).append("\">").append(escapeHtml(impact.ref()))
                        .append("</a></td><td>").append(escapeHtml(impact.metaclass())).append("</td><td>")
                        .append(sourceCell(impact.ref())).append("</td></tr>");
            }
            page.append("</table>");
        }
        page.append("<p><a href=\"/\">← 回到视图</a></p></body></html>");
        return page.toString();
    }

    private String sourceCell(String ref) {
        WorkspaceIndex.ElementEntry element = ModelQuery.elementOf(index, ref);
        if (element == null || element.source() == null) {
            return "";
        }
        String uri = element.source().uri();
        int slash = uri.lastIndexOf('/');
        return escapeHtml(slash < 0 ? uri : uri.substring(slash + 1)) + ":" + element.source().line();
    }

    private static String url(String value) {
        return java.net.URLEncoder.encode(value == null ? "" : value, StandardCharsets.UTF_8);
    }

    private static String escapeHtml(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }

    private static String errorPage(String message) {
        return "<!DOCTYPE html><html lang=\"zh\"><head><meta charset=\"utf-8\"><title>错误</title></head><body><p>"
                + escapeHtml(message) + "</p></body></html>";
    }

    /** 解析光标位置并回 JSON；没有光标或解析失败时返回空列表（不让页面报错）。 */
    private String resolve() {
        Map<String, Object> payload = new HashMap<>();
        List<String> ids = new ArrayList<>();
        String at = cursor;
        payload.put("at", at);
        if (at != null) {
            try {
                for (ViewProduct.NodeRef node : SourceLookup.nodesAt(product, workspaceRoot, at)) {
                    ids.add(node.id());
                }
            } catch (Exception ignored) {
                // 光标落在解析不了的位置是常态
            }
        }
        payload.put("ids", ids);
        return GSON.toJson(payload);
    }

    private static Map<String, String> parseQuery(String rawQuery) {
        Map<String, String> result = new HashMap<>();
        if (rawQuery == null || rawQuery.isEmpty()) {
            return result;
        }
        for (String pair : rawQuery.split("&")) {
            int eq = pair.indexOf('=');
            if (eq <= 0) {
                continue;
            }
            result.put(decode(pair.substring(0, eq)), decode(pair.substring(eq + 1)));
        }
        return result;
    }

    private static String decode(String value) {
        return URLDecoder.decode(value, StandardCharsets.UTF_8);
    }

    private static void respond(OutputStream out, int status, String contentType, byte[] body)
            throws IOException {
        String header = "HTTP/1.1 " + status + (status == 200 ? " OK" : " Not Found") + "\r\n"
                + "Content-Type: " + contentType + "\r\n"
                + "Content-Length: " + body.length + "\r\n"
                + "Cache-Control: no-store\r\n"
                + "Connection: close\r\n\r\n";
        out.write(header.getBytes(StandardCharsets.UTF_8));
        out.write(body);
        out.flush();
    }
}
