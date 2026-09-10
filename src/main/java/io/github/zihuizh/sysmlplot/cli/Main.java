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
import io.github.zihuizh.sysmlplot.view.ViewProduct;
import io.github.zihuizh.sysmlplot.view.ViewProductBuilder;

/**
 * 命令行入口。
 *
 * <pre>
 * Main --libdir &lt;sysml.library&gt; --workspace &lt;dir&gt;                 列出视图与诊断
 * Main --libdir &lt;sysml.library&gt; --workspace &lt;dir&gt; --view &lt;ref&gt;     生成视图产物
 *                                                            --out &lt;file&gt; 写文件（默认 stdout）
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
        String viewRef = null;

        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--libdir" -> libraryDir = Path.of(args[++i]);
                case "--workspace" -> workspaceDir = Path.of(args[++i]);
                case "--view" -> viewRef = args[++i];
                case "--out" -> out = Path.of(args[++i]);
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
        String json = GSON.toJson(product) + "\n";
        if (out == null) {
            System.out.println(json);
        } else {
            Path target1 = out.toAbsolutePath();
            if (target1.getParent() != null) {
                Files.createDirectories(target1.getParent());
            }
            Files.writeString(target1, json, StandardCharsets.UTF_8);
            System.out.println("[product] written to " + target1);
        }
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

