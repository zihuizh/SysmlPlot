package io.github.zihuizh.sysmlplot.view;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 需求追溯矩阵：以需求为行、以关系为列，回答"这条需求由谁满足、被谁验证、从哪儿派生"。
 *
 * <p>它建立在 {@link WorkspaceIndex} 之上——矩阵是**全模型**的，不属于某一个视图，
 * 所以不依赖 `expose`。行只取需求用法（`RequirementUsage`）：需求的**定义**
 * （`requirement def`）是被复用的模板，它自己不需要被满足，把它算成缺口是误报。
 *
 * <p>输出刻意做成**稀疏列表**而不是网格：真实模型里需求成百上千，网格大部分是空格子，
 * 既看不清也没法读。稀疏列表只写有内容的格子，并按所属包分块——这与
 * `docs/PHASE-3-PLAN.md` 里 S3-7 的做法一致。
 */
public final class TraceMatrix {

    /**
     * 编译器生成的包装用法所属的成员关系。它们**不是**被建模的需求：
     * `objective { … }` 会生成一个需求用法（Pilot 起名叫 `obj`，官方渲染器同样特殊处理它），
     * `verify X;` 又会生成一个被验证需求的副本。把它们算成需求行，覆盖率会全是假缺口。
     */
    private static final List<String> WRAPPER_MEMBERSHIPS = List.of(
            "ObjectiveMembership", "RequirementVerificationMembership");

    /** 矩阵的一行（一条需求）。列表都已排序，保证同样的输入产出同样的文本。 */
    public record Row(String ref,
                      String name,
                      String reqId,
                      String owner,
                      String file,
                      int line,
                      List<String> satisfiedBy,
                      List<String> verifiedBy,
                      List<String> derivedFrom,
                      List<String> derivedBy) {

        /** 既没人满足、也没人验证 = 缺口。 */
        public boolean hasGap() {
            return satisfiedBy.isEmpty() && verifiedBy.isEmpty();
        }

        /** 行标题里的编号：有需求号用需求号，没有就用元素名。 */
        public String label() {
            if (reqId != null) {
                return "[" + reqId + "] " + (name == null ? ref : name);
            }
            return name == null ? ref : name;
        }
    }

    public record Matrix(String modelDigest,
                         int requirements,
                         int satisfied,
                         int verified,
                         int gaps,
                         List<Row> rows) {
    }

    private TraceMatrix() {
    }

    /** 全模型建矩阵。 */
    public static Matrix build(WorkspaceIndex.Index index) {
        List<Row> rows = new ArrayList<>();

        for (WorkspaceIndex.ElementEntry element : index.elements()) {
            if (!"RequirementUsage".equals(element.metaclass())) {
                continue;
            }
            if (WRAPPER_MEMBERSHIPS.contains(element.ownerMembership())) {
                continue;
            }
            List<String> satisfiedBy = new ArrayList<>();
            List<String> verifiedBy = new ArrayList<>();
            List<String> derivedFrom = new ArrayList<>();
            List<String> derivedBy = new ArrayList<>();
            for (WorkspaceIndex.RelationEntry relation : index.relations()) {
                if (element.ref().equals(relation.target())) {
                    switch (relation.kind()) {
                        case "satisfy" -> satisfiedBy.add(relation.source());
                        case "verify" -> verifiedBy.add(relation.source());
                        case "derive" -> derivedBy.add(relation.source());
                        default -> {
                        }
                    }
                } else if (element.ref().equals(relation.source()) && "derive".equals(relation.kind())) {
                    derivedFrom.add(relation.target());
                }
            }
            satisfiedBy.sort(Comparator.naturalOrder());
            verifiedBy.sort(Comparator.naturalOrder());
            derivedFrom.sort(Comparator.naturalOrder());
            derivedBy.sort(Comparator.naturalOrder());

            String file = null;
            int line = 0;
            if (element.source() != null) {
                file = shortName(element.source().uri());
                line = element.source().line();
            }
            rows.add(new Row(element.ref(), element.name(), element.reqId(), ownerOf(element.ref()),
                    file, line, List.copyOf(satisfiedBy), List.copyOf(verifiedBy),
                    List.copyOf(derivedFrom), List.copyOf(derivedBy)));
        }

        rows.sort(Comparator.comparing(Row::owner)
                .thenComparing(row -> row.reqId() == null ? "" : row.reqId())
                .thenComparing(Row::ref));

        int satisfied = 0;
        int verified = 0;
        int gaps = 0;
        for (Row row : rows) {
            if (!row.satisfiedBy().isEmpty()) {
                satisfied++;
            }
            if (!row.verifiedBy().isEmpty()) {
                verified++;
            }
            if (row.hasGap()) {
                gaps++;
            }
        }
        return new Matrix(index.modelDigest(), rows.size(), satisfied, verified, gaps, List.copyOf(rows));
    }

    /** 只留下有缺口的行。 */
    public static Matrix gapsOnly(Matrix matrix) {
        List<Row> gaps = new ArrayList<>();
        for (Row row : matrix.rows()) {
            if (row.hasGap()) {
                gaps.add(row);
            }
        }
        return new Matrix(matrix.modelDigest(), matrix.requirements(), matrix.satisfied(),
                matrix.verified(), gaps.size(), List.copyOf(gaps));
    }

    /** 稀疏列表 + 按包分块。同样的输入产出逐字相同的文本。 */
    public static String toText(Matrix matrix) {
        StringBuilder text = new StringBuilder();
        text.append(String.format("[matrix] requirements=%d satisfied=%d verified=%d gaps=%d%n",
                matrix.requirements(), matrix.satisfied(), matrix.verified(), matrix.gaps()));
        if (matrix.rows().isEmpty()) {
            text.append("（本次没有可列出的需求行；用 --gaps-only 时表示没有缺口）")
                    .append(System.lineSeparator());
            return text.toString();
        }

        Map<String, List<Row>> byOwner = new LinkedHashMap<>();
        for (Row row : matrix.rows()) {
            byOwner.computeIfAbsent(row.owner(), key -> new ArrayList<>()).add(row);
        }
        for (Map.Entry<String, List<Row>> block : byOwner.entrySet()) {
            List<Row> rows = block.getValue();
            int satisfied = 0;
            int verified = 0;
            int gaps = 0;
            for (Row row : rows) {
                if (!row.satisfiedBy().isEmpty()) {
                    satisfied++;
                }
                if (!row.verifiedBy().isEmpty()) {
                    verified++;
                }
                if (row.hasGap()) {
                    gaps++;
                }
            }
            text.append(System.lineSeparator())
                    .append("## ").append(block.getKey())
                    .append(String.format("  (需求 %d / 已满足 %d / 已验证 %d / 缺口 %d)%n",
                            rows.size(), satisfied, verified, gaps));
            for (Row row : rows) {
                text.append("- ").append(row.label());
                if (row.file() != null) {
                    text.append("  (").append(row.file()).append(':').append(row.line()).append(')');
                }
                if (row.hasGap()) {
                    text.append("  [缺口]");
                }
                text.append(System.lineSeparator());
                if (!row.satisfiedBy().isEmpty()) {
                    text.append("    满足方: ").append(String.join(", ", shortRefs(row.satisfiedBy())))
                            .append(System.lineSeparator());
                }
                if (!row.verifiedBy().isEmpty()) {
                    text.append("    验证方: ").append(String.join(", ", shortRefs(row.verifiedBy())))
                            .append(System.lineSeparator());
                }
                if (!row.derivedFrom().isEmpty()) {
                    text.append("    派生自: ").append(String.join(", ", shortRefs(row.derivedFrom())))
                            .append(System.lineSeparator());
                }
                if (!row.derivedBy().isEmpty()) {
                    text.append("    派生出: ").append(String.join(", ", shortRefs(row.derivedBy())))
                            .append(System.lineSeparator());
                }
            }
        }
        return text.toString();
    }

    /** 文本里用简单名，限定名太长会把稀疏列表挤成两行。 */
    private static List<String> shortRefs(List<String> refs) {
        List<String> shortRefs = new ArrayList<>(refs.size());
        for (String ref : refs) {
            shortRefs.add(simpleName(ref));
        }
        return shortRefs;
    }

    private static String simpleName(String ref) {
        int index = ref.lastIndexOf("::");
        return index < 0 ? ref : ref.substring(index + 2);
    }

    /** 所属包：限定名去掉最后一段。 */
    private static String ownerOf(String ref) {
        int index = ref.lastIndexOf("::");
        return index < 0 ? "(root)" : ref.substring(0, index);
    }

    private static String shortName(String uri) {
        int index = uri.lastIndexOf('/');
        return index < 0 ? uri : uri.substring(index + 1);
    }

}
