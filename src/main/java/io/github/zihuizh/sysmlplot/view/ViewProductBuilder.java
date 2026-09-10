package io.github.zihuizh.sysmlplot.view;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.eclipse.emf.ecore.resource.Resource;
import org.eclipse.xtext.nodemodel.INode;
import org.eclipse.xtext.nodemodel.util.NodeModelUtils;
import org.omg.sysml.lang.sysml.Connector;
import org.omg.sysml.lang.sysml.Element;
import org.omg.sysml.lang.sysml.Feature;
import org.omg.sysml.lang.sysml.FeatureTyping;
import org.omg.sysml.lang.sysml.Redefinition;
import org.omg.sysml.lang.sysml.ReferenceSubsetting;
import org.omg.sysml.lang.sysml.RenderingUsage;
import org.omg.sysml.lang.sysml.Specialization;
import org.omg.sysml.lang.sysml.Subsetting;
import org.omg.sysml.lang.sysml.Type;
import org.omg.sysml.lang.sysml.ViewDefinition;
import org.omg.sysml.lang.sysml.ViewUsage;

import io.github.zihuizh.sysmlplot.engine.SysMLWorkspace;

/**
 * 把一个 {@code ViewUsage} 投影成视图产物。
 *
 * <p>投影只做"读语义"的事：元素来自 {@code ViewUsage.getExposedElement()}（官方实现里
 * expose 与 filter 都已求值），关系目前只做包含关系。排序与编号规则见契约第 4 节。
 */
public final class ViewProductBuilder {

    /** 标准视图定义（限定名）到产物 `view.kind` 的映射；顺序是从具体到一般。 */
    private static final Map<String, String> STANDARD_VIEW_KINDS = Map.ofEntries(
            Map.entry("StandardViewDefinitions::ActionFlowView", "actionFlow"),
            Map.entry("StandardViewDefinitions::StateTransitionView", "stateTransition"),
            Map.entry("StandardViewDefinitions::SequenceView", "sequence"),
            Map.entry("StandardViewDefinitions::GridView", "grid"),
            Map.entry("StandardViewDefinitions::BrowserView", "browser"),
            Map.entry("StandardViewDefinitions::GeometryView", "geometry"),
            Map.entry("StandardViewDefinitions::InterconnectionView", "interconnection"),
            Map.entry("StandardViewDefinitions::GeneralView", "general"));

    /** 这些视图类型按"嵌套结构 + 端口贴边界 + 连接器当边"投影。 */
    private static final Set<String> INTERCONNECTION_KINDS = Set.of("interconnection", "actionFlow", "stateTransition");

    private ViewProductBuilder() {
    }

    public static ViewProduct.Product build(SysMLWorkspace workspace, ViewUsage view) {
        List<Element> exposed = new ArrayList<>(view.getExposedElement());

        // 暴露顺序本身是稳定的，作为匿名元素之间的最后一级排序依据。
        Map<Element, Integer> exposureOrder = new LinkedHashMap<>();
        for (int i = 0; i < exposed.size(); i++) {
            exposureOrder.put(exposed.get(i), i);
        }

        // 注意：不能用 elementId 参与排序——Pilot 每次加载都会重新生成随机 UUID，
        // 拿它当身份或排序依据会让产物不可复现。用限定名 + 元类 + 源码偏移 + 暴露顺序。
        exposed.sort(Comparator
                .comparing((Element element) -> element.getQualifiedName() == null ? 1 : 0)
                .thenComparing(element -> nullToEmpty(element.getQualifiedName()))
                .thenComparing(element -> element.eClass().getName())
                .thenComparing(ViewProductBuilder::sourceOffsetOf)
                .thenComparing(element -> exposureOrder.getOrDefault(element, Integer.MAX_VALUE)));

        Map<Resource, Integer> documentIds = collectDocuments(workspace, view, exposed);

        String kind = viewKindOf(view);
        boolean interconnectionLike = INTERCONNECTION_KINDS.contains(kind);

        // 互联类视图里，连接器不是节点（它要变成边），连接器自己的端也不是节点。
        List<Element> connectors = new ArrayList<>();
        List<Element> projected = new ArrayList<>();
        for (Element element : exposed) {
            if (interconnectionLike && isConnector(element)) {
                connectors.add(element);
                continue;
            }
            if (interconnectionLike && ownedByConnector(element)) {
                continue;
            }
            projected.add(element);
        }

        Map<Element, String> nodeIds = new LinkedHashMap<>();
        for (int i = 0; i < projected.size(); i++) {
            nodeIds.put(projected.get(i), "n" + (i + 1));
        }

        // 端口的边界附着要在节点集确定之后再算。
        Map<Element, String> boundaryParents = interconnectionLike
                ? findBoundaryParents(projected)
                : Map.of();

        List<ViewProduct.NodeRef> nodes = new ArrayList<>(projected.size());
        for (Element element : projected) {
            nodes.add(toNode(workspace, documentIds, nodeIds, boundaryParents, element));
        }

        List<ViewProduct.RelationshipRef> relationships =
                buildRelationships(nodeIds, connectors, interconnectionLike);

        List<ViewProduct.Reason> reasons = new ArrayList<>();
        int errorCount = workspace.errorCount();
        if (errorCount > 0) {
            reasons.add(new ViewProduct.Reason("model-errors",
                    "workspace has " + errorCount + " unresolved semantic error(s)"));
        }

        return new ViewProduct.Product(
                ViewProduct.SCHEMA_VERSION,
                workspace.modelDigest(),
                toViewRef(workspace, documentIds, view, kind),
                toDocumentRefs(workspace, documentIds),
                nodes,
                relationships,
                new ViewProduct.Completeness(reasons.isEmpty(), List.copyOf(reasons)));
    }

    /** 视图类型：沿视图定义的泛化闭包找标准视图定义。找不到就是 `unclassified`。 */
    private static String viewKindOf(ViewUsage view) {
        ViewDefinition definition = view.getViewDefinition();
        if (definition == null) {
            return "unclassified";
        }
        Set<String> qualifiedNames = new HashSet<>();
        collectSupertypes(definition, qualifiedNames);
        for (Map.Entry<String, String> entry : STANDARD_VIEW_KINDS.entrySet()) {
            if (qualifiedNames.contains(entry.getKey())) {
                return entry.getValue();
            }
        }
        return "unclassified";
    }

    private static void collectSupertypes(Type type, Set<String> collected) {
        if (type == null) {
            return;
        }
        String qualifiedName = type.getQualifiedName();
        if (qualifiedName != null && !collected.add(qualifiedName)) {
            return;
        }
        for (Specialization specialization : type.getOwnedSpecialization()) {
            collectSupertypes(specialization.getGeneral(), collected);
        }
    }

    private static boolean isConnector(Element element) {
        return element instanceof Connector;
    }

    /** 元素是否归属于某个连接器（连接器的端特征不单独成节点）。 */
    private static boolean ownedByConnector(Element element) {
        Element owner = element.getOwner();
        while (owner != null) {
            if (owner instanceof Connector) {
                return true;
            }
            owner = owner.getOwner();
        }
        return false;
    }

    /**
     * 端口贴在哪个节点上：优先端口属主本身（若已投影），否则找"类型闭包包含该属主"的用法节点
     * ——`tank : Tank` 上的端口来自 `Tank`，在互联视图里它应该画在 `tank` 的边界上。
     */
    private static Map<Element, String> findBoundaryParents(List<Element> projected) {
        Map<Element, String> nodeIds = new LinkedHashMap<>();
        for (int i = 0; i < projected.size(); i++) {
            nodeIds.put(projected.get(i), "n" + (i + 1));
        }

        Map<Element, String> parents = new LinkedHashMap<>();
        for (Element element : projected) {
            if (!"port".equals(graphicOf(element.eClass().getName()))) {
                continue;
            }
            Element owner = element.getOwner();
            if (owner == null) {
                continue;
            }
            String direct = nodeIds.get(owner);
            if (direct != null) {
                parents.put(element, direct);
                continue;
            }
            for (Map.Entry<Element, String> entry : nodeIds.entrySet()) {
                if (entry.getKey() instanceof Feature feature && typeClosureContains(feature, owner, new HashSet<>())) {
                    parents.put(element, entry.getValue());
                    break;
                }
            }
        }
        return parents;
    }

    private static boolean typeClosureContains(Feature feature, Element candidate, Set<Element> visited) {
        if (!visited.add(feature)) {
            return false;
        }
        for (Type type : feature.getType()) {
            if (typeClosureContains(type, candidate, visited)) {
                return true;
            }
        }
        return false;
    }

    private static boolean typeClosureContains(Type type, Element candidate, Set<Element> visited) {
        if (type == null || !visited.add(type)) {
            return false;
        }
        if (type == candidate) {
            return true;
        }
        for (Specialization specialization : type.getOwnedSpecialization()) {
            if (typeClosureContains(specialization.getGeneral(), candidate, visited)) {
                return true;
            }
        }
        return false;
    }

    private static Map<Resource, Integer> collectDocuments(SysMLWorkspace workspace,
                                                           ViewUsage view,
                                                           List<Element> exposed) {
        List<Resource> resources = new ArrayList<>();
        addResource(resources, view.eResource());
        for (Element element : exposed) {
            addResource(resources, element.eResource());
        }
        resources.sort(Comparator.comparing(resource -> resource.getURI().toString()));

        Map<Resource, Integer> ids = new LinkedHashMap<>();
        for (int i = 0; i < resources.size(); i++) {
            ids.put(resources.get(i), i);
        }
        return ids;
    }

    private static void addResource(List<Resource> resources, Resource resource) {
        if (resource != null && !resources.contains(resource)) {
            resources.add(resource);
        }
    }

    private static List<ViewProduct.DocumentRef> toDocumentRefs(SysMLWorkspace workspace,
                                                                Map<Resource, Integer> documentIds) {
        List<ViewProduct.DocumentRef> documents = new ArrayList<>(documentIds.size());
        for (Map.Entry<Resource, Integer> entry : documentIds.entrySet()) {
            documents.add(new ViewProduct.DocumentRef(
                    entry.getValue(),
                    normalizeUri(entry.getKey().getURI().toString()),
                    workspace.isWorkspaceResource(entry.getKey())));
        }
        return documents;
    }

    private static ViewProduct.ViewRef toViewRef(SysMLWorkspace workspace,
                                                 Map<Resource, Integer> documentIds,
                                                 ViewUsage view,
                                                 String kind) {
        RenderingUsage rendering = view.getViewRendering();
        return new ViewProduct.ViewRef(
                qualifiedNameOrNull(view),
                blankToNull(view.getName()),
                view.getViewDefinition() == null ? null : blankToNull(view.getViewDefinition().getName()),
                kind,
                rendering == null ? null : blankToNull(rendering.getName()),
                toSourceRef(documentIds, view));
    }

    private static ViewProduct.NodeRef toNode(SysMLWorkspace workspace,
                                              Map<Resource, Integer> documentIds,
                                              Map<Element, String> nodeIds,
                                              Map<Element, String> boundaryParents,
                                              Element element) {
        String metaclass = element.eClass().getName();
        ViewProduct.SourceRef source = toSourceRef(documentIds, element);
        String origin = originOf(workspace, element, source);

        String boundaryParent = boundaryParents.get(element);
        String parent = boundaryParent;
        if (parent == null) {
            Element owner = element.getOwner();
            parent = owner == null ? null : nodeIds.get(owner);
        }

        return new ViewProduct.NodeRef(
                nodeIds.get(element),
                qualifiedNameOrNull(element),
                blankToNull(element.getName()),
                metaclass,
                graphicOf(metaclass),
                origin,
                boundaryParent == null ? null : "boundary",
                source,
                parent,
                typeNamesOf(element));
    }

    private static int sourceOffsetOf(Element element) {
        INode node = NodeModelUtils.findActualNodeFor(element);
        return node == null ? Integer.MAX_VALUE : node.getTotalOffset();
    }

    /** 产物内的一条边（编号前的中间形态）。 */
    private record Edge(String kind, String source, String target, boolean authored) {
    }

    /**
     * 收集边。规则：只画两端都在本产物节点集内的关系；类型/特化/子集化/重定义四类只画
     * 文本里写出来的（隐式推导的不画），包含关系两者都画并如实标记 `authored`。
     */
    private static List<ViewProduct.RelationshipRef> buildRelationships(Map<Element, String> nodeIds,
                                                                       List<Element> connectors,
                                                                       boolean interconnectionLike) {
        Map<String, Edge> edges = new LinkedHashMap<>();
        for (Map.Entry<Element, String> entry : nodeIds.entrySet()) {
            Element element = entry.getKey();
            String id = entry.getValue();

            Element owner = element.getOwner();
            if (owner != null && nodeIds.containsKey(owner)) {
                boolean authored = NodeModelUtils.findActualNodeFor(element) != null;
                addEdge(edges, new Edge("containment", nodeIds.get(owner), id, authored));
            }

            if (element instanceof Type type) {
                for (Specialization specialization : type.getOwnedSpecialization()) {
                    if (NodeModelUtils.findActualNodeFor(specialization) == null) {
                        continue;
                    }
                    Element target = targetOf(specialization);
                    if (target == null || !nodeIds.containsKey(target)) {
                        continue;
                    }
                    addEdge(edges, new Edge(kindOf(specialization), id, nodeIds.get(target), true));
                }
            }
        }

        // 连接器在互联类视图里是边：端点由 reference subsetting / 特征链解析到真实特征。
        if (interconnectionLike) {
            for (Element element : connectors) {
                if (!(element instanceof Connector connector)) {
                    continue;
                }
                List<Feature> ends = connector.getConnectorEnd();
                if (ends.size() != 2) {
                    continue;
                }
                String sourceId = nodeIdFor(resolveConnectorEnd(ends.get(0), 0), nodeIds);
                String targetId = nodeIdFor(resolveConnectorEnd(ends.get(1), 0), nodeIds);
                if (sourceId != null && targetId != null) {
                    addEdge(edges, new Edge("connection", sourceId, targetId, true));
                }
            }
        }

        List<Edge> sorted = new ArrayList<>(edges.values());
        sorted.sort(Comparator
                .comparing(Edge::kind)
                .thenComparing(edge -> nodeNumber(edge.source()))
                .thenComparing(edge -> nodeNumber(edge.target())));

        List<ViewProduct.RelationshipRef> relationships = new ArrayList<>(sorted.size());
        for (int i = 0; i < sorted.size(); i++) {
            Edge edge = sorted.get(i);
            relationships.add(new ViewProduct.RelationshipRef(
                    "r" + (i + 1), edge.kind(), edge.source(), edge.target(), edge.authored()));
        }
        return relationships;
    }

    /** 把连接器端解析到真实特征：匿名端走 reference subsetting，点路径走特征链。 */
    private static Element resolveConnectorEnd(Feature end, int depth) {
        if (end == null || depth > 8) {
            return end;
        }
        ReferenceSubsetting reference = end.getOwnedReferenceSubsetting();
        if (reference != null && reference.getReferencedFeature() != null
                && reference.getReferencedFeature() != end) {
            return resolveConnectorEnd(reference.getReferencedFeature(), depth + 1);
        }
        List<Feature> chaining = end.getChainingFeature();
        if (!chaining.isEmpty()) {
            Feature last = chaining.get(chaining.size() - 1);
            if (last != end) {
                return resolveConnectorEnd(last, depth + 1);
            }
        }
        return end;
    }

    /** 端点对应的节点 id；端点本身不在节点集时沿属主链上找。 */
    private static String nodeIdFor(Element element, Map<Element, String> nodeIds) {
        Element current = element;
        while (current != null) {
            String id = nodeIds.get(current);
            if (id != null) {
                return id;
            }
            current = current.getOwner();
        }
        return null;
    }

    /** 同一对端点可能既被文本写出、又被语义推导，按端点去重并优先保留 authored 的那条。 */
    private static void addEdge(Map<String, Edge> edges, Edge edge) {
        String key = edge.kind() + "\u0000" + edge.source() + "\u0000" + edge.target();
        Edge existing = edges.get(key);
        if (existing == null || (!existing.authored() && edge.authored())) {
            edges.put(key, edge);
        }
    }

    private static String kindOf(Specialization specialization) {
        if (specialization instanceof Redefinition) {
            return "redefinition";
        }
        if (specialization instanceof FeatureTyping) {
            return "typing";
        }
        if (specialization instanceof Subsetting) {
            return "subsetting";
        }
        return "specialization";
    }

    private static Element targetOf(Specialization specialization) {
        if (specialization instanceof Redefinition redefinition) {
            return redefinition.getRedefinedFeature();
        }
        if (specialization instanceof FeatureTyping typing) {
            return typing.getType();
        }
        if (specialization instanceof Subsetting subsetting) {
            return subsetting.getSubsettedFeature();
        }
        return specialization.getGeneral();
    }

    private static int nodeNumber(String nodeId) {
        return Integer.parseInt(nodeId.substring(1));
    }

    private static ViewProduct.SourceRef toSourceRef(Map<Resource, Integer> documentIds, Element element) {
        INode node = NodeModelUtils.findActualNodeFor(element);
        if (node == null) {
            return null;
        }
        Integer documentId = documentIds.get(element.eResource());
        if (documentId == null) {
            return null;
        }
        return new ViewProduct.SourceRef(documentId, node.getStartLine(), node.getTotalOffset(), node.getTotalLength());
    }

    private static String originOf(SysMLWorkspace workspace, Element element, ViewProduct.SourceRef source) {
        if (source == null || element.eResource() == null) {
            return "implicit";
        }
        return workspace.isWorkspaceResource(element.eResource()) ? "workspace" : "library";
    }

    private static List<String> typeNamesOf(Element element) {
        if (!(element instanceof Feature feature)) {
            return List.of();
        }
        List<String> names = new ArrayList<>();
        for (Type type : feature.getType()) {
            String name = qualifiedNameOrNull(type);
            if (name != null) {
                names.add(name);
            }
        }
        return names.isEmpty() ? List.of() : List.copyOf(names);
    }

    /** 元类到图形类别的映射；渲染器可以用，也可以不用。 */
    private static String graphicOf(String metaclass) {
        String name = metaclass.toLowerCase(Locale.ROOT);
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
        if (name.contains("enumeration")) {
            return "enumeration";
        }
        if (name.contains("attribute")) {
            return "attribute";
        }
        if (name.contains("interface")) {
            return "interface";
        }
        if (name.contains("connection")) {
            return "connection";
        }
        if (name.contains("flow")) {
            return "flow";
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

    private static String qualifiedNameOrNull(Element element) {
        return blankToNull(element.getQualifiedName());
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    /**
     * EMF 的 {@code URI.toString()} 会把文件 URI 写成 {@code file:/D:/x}，
     * 这里统一成通用形式 {@code file:///D:/x}，避免下游解析器各自兼容。
     */
    private static String normalizeUri(String uri) {
        if (uri.startsWith("file:/") && !uri.startsWith("file://")) {
            return "file:///" + uri.substring("file:/".length());
        }
        return uri;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
