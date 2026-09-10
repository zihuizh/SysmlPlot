package io.github.zihuizh.sysmlplot.view;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 源码位置 → 节点的反查。
 *
 * <p>这是"源码 → 图形"方向联动的引擎侧实现：给定文件与行列，返回覆盖该位置的全部节点，
 * **最内层在前**（节点的 `source.length` 包含子元素，所以覆盖同一位置的节点会有一串）。
 *
 * <p>位置格式：{@code <path>:<line>[:<col>]}，路径可绝对或相对工作区，列从 1 开始、缺省为 1。
 */
public final class SourceLookup {

    private SourceLookup() {
    }

    public static List<ViewProduct.NodeRef> nodesAt(ViewProduct.Product product, Path workspaceRoot, String at)
            throws Exception {
        String[] parts = at.split(":");
        if (parts.length < 2) {
            throw new IllegalArgumentException("expected <path>:<line>[:<col>], got: " + at);
        }
        boolean hasColumn = isNumber(parts[parts.length - 1]) && isNumber(parts[parts.length - 2]);
        int line;
        int column = 1;
        int pathEnd;
        if (hasColumn) {
            line = Integer.parseInt(parts[parts.length - 2]);
            column = Integer.parseInt(parts[parts.length - 1]);
            pathEnd = parts.length - 3;
        } else {
            line = Integer.parseInt(parts[parts.length - 1]);
            pathEnd = parts.length - 2;
        }
        String pathText = String.join(":", java.util.Arrays.copyOfRange(parts, 0, pathEnd + 1));
        Path file = resolveFile(workspaceRoot, pathText);

        String text = Files.readString(file, StandardCharsets.UTF_8);
        String[] lines = text.split("\n", -1);
        if (line < 1 || line > lines.length) {
            throw new IllegalArgumentException("line out of range: " + line);
        }
        int offset = 0;
        for (int i = 0; i < line - 1; i++) {
            offset += lines[i].length() + 1;
        }
        offset += Math.max(0, column - 1);

        List<ViewProduct.NodeRef> matches = new ArrayList<>();
        for (ViewProduct.NodeRef node : product.nodes()) {
            ViewProduct.SourceRef source = node.source();
            if (source == null) {
                continue;
            }
            ViewProduct.DocumentRef document = product.documents().get(source.document());
            if (!sameFile(document.uri(), file)) {
                continue;
            }
            if (offset >= source.offset() && offset < source.offset() + source.length()) {
                matches.add(node);
            }
        }
        matches.sort(Comparator.comparingInt(node -> node.source().length()));
        return matches;
    }

    /** 相对路径先按工作区根解析，不存在再退回当前目录——两种写法都常见。 */
    private static Path resolveFile(Path workspaceRoot, String pathText) {
        Path file = Path.of(pathText);
        if (!file.isAbsolute()) {
            Path fromWorkspace = workspaceRoot.resolve(pathText).toAbsolutePath().normalize();
            Path fromCwd = file.toAbsolutePath().normalize();
            file = Files.exists(fromWorkspace) ? fromWorkspace : fromCwd;
        } else {
            file = file.toAbsolutePath().normalize();
        }
        if (!Files.exists(file)) {
            throw new IllegalArgumentException("file not found: " + file);
        }
        return file;
    }

    /** 比较产物里的文档 URI 与本地文件路径是否指向同一个文件。 */
    public static boolean sameFile(String uri, Path file) {
        String path = uri;
        for (String prefix : new String[] {"file:///", "file://", "file:/"}) {
            if (path.startsWith(prefix)) {
                path = path.substring(prefix.length());
                break;
            }
        }
        return path.replace('/', '\\').equalsIgnoreCase(file.toString());
    }

    private static boolean isNumber(String value) {
        if (value.isEmpty()) {
            return false;
        }
        for (int i = 0; i < value.length(); i++) {
            if (!Character.isDigit(value.charAt(i))) {
                return false;
            }
        }
        return true;
    }
}
