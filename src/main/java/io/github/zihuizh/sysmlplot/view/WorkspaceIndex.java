package io.github.zihuizh.sysmlplot.view;

import java.util.List;

/**
 * 全模型索引：跨视图的关系底座。
 *
 * <p>视图产物（{@link ViewProduct}）回答"这个视图显示什么"，索引回答"整个模型里有什么、
 * 谁和谁有关系、某个元素出现在哪些视图"。阶段 3 的四个功能——跨视图跳转、局部关系视图、
 * 影响范围分析、追溯矩阵——都建立在它之上。
 *
 * <p>契约见 {@code docs/archive/PHASE-3-PLAN.md} 第 3.1 节。与产物最大的区别：**索引里的身份是
 * `ref`（限定名），不是产物的节点编号**——编号只在单个产物内有效，跨视图追问必须靠限定名。
 */
public final class WorkspaceIndex {

    public static final int SCHEMA_VERSION = 0;

    /** 元素来源。索引跨视图，所以直接存 URI，不搞产物那套文档编号。 */
    public record ElementSource(String uri, int line, int offset, int length, String snippet) {
    }

    public record ElementEntry(String ref,
                               String name,
                               String reqId,
                               String metaclass,
                               String ownerMembership,
                               String origin,
                               ElementSource source,
                               List<String> views) {
    }

    /** 关系两端都是元素的 `ref`。 */
    public record RelationEntry(String kind, String source, String target, boolean authored) {
    }

    public record Index(int schemaVersion,
                        String modelDigest,
                        List<ElementEntry> elements,
                        List<RelationEntry> relations) {
    }

    private WorkspaceIndex() {
    }
}
