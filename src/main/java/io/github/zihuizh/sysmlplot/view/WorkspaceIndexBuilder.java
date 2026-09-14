package io.github.zihuizh.sysmlplot.view;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import org.eclipse.emf.common.util.TreeIterator;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.emf.ecore.resource.Resource;
import org.eclipse.xtext.nodemodel.INode;
import org.eclipse.xtext.nodemodel.util.NodeModelUtils;
import org.omg.sysml.lang.sysml.Connector;
import org.omg.sysml.lang.sysml.Element;
import org.omg.sysml.lang.sysml.RequirementUsage;
import org.omg.sysml.lang.sysml.ViewUsage;

import io.github.zihuizh.sysmlplot.engine.SysMLWorkspace;

/**
 * 构建全模型索引。
 *
 * <p>两条设计约束：
 * <ol>
 *   <li>关系推导**复用** {@link ViewProductBuilder#deriveRelations}，不另写一套——否则
 *       "视图里的关系"和"索引里的关系"迟早漂移。</li>
 *   <li>元素来源与 `origin` 的判定沿用产物的规则（有源码位置就算 workspace，否则 implicit；
 *       索引只扫工作区资源，所以不出现 library）。</li>
 * </ol>
 */
public final class WorkspaceIndexBuilder {

    private WorkspaceIndexBuilder() {
    }

    public static WorkspaceIndex.Index build(SysMLWorkspace workspace) {
        Map<String, List<String>> viewsByRef = collectViews(workspace);

        Map<Element, String> refByElement = new LinkedHashMap<>();
        List<Element> elements = new ArrayList<>();
        for (Resource resource : workspace.workspaceResources()) {
            TreeIterator<EObject> iterator = resource.getAllContents();
            while (iterator.hasNext()) {
                if (!(iterator.next() instanceof Element element)) {
                    continue;
                }
                String ref = blankToNull(element.getQualifiedName());
                if (ref == null || refByElement.containsKey(element)) {
                    continue;
                }
                refByElement.put(element, ref);
                elements.add(element);
            }
        }

        List<Element> connectors = new ArrayList<>();
        for (Element element : elements) {
            if (element instanceof Connector) {
                connectors.add(element);
            }
        }

        List<ViewProduct.RelationshipRef> derived =
                ViewProductBuilder.deriveRelations(refByElement, connectors, true);

        List<WorkspaceIndex.ElementEntry> entries = new ArrayList<>(elements.size());
        for (Element element : elements) {
            String ref = refByElement.get(element);
            entries.add(new WorkspaceIndex.ElementEntry(
                    ref,
                    blankToNull(element.getName()),
                    element instanceof RequirementUsage requirement ? blankToNull(requirement.getReqId()) : null,
                    element.eClass().getName(),
                    element.getOwningRelationship() == null
                            ? null
                            : element.getOwningRelationship().eClass().getName(),
                    originOf(element),
                    sourceOf(element),
                    viewsByRef.getOrDefault(ref, List.of())));
        }
        entries.sort(Comparator.comparing(WorkspaceIndex.ElementEntry::ref));

        List<WorkspaceIndex.RelationEntry> relations = new ArrayList<>(derived.size());
        for (ViewProduct.RelationshipRef relation : derived) {
            relations.add(new WorkspaceIndex.RelationEntry(
                    relation.kind(), relation.source(), relation.target(), relation.authored()));
        }

        return new WorkspaceIndex.Index(WorkspaceIndex.SCHEMA_VERSION, workspace.modelDigest(),
                List.copyOf(entries), List.copyOf(relations));
    }

    /** 视图 → 它包含的元素 ref。视图内容仍由产物定义，索引只做汇总。 */
    private static Map<String, List<String>> collectViews(SysMLWorkspace workspace) {
        Map<String, List<String>> viewsByRef = new TreeMap<>();
        for (ViewUsage view : workspace.views()) {
            String viewRef = blankToNull(view.getQualifiedName());
            if (viewRef == null) {
                continue;
            }
            ViewProduct.Product product = ViewProductBuilder.build(workspace, view);
            for (ViewProduct.NodeRef node : product.nodes()) {
                if (node.ref() == null) {
                    continue;
                }
                List<String> views = viewsByRef.computeIfAbsent(node.ref(), key -> new ArrayList<>());
                if (!views.contains(viewRef)) {
                    views.add(viewRef);
                }
            }
        }
        return viewsByRef;
    }

    /** 与产物保持一致的 origin 规则；索引只扫工作区，因此只会出现 workspace / implicit。 */
    private static String originOf(Element element) {
        return sourceOf(element) == null || element.eResource() == null ? "implicit" : "workspace";
    }

    private static WorkspaceIndex.ElementSource sourceOf(Element element) {
        INode node = NodeModelUtils.findActualNodeFor(element);
        Resource resource = element.eResource();
        if (node == null || resource == null) {
            return null;
        }
        INode root = node.getRootNode();
        String text = root == null ? null : root.getText();
        String snippet = null;
        if (text != null) {
            int from = Math.min(node.getOffset(), text.length());
            int to = Math.min(from + node.getLength(), text.length());
            String collapsed = text.substring(from, to).replaceAll("\\s+", " ").trim();
            snippet = collapsed.isEmpty()
                    ? null
                    : collapsed.length() <= MAX_SNIPPET
                            ? collapsed
                            : collapsed.substring(0, MAX_SNIPPET - 1) + "\u2026";
        }
        return new WorkspaceIndex.ElementSource(normalizeUri(resource.getURI().toString()),
                node.getStartLine(), node.getOffset(), node.getLength(), snippet);
    }

    /** EMF 会把文件 URI 写成 `file:/D:/x`，统一成 `file:///D:/x`。 */
    private static String normalizeUri(String uri) {
        if (uri.startsWith("file:/") && !uri.startsWith("file://")) {
            return "file:///" + uri.substring("file:/".length());
        }
        return uri;
    }

    private static final int MAX_SNIPPET = 160;

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
