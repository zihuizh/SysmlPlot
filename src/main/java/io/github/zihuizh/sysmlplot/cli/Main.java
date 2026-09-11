package io.github.zihuizh.sysmlplot.cli;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.eclipse.emf.ecore.resource.Resource;
import org.eclipse.xtext.validation.Issue;
import org.omg.sysml.lang.sysml.RenderingUsage;
import org.omg.sysml.lang.sysml.ViewUsage;

import io.github.zihuizh.sysmlplot.engine.SysMLWorkspace;
import io.github.zihuizh.sysmlplot.render.HtmlRenderer;
import io.github.zihuizh.sysmlplot.render.Layout;
import io.github.zihuizh.sysmlplot.render.SvgRenderer;
import io.github.zihuizh.sysmlplot.view.ViewProduct;
import io.github.zihuizh.sysmlplot.view.ViewProductBuilder;

/**
 * 命令行入口。
 *
 * <pre>
 * Main --libdir &lt;sysml.library&gt; --workspace &lt;dir&gt;                 列出视图与诊断
 * Main --libdir &lt;sysml.library&gt; --workspace &lt;dir&gt; --view &lt;ref&gt;     生成视图产物
 *        [--out &lt;file&gt;]        产物写文件（默认 stdout）
 *        [--svg &lt;file&gt;]        渲染 SVG
 *        [--html &lt;file&gt;]       渲染自包含的交互式 HTML
 *        [--layout &lt;file&gt;]     使用既有布局
 *        [--emit-layout &lt;file&gt;] 输出本次使用的布局
 *        [--at &lt;path:line[:col]&gt;] 反查：源码位置落在哪些节点范围内（最内层在前）
 * </pre>
 *
 * <p>退出码：0 成功；2 指定的视图不存在；3 参数错误。
 */
public final class Main {

    private static final Gson GSON = new GsonBuilder()
            .setPrettyPrinting()
            .disableHtmlEscaping()
            .create();

    private Main() {
    }

    public static void main(String[] args) throws Exception {
        Path libraryDir = null;
        Path workspaceDir = null;
        Path out = null;
        Path svg = null;
        Path html = null;
        Path layoutIn = null;
        Path layoutOut = null;
        String viewRef = null;
        String at = null;

        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--libdir" -> libraryDir = Path.of(args[++i]);
                case "--workspace" -> workspaceDir = Path.of(args[++i]);
                case "--view" -> viewRef = args[++i];
                case "--out" -> out = Path.of(args[++i]);
                case "--svg" -> svg = Path.of(args[++i]);
                case "--html" -> html = Path.of(args[++i]);
                case "--layout" -> layoutIn = Path.of(args[++i]);
                case "--emit-layout" -> layoutOut = Path.of(args[++i]);
                case "--at" -> at = args[++i];
                default -> {
                    System.err.println("unknown argument: " + args[i]);
                    System.exit(3);
                }
            }
        }

        if (workspaceDir == null) {
            System.err.println("--workspace is required");
            System.exit(3);
        }

        SysMLWorkspace workspace = SysMLWorkspace.load(libraryDir, workspaceDir);
        System.out.println("[workspace] " + workspace.workspaceRoot());
        System.out.println("[modelDigest] " + workspace.modelDigest());
        printDiagnostics(workspace);

        List<ViewUsage> views = workspace.views();
        if (viewRef == null) {
            printViews(views);
            return;
        }

        ViewUsage target = findView(views, viewRef);
        if (target == null) {
            System.err.println("view not found: " + viewRef);
            printViews(views);
            System.exit(2);
            return;
        }

        ViewProduct.Product product = ViewProductBuilder.build(workspace, target);

        if (at != null) {
            printNodesAt(product, workspace.workspaceRoot(), at);
            return;
        }

        String json = GSON.toJson(product) + "\n";
        if (out == null) {
            if (svg == null && html == null) {
                System.out.println(json);
            }
        } else {
            write(out, json);
            System.out.println("[product] written to " + out.toAbsolutePath());
        }

        if (svg != null || html != null || layoutOut != null) {
            Layout.LayoutFile provided = layoutIn == null ? null : readLayout(layoutIn);
            SvgRenderer.Result rendered = SvgRenderer.render(product, provided);
            if (layoutOut != null) {
                write(layoutOut, GSON.toJson(rendered.layout()) + "\n");
                System.out.println("[layout] written to " + layoutOut.toAbsolutePath());
            }
            if (svg != null) {
                write(svg, rendered.svg());
                System.out.println("[svg] written to " + svg.toAbsolutePath());
            }
            if (html != null) {
                write(html, HtmlRenderer.render(product, provided));
                System.out.println("[html] written to " + html.toAbsolutePath());
            }
        }
    }

    private static Layout.LayoutFile readLayout(Path path) throws Exception {
        Layout.LayoutFile layout = GSON.fromJson(Files.readString(path.toAbsolutePath(), StandardCharsets.UTF_8),
                Layout.LayoutFile.class);
        if (layout == null) {
            throw new IllegalArgumentException("cannot read layout: " + path);
        }
        System.out.println("[layout] loaded " + path.toAbsolutePath());
        return layout;
    }

    /**
     * 反查：给定源码位置，列出覆盖它的节点（最内层在前）。
     *
     * <p>这是"源码 → 图形"这半边联动的引擎侧实现；编辑器扩展只需把光标位置传进来即可。
     * 位置格式：{@code <path>:<line>[:<col>]}，路径可绝对或相对工作区，列从 1 开始、缺省为 1。
     */
    private static void printNodesAt(ViewProduct.Product product, Path workspaceRoot, String at) throws Exception {
        String[] parts = at.split(":");
        if (parts.length < 2) {
            throw new IllegalArgumentException("--at 需要 <path>:<line>[:<col>]");
        }
        boolean hasColumn = isNumber(parts[parts.length - 1]) && isNumber(parts[parts.length - 2]);
        int line;
        int column = 1;
        int pathEnd = parts.length - 1;
        if (hasColumn) {
            line = Integer.parseInt(parts[parts.length - 2]);
            column = Integer.parseInt(parts[parts.length - 1]);
            pathEnd = parts.length - 3;
        } else {
            line = Integer.parseInt(parts[parts.length - 1]);
            pathEnd = parts.length - 2;
        }
        String pathText = String.join(":", java.util.Arrays.copyOfRange(parts, 0, pathEnd + 1));
        Path file = Path.of(pathText);
        if (!file.isAbsolute()) {
            // 相对路径先按工作区根解析，再退回当前目录——两种写法都常见。
            Path fromWorkspace = workspaceRoot.resolve(pathText).toAbsolutePath().normalize();
            Path fromCwd = file.toAbsolutePath().normalize();
            file = java.nio.file.Files.exists(fromWorkspace) ? fromWorkspace : fromCwd;
        } else {
            file = file.toAbsolutePath().normalize();
        }
        if (!java.nio.file.Files.exists(file)) {
            throw new IllegalArgumentException("file not found: " + file);
        }

        String text = java.nio.file.Files.readString(file, java.nio.charset.StandardCharsets.UTF_8);
        String[] lines = text.split("\n", -1);
        if (line < 1 || line > lines.length) {
            throw new IllegalArgumentException("line out of range: " + line);
        }
        int offset = 0;
        for (int i = 0; i < line - 1; i++) {
            offset += lines[i].length() + 1;
        }
        offset += Math.max(0, column - 1);

        List<ViewProduct.NodeRef> matches = new java.util.ArrayList<>();
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
        matches.sort(java.util.Comparator.comparingInt(node -> node.source().length()));

        System.out.printf("[at] %s:%d:%d%n", pathText, line, column);
        if (matches.isEmpty()) {
            System.out.println("  (没有节点覆盖该位置)");
            return;
        }
        for (ViewProduct.NodeRef node : matches) {
            System.out.printf("  %s  %s  <%s>  line=%d length=%d%n",
                    node.id(),
                    node.ref() == null ? node.name() : node.ref(),
                    node.metaclass(),
                    node.source().line(),
                    node.source().length());
        }
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

    /** 比较产物里的文档 URI 与本地文件路径是否指向同一个文件。 */
    private static boolean sameFile(String uri, Path file) {
        String path = uri;
        for (String prefix : new String[] {"file:///", "file://", "file:/"}) {
            if (path.startsWith(prefix)) {
                path = path.substring(prefix.length());
                break;
            }
        }
        return path.replace('/', '\\').equalsIgnoreCase(file.toString());
    }

    private static void write(Path path, String content) throws Exception {
        Path target = path.toAbsolutePath();
        if (target.getParent() != null) {
            Files.createDirectories(target.getParent());
        }
        Files.writeString(target, content, StandardCharsets.UTF_8);
    }

    private static void printDiagnostics(SysMLWorkspace workspace) {
        int count = 0;
        for (Resource resource : workspace.workspaceResources()) {
            for (Issue issue : workspace.validate(resource)) {
                count++;
                System.out.printf("  [%s] %s:%d %s%n",
                        issue.getSeverity().name().toLowerCase(),
                        resource.getURI().lastSegment(),
                        issue.getLineNumber(),
                        issue.getMessage());
            }
        }
        System.out.println("[diagnostics] " + count + " issue(s)");
    }

    private static void printViews(List<ViewUsage> views) {
        System.out.println("[views] " + views.size());
        for (ViewUsage view : views) {
            RenderingUsage rendering = view.getViewRendering();
            System.out.printf("  - %s  (rendering=%s, exposed=%d)%n",
                    view.getQualifiedName(),
                    rendering == null ? "-" : rendering.getName(),
                    view.getExposedElement().size());
        }
    }

    private static ViewUsage findView(List<ViewUsage> views, String ref) {
        for (ViewUsage view : views) {
            String qualifiedName = view.getQualifiedName();
            if (ref.equals(qualifiedName) || ref.equals(view.getName())) {
                return view;
            }
        }
        return null;
    }
}
