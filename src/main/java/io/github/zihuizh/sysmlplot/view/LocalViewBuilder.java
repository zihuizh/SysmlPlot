package io.github.zihuizh.sysmlplot.view;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 局部关系视图：以某元素为中心，把 N 跳邻域做成一份**普通视图产物**。
 *
 * <p>这是计划里定的做法——局部视图是"产物变换"而不是渲染器功能，所以 SVG / PNG / 交互页
 * 三种出口对它天然可用，不用为它写第二套渲染。
 *
 * <p>与大视图的关键区别：**取数范围是整个模型，不是当前视图**。否则点中一个元素后，
 * 若它的邻居没被当前视图 expose，就地找邻居会什么都找不到。
 */
public final class LocalViewBuilder {

    private LocalViewBuilder() {
    }

    public static ViewProduct.Product build(WorkspaceIndex.Index index, String centerRef, int depth) {
        ModelQuery.Subgraph subgraph = ModelQuery.subgraph(index, centerRef, depth);
        Map<String, WorkspaceIndex.ElementEntry> elements = new LinkedHashMap<>();
        for (WorkspaceIndex.ElementEntry element : index.elements()) {
            elements.put(element.ref(), element);
        }

        // 文档按 URI 去重后编号
        List<String> uris = new ArrayList<>(new LinkedHashSet<>(subgraph.nodes().stream()
                .map(elements::get)
                .filter(element -> element != null && element.source() != null)
                .map(element -> element.source().uri())
                .sorted()
                .toList()));
        Map<String, Integer> documentIds = new LinkedHashMap<>();
        List<ViewProduct.DocumentRef> documents = new ArrayList<>();
        for (int i = 0; i < uris.size(); i++) {
            documentIds.put(uris.get(i), i);
            documents.add(new ViewProduct.DocumentRef(i, uris.get(i), true));
        }

        List<String> orderedRefs = new ArrayList<>(subgraph.nodes());
        orderedRefs.sort(Comparator.naturalOrder());
        Map<String, String> nodeIds = new LinkedHashMap<>();
        for (int i = 0; i < orderedRefs.size(); i++) {
            nodeIds.put(orderedRefs.get(i), "n" + (i + 1));
        }

        List<ViewProduct.NodeRef> nodes = new ArrayList<>(orderedRefs.size());
        for (String ref : orderedRefs) {
            WorkspaceIndex.ElementEntry element = elements.get(ref);
            ViewProduct.SourceRef source = null;
            if (element != null && element.source() != null) {
                Integer documentId = documentIds.get(element.source().uri());
                if (documentId != null) {
                    source = new ViewProduct.SourceRef(documentId, element.source().line(),
                            element.source().offset(), element.source().length(), element.source().snippet());
                }
            }
            nodes.add(new ViewProduct.NodeRef(
                    nodeIds.get(ref),
                    ref,
                    element == null ? null : element.name(),
                    element == null ? null : element.metaclass(),
                    graphicOf(element == null ? null : element.metaclass()),
                    element == null ? "workspace" : element.origin(),
                    null,
                    source,
                    null,
                    List.of(),
                    List.of()));
        }

        List<ViewProduct.RelationshipRef> relationships = new ArrayList<>();
        for (WorkspaceIndex.RelationEntry edge : subgraph.edges()) {
            String sourceId = nodeIds.get(edge.source());
            String targetId = nodeIds.get(edge.target());
            if (sourceId == null || targetId == null) {
                continue;
            }
            relationships.add(new ViewProduct.RelationshipRef(
                    "r" + (relationships.size() + 1), edge.kind(), sourceId, targetId, edge.authored()));
        }

        WorkspaceIndex.ElementEntry center = elements.get(centerRef);
        String centerName = center == null ? centerRef : center.name();
        ViewProduct.ViewRef viewRef = new ViewProduct.ViewRef(
                "local:" + centerRef,
                "局部关系：" + (centerName == null ? centerRef : centerName),
                null,
                "general",
                null,
                null);

        return new ViewProduct.Product(
                ViewProduct.SCHEMA_VERSION,
                index.modelDigest(),
                viewRef,
                List.copyOf(documents),
                List.copyOf(nodes),
                List.copyOf(relationships),
                new ViewProduct.Completeness(true, List.of()));
    }

    /** 与产物一致的图形类别映射（局部视图里只做粗分类）。 */
    private static String graphicOf(String metaclass) {
        if (metaclass == null) {
            return "other";
        }
        String name = metaclass.toLowerCase(java.util.Locale.ROOT);
        if (name.startsWith("package")) {
            return "package";
        }
        if (name.contains("documentation")) {
            return "documentation";
        }
        if (name.contains("multiplicity")) {
            return "multiplicity";
        }
        if (name.contains("requirement")) {
            return "requirement";
        }
        if (name.contains("attribute")) {
            return "attribute";
        }
        if (name.contains("action")) {
            return "action";
        }
        if (name.contains("state")) {
            return "state";
        }
        if (name.contains("port")) {
            return "port";
        }
        if (name.contains("item")) {
            return "item";
        }
        if (name.contains("part")) {
            return "part";
        }
        return "other";
    }
}
