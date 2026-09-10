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

    /**
     * 源码位置。{@code line} 从 1 开始，{@code offset} 为文档内字符偏移；
     * {@code snippet} 是元素的原文片段（空白已折叠、超长已截断），便于宿主在不读文件的情况下
     * 展示来源。
     */
    public record SourceRef(int document, int line, int offset, int length, String snippet) {
    }

    public record DocumentRef(int id, String uri, boolean workspace) {
    }

    /** 仓格里的一条条目：一行文字，外加可选的语义引用与方向。 */
    public record EntryRef(String text, String ref, String direction, Boolean inherited) {
    }

    /** 仓格：按标题分组的一组条目（title 规则见契约第 3.2 节）。 */
    public record CompartmentRef(String title, List<EntryRef> entries) {
    }

    public record ViewRef(String ref, String name, String definition, String kind, String rendering, SourceRef source) {
    }

    public record NodeRef(String id,
                          String ref,
                          String name,
                          String metaclass,
                          String graphic,
                          String origin,
                          String placement,
                          SourceRef source,
                          String parent,
                          List<String> types,
                          List<CompartmentRef> compartments) {
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
