package io.github.zihuizh.sysmlplot.view;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 模型查询层：索引之上的遍历。
 *
 * <p>阶段 3 的四个消费者共用它，而不是各写一套遍历（见 `docs/archive/PHASE-3-PLAN.md` §3.2）：
 *
 * <table>
 *   <tr><td>关系列表（1 跳）</td><td>{@link #neighbors}</td></tr>
 *   <tr><td>局部关系视图</td><td>{@link #subgraph}</td></tr>
 *   <tr><td>影响范围分析</td><td>{@link #impact}</td></tr>
 *   <tr><td>跨视图跳转</td><td>{@link #viewsOf}</td></tr>
 * </table>
 *
 * <p>默认**过滤掉隐式元素**：实测递归展开会把匿名多重性、库继承特征一起带进来，
 * 影响范围分析如果不滤，会把与改动无关的东西全算成"受影响"。
 */
public final class ModelQuery {

    /**
     * 影响分析的默认边类型：**排除 containment**。
     *
     * <p>包含关系是结构，不是依赖——改了需求不该"影响"它所在的包。实测第一版把包也算成
     * 受影响元素，属于误报。
     */
    private static final Set<String> INFLUENCE_KINDS = Set.of(
            "typing", "specialization", "subsetting", "redefinition",
            "satisfy", "verify", "derive", "allocate", "flow", "connection",
            "perform", "succession");

    /** 一跳邻居。{@code outgoing} 表示这条边从起点指向邻居。 */
    public record Neighbor(String ref, String name, String metaclass, String kind,
                           boolean outgoing, boolean authored) {
    }

    /** 影响范围里的一条命中：谁、几步之外、通过什么关系。 */
    public record Impact(String ref, String name, String metaclass, int depth, String viaKind) {
    }

    /** 以某元素为中心的局部子图。 */
    public record Subgraph(String center, Set<String> nodes, List<WorkspaceIndex.RelationEntry> edges) {
    }

    private ModelQuery() {
    }

    /** 一跳邻居，正反都算。 */
    public static List<Neighbor> neighbors(WorkspaceIndex.Index index, String ref) {
        Map<String, WorkspaceIndex.ElementEntry> elements = byRef(index);
        List<Neighbor> result = new ArrayList<>();
        for (WorkspaceIndex.RelationEntry relation : index.relations()) {
            boolean outgoing;
            String other;
            if (ref.equals(relation.source())) {
                outgoing = true;
                other = relation.target();
            } else if (ref.equals(relation.target())) {
                outgoing = false;
                other = relation.source();
            } else {
                continue;
            }
            WorkspaceIndex.ElementEntry element = elements.get(other);
            result.add(new Neighbor(other,
                    element == null ? null : element.name(),
                    element == null ? null : element.metaclass(),
                    relation.kind(),
                    outgoing,
                    relation.authored()));
        }
        result.sort(Comparator.comparing(Neighbor::kind).thenComparing(Neighbor::ref));
        return result;
    }

    /**
     * 影响范围：从 ref 出发沿**反向**边做可达，得到"改了它会牵连谁"。
     *
     * <p>方向约定：边 `<source> --kind--> <target>` 表示 source 依赖/实现/满足 target，
     * 所以改动 target 会牵连 source。
     */
    public static List<Impact> impact(WorkspaceIndex.Index index, String ref, int maxDepth) {
        return impact(index, ref, maxDepth, INFLUENCE_KINDS);
    }

    /** 影响范围，可指定要跟随的边类型。 */
    public static List<Impact> impact(WorkspaceIndex.Index index, String ref, int maxDepth, Set<String> kinds) {
        Map<String, List<WorkspaceIndex.RelationEntry>> incoming = new HashMap<>();
        for (WorkspaceIndex.RelationEntry relation : index.relations()) {
            if (!kinds.contains(relation.kind())) {
                continue;
            }
            incoming.computeIfAbsent(relation.target(), key -> new ArrayList<>()).add(relation);
        }
        Map<String, WorkspaceIndex.ElementEntry> elements = byRef(index);

        List<Impact> result = new ArrayList<>();
        Set<String> visited = new HashSet<>();
        visited.add(ref);
        Deque<String> frontier = new ArrayDeque<>();
        frontier.add(ref);
        for (int depth = 1; depth <= maxDepth && !frontier.isEmpty(); depth++) {
            Deque<String> next = new ArrayDeque<>();
            while (!frontier.isEmpty()) {
                String current = frontier.poll();
                for (WorkspaceIndex.RelationEntry relation : incoming.getOrDefault(current, List.of())) {
                    if (!visited.add(relation.source())) {
                        continue;
                    }
                    WorkspaceIndex.ElementEntry element = elements.get(relation.source());
                    result.add(new Impact(relation.source(),
                            element == null ? null : element.name(),
                            element == null ? null : element.metaclass(),
                            depth,
                            relation.kind()));
                    next.add(relation.source());
                }
            }
            frontier = next;
        }
        result.sort(Comparator.comparingInt(Impact::depth).thenComparing(Impact::ref));
        return result;
    }

    /** 以 ref 为中心、N 跳内的子图（节点集合 + 两端都在集合里的边）。 */
    public static Subgraph subgraph(WorkspaceIndex.Index index, String ref, int depth) {
        Map<String, Set<String>> adjacency = new HashMap<>();
        for (WorkspaceIndex.RelationEntry relation : index.relations()) {
            adjacency.computeIfAbsent(relation.source(), key -> new LinkedHashSet<>()).add(relation.target());
            adjacency.computeIfAbsent(relation.target(), key -> new LinkedHashSet<>()).add(relation.source());
        }

        Set<String> nodes = new LinkedHashSet<>();
        nodes.add(ref);
        Deque<String> frontier = new ArrayDeque<>();
        frontier.add(ref);
        for (int level = 0; level < depth && !frontier.isEmpty(); level++) {
            Deque<String> next = new ArrayDeque<>();
            while (!frontier.isEmpty()) {
                String current = frontier.poll();
                for (String neighbour : adjacency.getOrDefault(current, Set.of())) {
                    if (nodes.add(neighbour)) {
                        next.add(neighbour);
                    }
                }
            }
            frontier = next;
        }

        List<WorkspaceIndex.RelationEntry> edges = new ArrayList<>();
        for (WorkspaceIndex.RelationEntry relation : index.relations()) {
            if (nodes.contains(relation.source()) && nodes.contains(relation.target())) {
                edges.add(relation);
            }
        }
        return new Subgraph(ref, nodes, List.copyOf(edges));
    }

    /** 某个元素出现在哪些视图。 */
    public static List<String> viewsOf(WorkspaceIndex.Index index, String ref) {
        WorkspaceIndex.ElementEntry element = byRef(index).get(ref);
        return element == null ? List.of() : element.views();
    }

    /** 取元素条目（名字、元类、来源位置、所属视图）。 */
    public static WorkspaceIndex.ElementEntry elementOf(WorkspaceIndex.Index index, String ref) {
        return byRef(index).get(ref);
    }

    private static Map<String, WorkspaceIndex.ElementEntry> byRef(WorkspaceIndex.Index index) {
        Map<String, WorkspaceIndex.ElementEntry> elements = new LinkedHashMap<>();
        for (WorkspaceIndex.ElementEntry element : index.elements()) {
            elements.put(element.ref(), element);
        }
        return elements;
    }
}
