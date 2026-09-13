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
import io.github.zihuizh.sysmlplot.view.SourceLookup;
import io.github.zihuizh.sysmlplot.view.ModelQuery;
import io.github.zihuizh.sysmlplot.view.LocalViewBuilder;
import io.github.zihuizh.sysmlplot.view.WorkspaceIndex;
import io.github.zihuizh.sysmlplot.view.WorkspaceIndexBuilder;

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
 *        [--serve &lt;port&gt;]      启动本地预览服务（交互式页面 + /cursor 光标通道）
 *        [--index &lt;file&gt;]      输出全模型索引（跨视图的关系底座，不需要 --view）
 *        [--check]             批量检查整个工作区（官方语料验收入口）
 *        [--report &lt;file&gt;]     与 --check 搭配，输出逐文件报告 JSON
 *        [--all-views &lt;dir&gt;]   一次加载把工作区里**所有视图**各导出一份产物
 *        [--query &lt;kind&gt; --ref &lt;ref&gt; [--depth N]]  查询：neighbors / impact / views / subgraph
 *        [--local-view &lt;ref&gt;]   以某元素为中心生成**局部关系视图**（产物变换，可配 -Svg/-Html）
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
        Integer servePort = null;
        Path indexOut = null;
        boolean check = false;
        Path reportOut = null;
        Path allViewsOut = null;
        String query = null;
        String queryRef = null;
        int queryDepth = 1;
        String localViewRef = null;

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
                case "--serve" -> servePort = Integer.parseInt(args[++i]);
                case "--index" -> indexOut = Path.of(args[++i]);
                case "--check" -> check = true;
                case "--report" -> reportOut = Path.of(args[++i]);
                case "--all-views" -> allViewsOut = Path.of(args[++i]);
                case "--query" -> query = args[++i];
                case "--ref" -> queryRef = args[++i];
                case "--depth" -> queryDepth = Integer.parseInt(args[++i]);
                case "--local-view" -> localViewRef = args[++i];
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

        long loadStarted = System.currentTimeMillis();
        SysMLWorkspace workspace = SysMLWorkspace.load(libraryDir, workspaceDir);
        long loadMillis = System.currentTimeMillis() - loadStarted;
        System.out.println("[workspace] " + workspace.workspaceRoot());
        System.out.println("[modelDigest] " + workspace.modelDigest());

        if (check) {
            WorkspaceCheck.run(workspace, reportOut, loadMillis);
            return;
        }

        if (allViewsOut != null) {
            writeAllViews(workspace, allViewsOut);
            return;
        }

        if (query != null) {
            if (queryRef == null) {
                System.err.println("--query 需要搭配 --ref");
                System.exit(3);
            }
            printQuery(WorkspaceIndexBuilder.build(workspace), query, queryRef, queryDepth);
            return;
        }

        // 局部关系视图：整模型取数，不受当前视图 expose 边界的限制
        if (localViewRef != null) {
            ViewProduct.Product local = LocalViewBuilder.build(
                    WorkspaceIndexBuilder.build(workspace), localViewRef, queryDepth);
            if (out == null) {
                System.out.println(GSON.toJson(local));
            } else {
                write(out, GSON.toJson(local) + "\n");
                System.out.println("[local-view] written to " + out.toAbsolutePath());
            }
            if (svg != null) {
                write(svg, SvgRenderer.render(local, null).svg());
                System.out.println("[svg] written to " + svg.toAbsolutePath());
            }
            if (html != null) {
                write(html, HtmlRenderer.render(local));
                System.out.println("[html] written to " + html.toAbsolutePath());
            }
            return;
        }

        printDiagnostics(workspace);

        if (indexOut != null) {
            WorkspaceIndex.Index index = WorkspaceIndexBuilder.build(workspace);
            write(indexOut, GSON.toJson(index) + "\n");
            System.out.println("[index] written to " + indexOut.toAbsolutePath());
            System.out.printf("[index] elements=%d relations=%d%n",
                    index.elements().size(), index.relations().size());
            return;
        }

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

        if (servePort != null) {
            String pageHtml = HtmlRenderer.render(product);
            PreviewServer.start(servePort, workspace, WorkspaceIndexBuilder.build(workspace), product, pageHtml);
            // 服务跑在后台线程上，主线程阻塞住，Ctrl+C 结束
            while (true) {
                Thread.sleep(60_000L);
            }
        }

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
    /** 输出时只显示文件名，避免整条 URI 把表格撑爆。 */
    private static String shortUri(String uri) {
        int slash = uri.lastIndexOf('/');
        return slash < 0 ? uri : uri.substring(slash + 1);
    }

    /** 查询入口：把索引上的遍历能力暴露给命令行，便于验证与脚本化。 */
    private static void printQuery(WorkspaceIndex.Index index, String query, String ref, int depth) {
        switch (query) {
            case "neighbors" -> {
                System.out.printf("[query] neighbors of %s%n", ref);
                for (ModelQuery.Neighbor neighbor : ModelQuery.neighbors(index, ref)) {
                    System.out.printf("  %-14s %s %-34s <%s>%s%n",
                            neighbor.kind(),
                            neighbor.outgoing() ? "\u2192" : "\u2190",
                            neighbor.ref(),
                            neighbor.metaclass() == null ? "?" : neighbor.metaclass(),
                            neighbor.authored() ? "" : "  (推导)");
                }
            }
            case "impact" -> {
                System.out.printf("[query] impact of %s (反向可达, depth<=%d)%n", ref, depth);
                List<ModelQuery.Impact> impacts = ModelQuery.impact(index, ref, depth);
                for (ModelQuery.Impact impact : impacts) {
                    WorkspaceIndex.ElementEntry element = ModelQuery.elementOf(index, impact.ref());
                    String where = element == null || element.source() == null
                            ? ""
                            : String.format("  @%s:%d", shortUri(element.source().uri()), element.source().line());
                    System.out.printf("  depth=%d  via %-14s %-40s <%s>%s%n",
                            impact.depth(), impact.viaKind(), impact.ref(),
                            impact.metaclass() == null ? "?" : impact.metaclass(),
                            where);
                }
                System.out.printf("  合计 %d 个受影响元素%n", impacts.size());
            }
            case "views" -> {
                System.out.printf("[query] views containing %s%n", ref);
                for (String view : ModelQuery.viewsOf(index, ref)) {
                    System.out.printf("  %s%n", view);
                }
            }
            case "subgraph" -> {
                ModelQuery.Subgraph subgraph = ModelQuery.subgraph(index, ref, depth);
                System.out.printf("[query] subgraph around %s (depth<=%d): nodes=%d edges=%d%n",
                        ref, depth, subgraph.nodes().size(), subgraph.edges().size());
                for (String node : subgraph.nodes()) {
                    System.out.printf("  node %s%n", node);
                }
                for (WorkspaceIndex.RelationEntry edge : subgraph.edges()) {
                    System.out.printf("  edge %-14s %s -> %s%n", edge.kind(), edge.source(), edge.target());
                }
            }
            default -> {
                System.err.println("未知查询: " + query + "（可用 neighbors / impact / views / subgraph）");
                System.exit(3);
            }
        }
    }

    private static void printNodesAt(ViewProduct.Product product, Path workspaceRoot, String at) throws Exception {
        List<ViewProduct.NodeRef> matches = SourceLookup.nodesAt(product, workspaceRoot, at);
        System.out.printf("[at] %s%n", at);
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

    private static void write(Path path, String content) throws Exception {
        Path target = path.toAbsolutePath();
        if (target.getParent() != null) {
            Files.createDirectories(target.getParent());
        }
        Files.writeString(target, content, StandardCharsets.UTF_8);
    }

    /**
     * 一次加载导出全部视图的产物。
     *
     * <p>存在的理由：官方语料里的模型经常互相引用（示例是"片段"，要整批一起才解析得通），
     * 所以验收时不能一个模型一个工作区，而是"一个语料工作区 + 遍历它所有的视图"。
     */
    private static void writeAllViews(SysMLWorkspace workspace, Path outDir) throws Exception {
        Path target = outDir.toAbsolutePath();
        Files.createDirectories(target);
        int written = 0;
        for (ViewUsage view : workspace.views()) {
            String ref = view.getQualifiedName();
            if (ref == null) {
                continue;
            }
            ViewProduct.Product product = ViewProductBuilder.build(workspace, view);
            String slug = ref.replaceAll("[^A-Za-z0-9._-]+", "-");
            write(target.resolve(slug + ".json"), GSON.toJson(product) + "\n");
            System.out.printf("  %-62s nodes=%d edges=%d%n",
                    ref, product.nodes().size(), product.relationships().size());
            written++;
        }
        System.out.printf("[all-views] %d view(s) written to %s%n", written, target);
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
