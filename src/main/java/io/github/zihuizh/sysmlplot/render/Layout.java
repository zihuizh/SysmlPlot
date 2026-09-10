package io.github.zihuizh.sysmlplot.render;

import java.util.Map;

/**
 * 布局文件（layout.json v0）：产物之外的可丢弃层。
 *
 * <p>它只回答"每个节点画在哪里"，不携带任何语义。视图产物变了（`modelDigest` 或
 * `viewRef` 不匹配）就应该整体丢弃重算。
 */
public final class Layout {

    public static final int SCHEMA_VERSION = 0;

    /** 节点框：左上角坐标与尺寸，单位是 SVG 用户单位（像素）。 */
    public record Box(double x, double y, double width, double height) {
    }

    public record LayoutFile(int schemaVersion,
                             String modelDigest,
                             String viewRef,
                             Map<String, Box> nodes) {
    }

    private Layout() {
    }
}

