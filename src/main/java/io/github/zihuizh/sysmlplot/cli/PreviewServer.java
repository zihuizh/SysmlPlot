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
    private volatile String cursor;

    private PreviewServer(String html, ViewProduct.Product product, Path workspaceRoot) {
        this.html = html;
        this.product = product;
        this.workspaceRoot = workspaceRoot;
    }

    /** 在后台线程上启动服务并立即返回；由调用方负责阻塞进程。 */
    public static PreviewServer start(int port,
                                      String html,
                                      ViewProduct.Product product,
                                      Path workspaceRoot) throws IOException {
        PreviewServer server = new PreviewServer(html, product, workspaceRoot);
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
            } else if ("/".equals(path) || "/index.html".equals(path)) {
                respond(out, 200, "text/html; charset=utf-8", html.getBytes(StandardCharsets.UTF_8));
            } else {
                respond(out, 404, "text/plain; charset=utf-8", "not found".getBytes(StandardCharsets.UTF_8));
            }
        } catch (IOException ignored) {
            // 连接被客户端中断是常态
        }
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
