package io.github.zihuizh.sysmlplot.spike;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.eclipse.emf.common.util.TreeIterator;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.emf.ecore.resource.Resource;
import org.eclipse.emf.ecore.resource.Resource.Diagnostic;
import org.omg.sysml.interactive.SysMLInteractive;
import org.omg.sysml.interactive.VizResult;
import org.omg.sysml.lang.sysml.Element;
import org.omg.sysml.lang.sysml.RenderingUsage;
import org.omg.sysml.lang.sysml.ViewUsage;

/**
 * 阶段 1 spike：用 OMG 官方解析器（SysML v2 Pilot Implementation）加载一个工作区，
 * 报告诊断、语义元素与 view 清单，并可把某个 view 渲染成 PlantUML 或 SVG。
 *
 * <p>这不是最终形态，只用于确认 API 链路与最小依赖集合。
 */
public final class PilotSpike {

    private PilotSpike() {
    }

    public static void main(String[] args) throws Exception {
        String libDir = null;
        String workspace = "samples/vehicle";
        String graphviz = null;
        String pumlView = null;
        String svgView = null;
        String outFile = null;

        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--libdir" -> libDir = args[++i];
                case "--workspace" -> workspace = args[++i];
                case "--graphviz" -> graphviz = args[++i];
                case "--puml" -> pumlView = args[++i];
                case "--svg" -> svgView = args[++i];
                case "--out" -> outFile = args[++i];
                default -> throw new IllegalArgumentException("unknown argument: " + args[i]);
            }
        }

        SysMLInteractive sysml = SysMLInteractive.createInstance();
        sysml.setVerbose(false);
        if (libDir != null) {
            sysml.loadLibrary(libDir);
            System.out.println("[library] " + libDir);
        }
        if (graphviz != null) {
            sysml.setGraphVizPath(graphviz);
            System.out.println("[graphviz] " + graphviz);
        }

        Path workspacePath = Path.of(workspace).toAbsolutePath();
        if (!Files.isDirectory(workspacePath)) {
            throw new IllegalArgumentException("workspace is not a directory: " + workspacePath);
        }

        // 注意：不要用 next()/parse() 逐个加载文件。那条路径只是把文本塞进一个未索引的
        // 内存资源，后续文件解析时看不到前面文件，跨文件引用会全部解析失败。
        // readAll 走的是真正的 ResourceSet 加载 + 索引。
        sysml.readAll(workspacePath.toString(), true);
        sysml.resolveAllInputResources();

        List<Resource> inputs = sysml.getInputResources();
        System.out.println("[workspace] " + workspacePath + " -> " + inputs.size() + " resource(s)");

        int errors = 0;
        for (Resource resource : inputs) {
            System.out.printf("  - %s (%d root element(s))%n",
                    resource.getURI().lastSegment(),
                    resource.getContents().size());
            for (Diagnostic diagnostic : resource.getErrors()) {
                errors++;
                System.out.printf("      [error] line %d: %s%n", diagnostic.getLine(), diagnostic.getMessage());
            }
            for (Diagnostic diagnostic : resource.getWarnings()) {
                System.out.printf("      [warn]  line %d: %s%n", diagnostic.getLine(), diagnostic.getMessage());
            }
        }
        System.out.printf("[diagnostics] resource errors=%d%n", errors);

        List<ViewUsage> views = findAllViews(inputs);
        System.out.println("[views] found " + views.size());
        for (ViewUsage view : views) {
            RenderingUsage rendering = view.getViewRendering();
            List<Element> exposed = view.getExposedElement();
            System.out.printf("  - %s%n", qualifiedNameOf(view));
            System.out.printf("      rendering: %s%n", rendering == null ? "(none)" : rendering.getName());
            System.out.printf("      exposed: %d%n", exposed.size());
            for (Element element : exposed) {
                System.out.printf("        * %s  <%s>%n", qualifiedNameOf(element), element.eClass().getName());
            }
        }

        if (pumlView != null) {
            System.out.println("[render:puml] " + pumlView);
            VizResult result = sysml.view(pumlView, new ArrayList<>(), new ArrayList<>(List.of("PUMLCODE")), new ArrayList<>());
            System.out.println(describe(result));
            if (!result.hasException()) {
                System.out.println(result.getPlantUML());
            }
        }

        if (svgView != null) {
            System.out.println("[render:svg] " + svgView);
            VizResult result = sysml.view(svgView, new ArrayList<>(), new ArrayList<>(), new ArrayList<>());
            if (result.hasException()) {
                System.out.println(describe(result));
            } else {
                String svg = result.getSVG();
                if (outFile == null) {
                    System.out.println("[svg] " + (svg == null ? 0 : svg.length()) + " chars (use --out to write a file)");
                } else {
                    Path target = Path.of(outFile).toAbsolutePath();
                    Files.createDirectories(target.getParent());
                    Files.writeString(target, svg, StandardCharsets.UTF_8);
                    System.out.println("[svg] written to " + target);
                }
            }
        }
    }

    private static List<ViewUsage> findAllViews(List<Resource> resources) {
        List<ViewUsage> views = new ArrayList<>();
        for (Resource resource : resources) {
            TreeIterator<EObject> iterator = resource.getAllContents();
            while (iterator.hasNext()) {
                EObject object = iterator.next();
                if (object instanceof ViewUsage viewUsage) {
                    views.add(viewUsage);
                }
            }
        }
        return views;
    }

    private static String qualifiedNameOf(Element element) {
        String qualifiedName = element.getQualifiedName();
        if (qualifiedName != null && !qualifiedName.isBlank()) {
            return qualifiedName;
        }
        String name = element.getName();
        return name == null || name.isBlank() ? "(anonymous " + element.eClass().getName() + ")" : name;
    }

    private static String describe(VizResult result) {
        return result.hasException() ? "  [error] " + result.formatException() : "  [ok] kind=" + result.kind;
    }

}
