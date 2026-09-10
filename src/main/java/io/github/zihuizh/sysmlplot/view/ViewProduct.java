package io.github.zihuizh.sysmlplot.view;

import java.util.List;

/**
 * 视图产物的数据结构。契约见 {@code docs/VIEW-PRODUCT.md}，
 * 机器可读形式见 {@code schema/view-product.schema.json}。
 *
 * <p>字段用 record 声明，序列化时字段顺序即声明顺序；可空字段在 JSON 中被省略。
 */
public final class ViewProduct {

    public static final int SCHEMA_VERSION = 0;

    /** 源码位置。{@code line} 从 1 开始，{@code offset} 为文档内字符偏移。 */
    public record SourceRef(int document, int line, int offset, int length) {
    }

    public record DocumentRef(int id, String uri, boolean workspace) {
    }

    public record ViewRef(String ref, String name, String definition, String rendering, SourceRef source) {
    }

    public record NodeRef(String id,
                          String ref,
                          String name,
                          String metaclass,
                          String graphic,
                          String origin,
                          SourceRef source,
                          String parent,
                          List<String> types) {
    }

    public record RelationshipRef(String id, String kind, String source, String target, boolean authored) {
    }

    public record Reason(String code, String detail) {
    }

    public record Completeness(boolean complete, List<Reason> reasons) {
    }

    public record Product(int schemaVersion,
                          String modelDigest,
                          ViewRef view,
                          List<DocumentRef> documents,
                          List<NodeRef> nodes,
                          List<RelationshipRef> relationships,
                          Completeness completeness) {
    }

    private ViewProduct() {
    }
}
