package io.github.zihuizh.sysmlplot.view;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.eclipse.emf.ecore.resource.Resource;
import org.eclipse.xtext.nodemodel.INode;
import org.eclipse.xtext.nodemodel.util.NodeModelUtils;
import org.omg.sysml.lang.sysml.Connector;
import org.omg.sysml.lang.sysml.ConnectionUsage;
import org.omg.sysml.lang.sysml.ConstraintUsage;
import org.omg.sysml.lang.sysml.Element;
import org.omg.sysml.lang.sysml.Feature;
import org.omg.sysml.lang.sysml.FeatureDirectionKind;
import org.omg.sysml.lang.sysml.FeatureTyping;
import org.omg.sysml.lang.sysml.FeatureValue;
import org.omg.sysml.lang.sysml.BindingConnector;
import org.omg.sysml.lang.sysml.Expression;
import org.omg.sysml.lang.sysml.FlowUsage;
import org.omg.sysml.lang.sysml.ItemUsage;
import org.omg.sysml.lang.sysml.ActorMembership;
import org.omg.sysml.lang.sysml.ActionUsage;
import org.omg.sysml.lang.sysml.AllocationUsage;
import org.omg.sysml.lang.sysml.Annotation;
import org.omg.sysml.lang.sysml.ObjectiveMembership;
import org.omg.sysml.lang.sysml.OccurrenceUsage;
import org.omg.sysml.lang.sysml.OwningMembership;
import org.omg.sysml.lang.sysml.PartUsage;
import org.omg.sysml.lang.sysml.PortUsage;
import org.omg.sysml.lang.sysml.PerformActionUsage;
import org.omg.sysml.lang.sysml.Documentation;
import org.omg.sysml.lang.sysml.MetadataFeature;
import org.omg.sysml.lang.sysml.RequirementVerificationMembership;
import org.omg.sysml.lang.sysml.RequirementUsage;
import org.omg.sysml.lang.sysml.VerificationCaseDefinition;
import org.omg.sysml.lang.sysml.VerificationCaseUsage;
import org.omg.sysml.lang.sysml.Redefinition;
import org.omg.sysml.lang.sysml.Relationship;
import org.omg.sysml.lang.sysml.ReferenceSubsetting;
import org.omg.sysml.lang.sysml.RenderingUsage;
import org.omg.sysml.lang.sysml.Specialization;
import org.omg.sysml.lang.sysml.StakeholderMembership;
import org.omg.sysml.lang.sysml.StateUsage;
import org.omg.sysml.lang.sysml.SatisfyRequirementUsage;
import org.omg.sysml.lang.sysml.SubjectMembership;
import org.omg.sysml.lang.sysml.Subsetting;
import org.omg.sysml.lang.sysml.Succession;
import org.omg.sysml.lang.sysml.SuccessionFlowUsage;
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

    /**
     * 标准视图定义（限定名）到产物 `view.kind` 的映射；**顺序从具体到一般，命中即返回**。
     *
     * <p>必须是 `List` 而不是 `Map`：`Map.ofEntries` 的迭代顺序由哈希决定，同一个视图
     * （例如 `ActionFlowView`，它特化 `InterconnectionView`）可能被判成 `actionFlow`，
     * 也可能被判成 `interconnection`——产物就不确定了。实测踩到过。
     */
    private static final List<Map.Entry<String, String>> STANDARD_VIEW_KINDS = List.of(
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

    /** 源码片段的最大长度。 */
    private static final int MAX_SNIPPET = 160;

    /** 暴露范围的节点上限，超过就截断并记录原因，避免递归展开失控。 */
    private static final int MAX_SCOPE_NODES = 200;

    /** 需求派生的三个元数据（标准库 `RequirementDerivation` 领域库里的定义名）。 */
    private static final String DERIVATION_METADATA = "DerivationMetadata";
    private static final String DERIVED_METADATA = "DerivedRequirementMetadata";
    private static final String ORIGINAL_METADATA = "OriginalRequirementMetadata";

    /** 元类名里的构造型后缀，照搬官方 `getStereotypeName` 的正则。 */
    private static final Pattern METACLASS_NAME =
            Pattern.compile("^((Enum)(?>eration)|(\\p{L}+?))(Definition|Usage|AsUsage)$");

    /** 驼峰与字母/符号边界插空格，照搬官方的 `convertCamelName`。 */
    private static final Pattern CAMEL_BOUNDARY =
            Pattern.compile("(?<=[A-Z])(?=[A-Z][a-z])|(?<=[^A-Z])(?=[A-Z])|(?<=[A-Za-z])(?=[^A-Za-z])");

    private ViewProductBuilder() {
    }

    public static ViewProduct.Product build(SysMLWorkspace workspace, ViewUsage view) {
        List<Element> exposed = new ArrayList<>(view.getExposedElement());

        // 暴露范围不只是 exposed 本身。官方渲染会往已暴露元素的内部走：把其中的结构特征
        // （部件、端口、有向特征等）也画成框。这里对齐该行为，见契约第 3.2 节。
        boolean[] truncated = {false};
        List<Element> collectedConnectors = new ArrayList<>();
        List<Element> scope = expandScope(workspace, exposed, truncated, collectedConnectors);

        // 暴露顺序本身是稳定的，作为匿名元素之间的最后一级排序依据。
        Map<Element, Integer> exposureOrder = new LinkedHashMap<>();
        for (int i = 0; i < scope.size(); i++) {
            exposureOrder.put(scope.get(i), i);
        }

        // 注意：不能用 elementId 参与排序——Pilot 每次加载都会重新生成随机 UUID，
        // 拿它当身份或排序依据会让产物不可复现。用限定名 + 元类 + 源码偏移 + 暴露顺序。
        scope.sort(Comparator
                .comparing((Element element) -> element.getQualifiedName() == null ? 1 : 0)
                .thenComparing(element -> nullToEmpty(element.getQualifiedName()))
                .thenComparing(element -> element.eClass().getName())
                .thenComparing(ViewProductBuilder::sourceOffsetOf)
                .thenComparing(element -> exposureOrder.getOrDefault(element, Integer.MAX_VALUE)));

        Map<Resource, Integer> documentIds = collectDocuments(workspace, view, scope);

        String kind = viewKindOf(view);
        boolean interconnectionLike = INTERCONNECTION_KINDS.contains(kind);

        // 互联类视图里，连接器不是节点（它要变成边），连接器自己的端也不是节点。
        List<Element> connectors = new ArrayList<>(collectedConnectors);
        List<Element> projected = new ArrayList<>();
        for (Element element : scope) {
            if (isConnector(element)) {
                if (!connectors.contains(element)) {
                    connectors.add(element);
                }
                if (interconnectionLike) {
                    continue;
                }
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

        // 边界元素（端口、有向特征即参数）的附着要在节点集确定之后再算。
        Map<Element, String> boundaryParents = findBoundaryParents(projected);

        List<ViewProduct.NodeRef> nodes = new ArrayList<>(projected.size());
        for (Element element : projected) {
            nodes.add(toNode(workspace, documentIds, nodeIds, boundaryParents, connectors, element));
        }

        List<ViewProduct.RelationshipRef> relationships =
                buildRelationships(nodeIds, connectors, interconnectionLike);

        List<ViewProduct.Reason> reasons = new ArrayList<>();
        if (truncated[0]) {
            reasons.add(new ViewProduct.Reason("scope-truncated",
                    "exposed scope exceeded " + MAX_SCOPE_NODES + " nodes; nested expansion stopped"));
        }
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

    /**
     * 把 exposed 扩展成"暴露范围"：递归收集已暴露元素自有的结构特征。
     *
     * <p>数据特征（无方向的属性、值）不进来，它们以仓格形式呈现。
     */
    private static List<Element> expandScope(SysMLWorkspace workspace,
                                             List<Element> exposed,
                                             boolean[] truncated,
                                             List<Element> connectors) {
        List<Element> scope = new ArrayList<>(exposed);
        Set<Element> seen = new LinkedHashSet<>(exposed);
        Deque<Element> queue = new ArrayDeque<>(exposed);
        while (!queue.isEmpty()) {
            Element current = queue.poll();
            if (!(current instanceof Type type)) {
                continue;
            }
            // 队列里的元素本身也要补继承来的端口/有向特征：
            // 端口 `outlet : FuelOutPort` 的 `fuelSupply` 就是这样进来的。
            if (current instanceof Feature currentFeature) {
                addInheritedPorts(workspace, currentFeature, scope, seen, queue);
            }
            for (Feature feature : type.getOwnedFeature()) {
                // 连接器不进节点集，但要收集起来——它们在互联类视图里是边。
                if (isConnectorFeature(feature)) {
                    if (!connectors.contains(feature)) {
                        connectors.add(feature);
                    }
                    continue;
                }
                // `perform X;` 本身不进节点集（它是边），但**被执行的动作用法要拉进范围**：
                // 否则边的一端落不到节点上，整条 perform 边会消失。
                if (feature instanceof PerformActionUsage perform) {
                    Element performed = perform.getPerformedAction();
                    if (performed instanceof Feature performedFeature && seen.add(performedFeature)) {
                        scope.add(performedFeature);
                        queue.add(performedFeature);
                    }
                    continue;
                }
                if (!isStructuralFeature(feature) || !seen.add(feature)) {
                    continue;
                }
                if (scope.size() >= MAX_SCOPE_NODES) {
                    truncated[0] = true;
                    return scope;
                }
                scope.add(feature);
                queue.add(feature);
                // 官方的渲染会把"从类型继承来的端口"也画在用法上（PUML 里标 `^`），照此补齐。
                addInheritedPorts(workspace, feature, scope, seen, queue);
            }
        }
        return scope;
    }

    /** 把类型闭包里的端口与有向特征补进范围——它们属于定义，但画在用法上。 */
    private static void addInheritedPorts(SysMLWorkspace workspace,
                                          Feature feature,
                                          List<Element> scope,
                                          Set<Element> seen,
                                          Deque<Element> queue) {
        Set<Type> visited = new HashSet<>();
        Deque<Type> types = new ArrayDeque<>(feature.getType());
        while (!types.isEmpty()) {
            Type type = types.poll();
            if (type == null || !visited.add(type)) {
                continue;
            }
            // 只展开用户模型里的类型：库类型（如 Ports::Port）自带的 ownedPorts / subports
            // 属于基础设施，不是模型内容，官方渲染也不会画。
            if (!workspace.isWorkspaceResource(type.eResource())) {
                continue;
            }
            for (Feature owned : type.getOwnedFeature()) {
                if (!isPortOrDirected(owned) || !seen.add(owned)) {
                    continue;
                }
                scope.add(owned);
                queue.add(owned);
            }
            for (Specialization specialization : type.getOwnedSpecialization()) {
                types.add(specialization.getGeneral());
            }
        }
    }

    private static boolean isPortOrDirected(Feature feature) {
        return feature instanceof PortUsage || feature.getDirection() != null;
    }

    /** 会变成边的连接类特征。 */
    private static boolean isConnectorFeature(Feature feature) {
        // 连接器都是"两个元素之间的关系"，在互联类视图里画成边而不是又一个框：
        // - succession：`first A then B` / `then B`
        // - binding：`bind a = b`（官方画成标 `=` 的粗边）
        // 漏掉其中任何一个，视图就会少画一条边（`bind` 曾经就是这样丢的）。
        return feature instanceof ConnectionUsage || feature instanceof FlowUsage
                || feature instanceof Succession || feature instanceof BindingConnector;
    }

    /** 结构特征 = 会画成框的东西；连接器除外（由投影决定它是边还是仓格条目）。 */
    private static boolean isStructuralFeature(Feature feature) {
        if (feature instanceof ConnectionUsage || feature instanceof BindingConnector
                || feature instanceof SatisfyRequirementUsage || feature instanceof PerformActionUsage
                || feature instanceof ConstraintUsage) {
            return false;
        }
        if (feature.getDirection() != null) {
            return true;
        }
        return feature instanceof PortUsage
                || feature instanceof PartUsage
                || feature instanceof ItemUsage
                || feature instanceof ActionUsage
                || feature instanceof StateUsage
                || feature instanceof OccurrenceUsage;
    }

    /** 视图类型：沿视图定义的泛化闭包找标准视图定义。找不到就是 `unclassified`。 */
    private static String viewKindOf(ViewUsage view) {
        ViewDefinition definition = view.getViewDefinition();
        if (definition == null) {
            return "unclassified";
        }
        Set<String> qualifiedNames = new HashSet<>();
        collectSupertypes(definition, qualifiedNames);
        for (Map.Entry<String, String> entry : STANDARD_VIEW_KINDS) {
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
            if (!isBoundaryCandidate(element)) {
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

    /** 会贴在父节点边界上的元素：端口，以及带方向的特征（参数，官方渲染成 portin/portout）。 */
    private static boolean isBoundaryCandidate(Element element) {
        if (!(element instanceof Feature feature)) {
            return false;
        }
        return feature.getDirection() != null || "port".equals(graphicOf(element.eClass().getName()));
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
                                              List<Element> connectors,
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
                typeNamesOf(element),
                element instanceof RequirementUsage requirement ? blankToNull(requirement.getReqId()) : null,
                compartmentsOf(element, nodeIds, connectors));
    }

    /**
     * 仓格内容：元素自有的特征，按标题分组。已经画成节点的特征（部件、端口）不再列进仓格，
     * 否则同一个元素会同时以节点和条目两种形态出现。
     *
     * <p>标题规则照搬官方渲染实现（`org.omg.sysml.plantuml.CompartmentEntry.getTitle()`）。
     */
    private static List<ViewProduct.CompartmentRef> compartmentsOf(Element element,
                                                                   Map<Element, String> nodeIds,
                                                                   List<Element> connectors) {
        if (!(element instanceof Type type)) {
            return List.of();
        }
        List<Feature> features = new ArrayList<>(type.getOwnedFeature());
        features.removeIf(nodeIds::containsKey);
        features.removeIf(connectors::contains);
        features.removeIf(Feature::isEnd);
        features.removeIf(SatisfyRequirementUsage.class::isInstance);
        features.removeIf(PerformActionUsage.class::isInstance);
        features.sort(Comparator
                .comparing((Feature feature) -> feature.getDirection() == null ? 1 : 0)
                .thenComparing(feature -> feature.getDirection() == null
                        ? Integer.MAX_VALUE
                        : feature.getDirection().getValue())
                .thenComparing(feature -> feature.eClass().getName())
                .thenComparing(feature -> nullToEmpty(feature.getName())));

        Map<String, List<ViewProduct.EntryRef>> groups = new LinkedHashMap<>();
        for (Feature feature : features) {
            groups.computeIfAbsent(titleOf(feature), key -> new ArrayList<>()).add(entryOf(feature));
        }

        // 值（`= 表达式`）挂在特征自己的 ownedRelationship 上，不在元素的 ownedFeature 里，
        // 所以要顺着特征再找一遍。
        for (Feature feature : collectValueFeatures(type, nodeIds, connectors)) {
            for (Relationship relationship : feature.getOwnedRelationship()) {
                if (relationship instanceof FeatureValue value) {
                    String text = valueTextOf(value);
                    if (text != null) {
                        groups.computeIfAbsent("values", key -> new ArrayList<>())
                                .add(new ViewProduct.EntryRef(text, qualifiedNameOrNull(feature), null, null));
                    }
                }
            }
        }

        List<ViewProduct.CompartmentRef> compartments = new ArrayList<>(groups.size());
        for (Map.Entry<String, List<ViewProduct.EntryRef>> group : groups.entrySet()) {
            compartments.add(new ViewProduct.CompartmentRef(group.getKey(), List.copyOf(group.getValue())));
        }
        compartments.sort(Comparator.comparing(ViewProduct.CompartmentRef::title));

        // 文档（doc）单独成一个仓格：官方渲染也把它放在节点下方独立区域
        List<ViewProduct.EntryRef> docs = new ArrayList<>();
        for (Documentation documentation : element.getDocumentation()) {
            String body = blankToNull(collapse(documentation.getBody()));
            if (body != null) {
                docs.add(new ViewProduct.EntryRef(body, null, null, null));
            }
        }
        if (!docs.isEmpty()) {
            compartments.add(new ViewProduct.CompartmentRef("documentation", List.copyOf(docs)));
        }
        return List.copyOf(compartments);
    }

    private static String collapse(String text) {
        return text == null ? null : text.replaceAll("\\s+", " ").trim();
    }

    /** 找出需要把值列进仓格的特征：自己不是节点、也不是已变成边的连接器。 */
    private static List<Feature> collectValueFeatures(Type type,
                                                      Map<Element, String> nodeIds,
                                                      List<Element> connectors) {
        List<Feature> features = new ArrayList<>();
        for (Feature feature : type.getOwnedFeature()) {
            if (nodeIds.containsKey(feature) || connectors.contains(feature)) {
                continue;
            }
            features.add(feature);
        }
        for (Relationship relationship : type.getOwnedRelationship()) {
            if (relationship instanceof FeatureValue value && value.getFeatureWithValue() != null
                    && !nodeIds.containsKey(value.getFeatureWithValue())
                    && !connectors.contains(value.getFeatureWithValue())) {
                features.add(value.getFeatureWithValue());
            }
        }
        return features;
    }

    private static ViewProduct.EntryRef entryOf(Feature feature) {
        String name = blankToNull(feature.getName());
        String typeText = simpleTypeNamesOf(feature);
        String text;
        if (name == null) {
            text = typeText == null ? feature.eClass().getName() : typeText;
        } else {
            text = typeText == null ? name : name + ": " + typeText;
        }
        return new ViewProduct.EntryRef(text, qualifiedNameOrNull(feature), directionOf(feature), null);
    }

    /** 仓格里的类型用简单名（与官方 PUML 输出一致）；精确引用由条目的 `ref` 字段承载。 */
    private static String simpleTypeNamesOf(Feature feature) {
        List<String> names = new ArrayList<>();
        for (Type type : feature.getType()) {
            String name = blankToNull(type.getName());
            if (name == null) {
                name = qualifiedNameOrNull(type);
            }
            if (name != null) {
                names.add(name);
            }
        }
        return names.isEmpty() ? null : String.join(", ", names);
    }

    private static String valueTextOf(FeatureValue value) {
        Feature feature = value.getFeatureWithValue();
        String name = feature == null ? null : blankToNull(feature.getName());
        String expressionText = null;
        Expression expression = value.getValue();
        if (expression != null) {
            INode node = NodeModelUtils.findActualNodeFor(expression);
            if (node != null) {
                expressionText = blankToNull(node.getText().trim());
            }
        }
        if (name == null && expressionText == null) {
            return null;
        }
        if (name == null) {
            return expressionText;
        }
        if (expressionText == null) {
            return name;
        }
        String operator = value.isInitial() ? " := " : value.isDefault() ? " default " : " = ";
        return name + operator + expressionText;
    }

    private static String directionOf(Feature feature) {
        FeatureDirectionKind direction = feature.getDirection();
        if (direction == null) {
            return null;
        }
        return switch (direction) {
            case IN -> "in";
            case OUT -> "out";
            case INOUT -> "inout";
            default -> null;
        };
    }

    private static String titleOf(Feature feature) {
        OwningMembership membership = feature.getOwningMembership();
        if (membership instanceof FeatureValue) {
            return "values";
        }
        if (membership instanceof SubjectMembership) {
            return "subject";
        }
        if (membership instanceof ActorMembership) {
            return "actors";
        }
        if (membership instanceof StakeholderMembership) {
            return "stakeholders";
        }
        if (membership instanceof ObjectiveMembership) {
            return "objectives";
        }
        if (feature instanceof BindingConnector) {
            return "bindings";
        }
        if (feature instanceof SuccessionFlowUsage) {
            return "succession flows";
        }
        if (feature instanceof FlowUsage) {
            return "flows";
        }
        if (feature.getDirection() != null) {
            return "parameters";
        }
        return pluralize(stereotypeOf(feature.eClass().getName()));
    }

    /**
     * 元类名里的"构造型名"，规则照搬官方 `SysML2PlantUMLText.getStereotypeName`：
     *
     * <pre>
     * ^((Enum)(?&gt;eration)|(\p{L}+?))(Definition|Usage|AsUsage)$
     * </pre>
     *
     * <p>三个容易写错的点，都是踩过的坑：
     *
     * <ul>
     *   <li>后缀有 **三个**：`Definition` / `Usage` / `AsUsage`。只去 `Usage` 会把
     *       `SuccessionAsUsage` 拆成 `succession as`，复数化后得到 `succession ases`；</li>
     *   <li>裸 `Usage` / `Definition` **不匹配**该正则（前面至少要有一个字母），要走整名小写，
     *       否则拆出空串、复数化成 `s`；</li>
     *   <li>`Enumeration*` 取 `Enum` 而不是 `Enumeration`。</li>
     * </ul>
     */
    private static String stereotypeOf(String metaclass) {
        String base = metaclass;
        Matcher matcher = METACLASS_NAME.matcher(base);
        if (matcher.matches()) {
            base = matcher.group(2) != null ? matcher.group(2) : matcher.group(3);
            if ("Definition".equals(matcher.group(4))) {
                base = base + " def";
            }
        }
        return CAMEL_BOUNDARY.matcher(base).replaceAll(" ").toLowerCase(Locale.ROOT);
    }

    private static String pluralize(String word) {
        if (word.endsWith("s")) {
            return word + "es";
        }
        if (word.endsWith("data")) {
            return word;
        }
        return word + "s";
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

        // 连接器要么是节点、要么是边，不会两者都是——否则同一个事实会被画两遍。
        // 互联类视图里连接器从不成节点（一律是边）；general 视图里只有被显式暴露的连接器才是节点，
        // 由递归展开发现的那些仍然画成边。
        for (Element element : connectors) {
            if (!(element instanceof Connector connector)) {
                continue;
            }
            if (!interconnectionLike && nodeIds.containsKey(element)) {
                continue;
            }
            // 端点由 reference subsetting / 特征链解析到真实特征
            List<Feature> ends = connector.getConnectorEnd();
            if (ends.size() != 2) {
                continue;
            }
            String sourceId = nodeIdFor(resolveConnectorEnd(ends.get(0), 0), nodeIds);
            String targetId = nodeIdFor(resolveConnectorEnd(ends.get(1), 0), nodeIds);
            if (sourceId != null && targetId != null) {
                addEdge(edges, new Edge(connectorKind(element), sourceId, targetId, true));
            }
        }

        collectSatisfyEdges(nodeIds, edges);
        collectVerifyEdges(nodeIds, edges);
        collectPerformEdges(nodeIds, edges);
        collectDeriveEdges(nodeIds, edges);

        List<Edge> sorted = new ArrayList<>(edges.values());
        sorted.sort(Comparator
                .comparing(Edge::kind)
                .thenComparing(edge -> edge.source(), endComparator())
                .thenComparing(edge -> edge.target(), endComparator()));

        List<ViewProduct.RelationshipRef> relationships = new ArrayList<>(sorted.size());
        for (int i = 0; i < sorted.size(); i++) {
            Edge edge = sorted.get(i);
            relationships.add(new ViewProduct.RelationshipRef(
                    "r" + (i + 1), edge.kind(), edge.source(), edge.target(), edge.authored()));
        }
        return relationships;
    }

    /**
     * 连接类元素的边类型：分配、流，其余按普通连接处理。
     */
    private static String connectorKind(Element element) {
        if (element instanceof AllocationUsage) {
            return "allocate";
        }
        if (element instanceof FlowUsage) {
            return "flow";
        }
        // 时序（`first A then B` / `then B`）：官方在行为视图里画成带 `then` 的箭头
        if (element instanceof Succession) {
            return "succession";
        }
        // 绑定（`bind a = b`）：官方画成标 `=` 的粗边
        if (element instanceof BindingConnector) {
            return "binding";
        }
        return "connection";
    }

    /**
     * `satisfy` 边：由满足方（`satisfyingFeature`，缺省是承载 satisfy 的那个元素）指向被满足的需求。
     *
     * <p>`satisfy X;` 语句本身是满足方的自有特征，官方渲染把它画成带 `«satisfy»` 标签的边而不是
     * 节点；我们对齐该行为，因此这类特征也不进仓格。
     */
    private static void collectSatisfyEdges(Map<Element, String> nodeIds, Map<String, Edge> edges) {
        for (Map.Entry<Element, String> entry : nodeIds.entrySet()) {
            if (!(entry.getKey() instanceof Type type)) {
                continue;
            }
            for (Feature feature : type.getOwnedFeature()) {
                if (!(feature instanceof SatisfyRequirementUsage satisfy)) {
                    continue;
                }
                Element satisfying = satisfy.getSatisfyingFeature() != null ? satisfy.getSatisfyingFeature() : type;
                String sourceId = nodeIdFor(satisfying, nodeIds);
                String targetId = nodeIdFor(satisfy.getSatisfiedRequirement(), nodeIds);
                if (sourceId != null && targetId != null) {
                    boolean authored = NodeModelUtils.findActualNodeFor(satisfy) != null;
                    addEdge(edges, new Edge("satisfy", sourceId, targetId, authored));
                }
            }
        }
    }

    /**
     * `verify` 边：验证用例 → 被验证的需求。
     *
     * <p>模型里写的是 `objective { verify req; }`，语义上由
     * `RequirementVerificationMembership` 承载。优先用派生查询
     * `VerificationCaseUsage.getVerifiedRequirement()`（它会把嵌套的目标成员关系一并算出来），
     * 再用成员关系扫描兜底——两种写法都能覆盖。
     */
    private static void collectVerifyEdges(Map<Element, String> nodeIds, Map<String, Edge> edges) {
        for (Map.Entry<Element, String> entry : nodeIds.entrySet()) {
            Element element = entry.getKey();
            if (element instanceof VerificationCaseUsage verification) {
                for (RequirementUsage verified : verification.getVerifiedRequirement()) {
                    addEdgeIfResolved(edges, nodeIds, "verify", element, verified, element);
                }
            }
            for (Relationship relationship : element.getOwnedRelationship()) {
                if (relationship instanceof RequirementVerificationMembership membership) {
                    // 边要从**验证用例**出发，而不是从 `objective { … }` 生成的包装用法出发：
                    // 包装用法是编译器产物（Pilot 给它起名叫 `obj`，官方渲染器同样特殊处理它）。
                    addEdgeIfResolved(edges, nodeIds, "verify", enclosingCase(element),
                            membership.getVerifiedRequirement(), membership);
                }
            }
        }
    }

    /** 向上找承载这条验证的验证用例（定义或用法）；找到才是真正的验证方。 */
    private static Element enclosingCase(Element element) {
        for (Element current = element; current != null; current = current.getOwner()) {
            if (current instanceof VerificationCaseUsage || current instanceof VerificationCaseDefinition) {
                return current;
            }
        }
        return null;
    }

    /**
     * `perform` 边：执行方（部件/动作）→ 被执行的动作用法。
     *
     * <p>`perform X;` 语句本身不进节点集（否则同一个事实会同时是节点和边），
     * 与 `satisfy` / 连接器的处理方式一致。
     */
    private static void collectPerformEdges(Map<Element, String> nodeIds, Map<String, Edge> edges) {
        for (Map.Entry<Element, String> entry : nodeIds.entrySet()) {
            // 与 satisfy 一样：perform 语句是**节点的自有特征**，不是节点本身
            if (!(entry.getKey() instanceof Type type)) {
                continue;
            }
            for (Feature feature : type.getOwnedFeature()) {
                if (!(feature instanceof PerformActionUsage perform)) {
                    continue;
                }
                // 被执行的动作用法可能不在节点集里；退回 reference subsetting / 特征链解析
                Element performed = perform.getPerformedAction();
                if (performed == null || nodeIdFor(performed, nodeIds) == null) {
                    performed = resolveConnectorEnd(perform, 0);
                }
                addEdgeIfResolved(edges, nodeIds, "perform", perform.getOwner(), performed, perform);
            }
        }
    }

    /**
     * `derive` 边：派生需求 → 原需求（依赖方 → 被依赖方，与 `typing` 等一致）。
     *
     * <p>需求派生在 SysML v2 里不是关键字，而是标准库 `RequirementDerivation` 的元数据：
     *
     * <pre>
     * #derivation connection {
     *     end #original ::> vehicleMassRequirement;
     *     end #derive ::> chassisMassRequirement;
     * }
     * </pre>
     *
     * 所以判据是"连接器带 `#derivation`，端带 `#original` / `#derive`"。连接器本身不必在
     * 节点集里——它承载的是两个需求之间的关系，只要两端都在，边就成立。
     */
    private static void collectDeriveEdges(Map<Element, String> nodeIds, Map<String, Edge> edges) {
        Set<Element> scanned = new LinkedHashSet<>();
        for (Element element : nodeIds.keySet()) {
            for (Element namespace = element.getOwner(); namespace != null; namespace = namespace.getOwner()) {
                if (!scanned.add(namespace)) {
                    // 属主链是共享的：这条链已经扫过，再往上只会重复
                    break;
                }
                for (Relationship relationship : namespace.getOwnedRelationship()) {
                    for (Element member : relationship.getOwnedRelatedElement()) {
                        if (member instanceof Connector connector
                                && metadataTypeOf(connector, DERIVATION_METADATA) != null) {
                            addDerivationEdges(connector, nodeIds, edges);
                        }
                    }
                }
            }
        }
    }

    /** 一条派生连接可以有多个原需求端与多个派生端，逐对出边。 */
    private static void addDerivationEdges(Connector connector,
                                           Map<Element, String> nodeIds,
                                           Map<String, Edge> edges) {
        List<Element> originals = new ArrayList<>();
        List<Element> derived = new ArrayList<>();
        for (Feature end : connector.getConnectorEnd()) {
            Element target = resolveConnectorEnd(end, 0);
            if (target == null) {
                continue;
            }
            String tag = derivationTagOf(end, connector);
            if (DERIVED_METADATA.equals(tag)) {
                derived.add(target);
            } else if (ORIGINAL_METADATA.equals(tag)) {
                originals.add(target);
            }
        }
        for (Element original : originals) {
            for (Element derivedElement : derived) {
                addEdgeIfResolved(edges, nodeIds, "derive", derivedElement, original, connector);
            }
        }
    }

    /**
     * 端的派生标签。用法上的端常常没写元数据，此时按名字沿用连接定义里那个端的标签
     * （`Requirements Examples/RequirementDerivationExample.sysml` 就是这个写法）。
     */
    private static String derivationTagOf(Feature end, Connector connector) {
        String tag = metadataTypeOf(end, DERIVED_METADATA, ORIGINAL_METADATA);
        if (tag != null || end.getName() == null) {
            return tag;
        }
        for (Type type : connector.getType()) {
            for (Feature candidate : type.getOwnedFeature()) {
                if (!candidate.isEnd() || !end.getName().equals(candidate.getName())) {
                    continue;
                }
                tag = metadataTypeOf(candidate, DERIVED_METADATA, ORIGINAL_METADATA);
                if (tag != null) {
                    return tag;
                }
            }
        }
        return null;
    }

    /** 元素上命中的元数据类型名（没有就返回 null）。 */
    private static String metadataTypeOf(Element element, String... names) {
        for (MetadataFeature metadata : metadataFeaturesOf(element)) {
            for (Type type : metadata.getType()) {
                String name = blankToNull(type.getName());
                for (String candidate : names) {
                    if (candidate.equals(name)) {
                        return name;
                    }
                }
            }
        }
        return null;
    }

    /** 元素自有的元数据用法，即 `#name` 这类前缀注解。 */
    private static List<MetadataFeature> metadataFeaturesOf(Element element) {
        List<MetadataFeature> features = new ArrayList<>();
        for (Relationship relationship : element.getOwnedRelationship()) {
            for (Element related : relationship.getOwnedRelatedElement()) {
                if (related instanceof MetadataFeature metadata && !features.contains(metadata)) {
                    features.add(metadata);
                }
            }
            if (relationship instanceof Annotation annotation
                    && annotation.getAnnotatingElement() instanceof MetadataFeature metadata
                    && !features.contains(metadata)) {
                features.add(metadata);
            }
        }
        return features;
    }

    /** 两端都能落到节点上才出边。 */
    private static void addEdgeIfResolved(Map<String, Edge> edges,
                                          Map<Element, String> nodeIds,
                                          String kind,
                                          Element source,
                                          Element target,
                                          Element authoredSource) {
        String sourceId = source == null ? null : nodeIdFor(source, nodeIds);
        String targetId = target == null ? null : nodeIdFor(target, nodeIds);
        if (sourceId == null || targetId == null) {
            return;
        }
        boolean authored = authoredSource != null && NodeModelUtils.findActualNodeFor(authoredSource) != null;
        addEdge(edges, new Edge(kind, sourceId, targetId, authored));
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

    /**
     * 端点排序：产物里端点是节点编号（`n1`…），索引里端点是限定名，两种都要能排。
     * 编号按数值排（`n2` 在 `n10` 前），其余按字典序。
     */
    private static Comparator<String> endComparator() {
        return (left, right) -> {
            boolean bothIds = isNodeId(left) && isNodeId(right);
            return bothIds
                    ? Integer.compare(nodeNumber(left), nodeNumber(right))
                    : left.compareTo(right);
        };
    }

    private static boolean isNodeId(String value) {
        if (value == null || value.length() < 2 || value.charAt(0) != 'n') {
            return false;
        }
        for (int i = 1; i < value.length(); i++) {
            if (!Character.isDigit(value.charAt(i))) {
                return false;
            }
        }
        return true;
    }

    /**
     * 供**全模型索引**复用同一套关系推导规则：键换成元素的稳定引用（`ref`）即可。
     *
     * <p>产物与索引共用这里，保证"索引里的关系"和"视图里的关系"永远出自同一份规则，
     * 不会各写一套后慢慢漂移。
     */
    public static List<ViewProduct.RelationshipRef> deriveRelations(Map<Element, String> idByElement,
                                                                    List<Element> connectors,
                                                                    boolean includeConnectors) {
        return buildRelationships(idByElement, connectors, includeConnectors);
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
        // 用 getOffset/getLength 而不是 getTotalOffset/getTotalLength：后者包含节点前面的隐藏
        // token（注释、空白），会把上一行的注释也算进元素范围。
        return new ViewProduct.SourceRef(documentId, node.getStartLine(), node.getOffset(),
                node.getLength(), snippetOf(node));
    }

    /**
     * 元素原文片段：折叠空白并截断，避免产物被大段文本撑开。
     *
     * <p>注意不能直接用 {@code node.getText()}：复合节点的 getText() 会连同隐藏子节点（注释、
     * 空白）一起返回，而 getOffset()/getLength() 只覆盖元素本身，两者口径不一致。这里按
     * offset/length 从文档原文里截取。
     */
    private static String snippetOf(INode node) {
        INode root = node.getRootNode();
        if (root == null) {
            return null;
        }
        String document = root.getText();
        int from = Math.min(node.getOffset(), document.length());
        int to = Math.min(from + node.getLength(), document.length());
        String collapsed = document.substring(from, to).replaceAll("\\s+", " ").trim();
        if (collapsed.isEmpty()) {
            return null;
        }
        return collapsed.length() <= MAX_SNIPPET
                ? collapsed
                : collapsed.substring(0, MAX_SNIPPET - 1) + "\u2026";
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
