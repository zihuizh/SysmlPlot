package io.github.zihuizh.sysmlplot.render;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import io.github.zihuizh.sysmlplot.view.ViewProduct;

/**
 * 无头 SVG 渲染器：把视图产物画成一张确定性的图。
 *
 * <p>职责边界（契约第 6 节）：只做布局与样式，不推断语义。产物里没有的节点和边，
 * 这里绝不会凭空出现。
 *
 * <p>布局是"分层树"：按包含关系分层，叶子从左到右占槽位，父节点居中于子节点跨度。
 * 节点尺寸统一，保证输出可复现（同一产物 + 同一布局，输出逐字节一致）。
 */
public final class SvgRenderer {

    private static final double MARGIN = 24;
    private static final double TITLE_BLOCK = 56;
    private static final double REASON_LINE = 16;
    private static final double NODE_HEIGHT = 40;
    private static final double PORT_SIZE = 18;
    /** 边界元素标签画在方块左侧，预留的宽度（用于避免整体越界）。 */
    private static final double PORT_LABEL_ALLOWANCE = 44;
    /** 需要画标签的边类型；包含/类型/特化/子集化/重定义这类结构边不加标签（会糊成一片）。 */
    private static final Set<String> LABELLED_EDGE_KINDS = Set.of("satisfy", "verify", "allocate", "flow");
    private static final double LINE_HEIGHT = 14;
    private static final double COMPARTMENT_PAD = 6;
    private static final double H_GAP = 72;
    private static final double V_GAP = 64;
    private static final double MIN_NODE_WIDTH = 96;
    private static final double MAX_NODE_WIDTH = 360;
    private static final int CHAR_WIDTH = 7;
    private static final int TEXT_PADDING = 20;
    private static final int MAX_LABEL_CHARS = 32;

    /**
     * 渲染结果。
     *
     * @param svg        完整 SVG 文档（带 XML 声明），适合落盘
     * @param svgElement 只有 svg 元素本身，适合内联进 HTML
     * @param layout     本次实际使用的布局，可直接落盘为 layout.json
     */
    public record Result(String svg, String svgElement, Layout.LayoutFile layout) {
    }

    private SvgRenderer() {
    }

    public static Result render(ViewProduct.Product product, Layout.LayoutFile provided) {
        Layout.LayoutFile layout = layoutFor(product, provided);
        String element = toSvgElement(product, product.nodes(), layout);
        return new Result("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n" + element, element, layout);
    }

    /**
     * 决定本次使用哪份布局：外部布局必须与产物一致（`modelDigest`、`viewRef`、节点键集），
     * 否则整体丢弃重算，不做部分复用（契约 6.1）。
     */
    public static Layout.LayoutFile layoutFor(ViewProduct.Product product, Layout.LayoutFile provided) {
        Map<String, ViewProduct.NodeRef> byId = new LinkedHashMap<>();
        for (ViewProduct.NodeRef node : product.nodes()) {
            byId.put(node.id(), node);
        }
        return isUsable(provided, product, byId)
                ? provided
                : computeLayout(product, product.nodes(), byId);
    }

    private static boolean isUsable(Layout.LayoutFile provided,
                                    ViewProduct.Product product,
                                    Map<String, ViewProduct.NodeRef> byId) {
        if (provided == null || provided.nodes() == null) {
            return false;
        }
        if (!product.modelDigest().equals(provided.modelDigest())) {
            return false;
        }
        if (product.view().ref() != null && !product.view().ref().equals(provided.viewRef())) {
            return false;
        }
        Set<String> ids = new LinkedHashSet<>(provided.nodes().keySet());
        return ids.equals(byId.keySet());
    }

    private static Layout.LayoutFile computeLayout(ViewProduct.Product product,
                                                   List<ViewProduct.NodeRef> nodes,
                                                   Map<String, ViewProduct.NodeRef> byId) {
        double nodeWidth = nodeWidth(nodes);

        Map<String, List<String>> children = new LinkedHashMap<>();
        List<String> roots = new ArrayList<>();
        for (ViewProduct.NodeRef node : nodes) {
            if (isBoundary(node)) {
                continue;
            }
            String parent = node.parent();
            if (parent != null && byId.containsKey(parent)) {
                children.computeIfAbsent(parent, key -> new ArrayList<>()).add(node.id());
            } else {
                roots.add(node.id());
            }
        }

        Map<String, Double> centerX = new LinkedHashMap<>();
        Map<String, Integer> depth = new LinkedHashMap<>();
        double[] cursor = {0};
        Set<String> placing = new HashSet<>();
        for (String root : roots) {
            place(root, 0, children, centerX, depth, cursor, placing);
        }

        double top = topOffset(product);
        double rowHeight = 0;
        for (ViewProduct.NodeRef node : nodes) {
            rowHeight = Math.max(rowHeight, nodeHeight(node));
        }
        Map<String, Layout.Box> boxes = new LinkedHashMap<>();
        for (ViewProduct.NodeRef node : nodes) {
            if (isBoundary(node)) {
                continue;
            }
            double center = centerX.getOrDefault(node.id(), 0.0);
            int level = depth.getOrDefault(node.id(), 0);
            boxes.put(node.id(), new Layout.Box(
                    MARGIN + center * (nodeWidth + H_GAP),
                    top + level * (rowHeight + V_GAP),
                    nodeWidth,
                    nodeHeight(node)));
        }

        // 边界元素（端口）不参与树布局，贴在父节点的左边界上依次排开。
        Map<String, Integer> boundaryIndex = new LinkedHashMap<>();
        double orphanCursor = 0;
        // 边界元素之间也会嵌套（端口上的载荷特征挂在端口上），必须按嵌套深度从外到内摆，
        // 否则内层元素在父元素定位之前就被处理，只能退化到画布角落。
        List<ViewProduct.NodeRef> boundaryNodes = new ArrayList<>();
        for (ViewProduct.NodeRef node : nodes) {
            if (isBoundary(node)) {
                boundaryNodes.add(node);
            }
        }
        boundaryNodes.sort(Comparator.comparingInt(node -> boundaryDepth(node, byId)));
        for (ViewProduct.NodeRef node : boundaryNodes) {
            Layout.Box parentBox = boxes.get(node.parent());
            double size = PORT_SIZE;
            if (parentBox == null) {
                boxes.put(node.id(), new Layout.Box(
                        MARGIN + orphanCursor * (nodeWidth + H_GAP), top, nodeWidth, NODE_HEIGHT));
                orphanCursor += 1;
                continue;
            }
            int index = boundaryIndex.merge(node.parent(), 1, Integer::sum) - 1;
            boxes.put(node.id(), new Layout.Box(
                    parentBox.x() - size / 2,
                    parentBox.y() + 12 + index * (size + 12),
                    size,
                    size));
        }

        // 边界元素的标签画在方块左侧，可能落到画布外；整体右移到最左侧不越界为止。
        double minLeft = Double.MAX_VALUE;
        for (ViewProduct.NodeRef node : nodes) {
            Layout.Box box = boxes.get(node.id());
            if (box == null) {
                continue;
            }
            minLeft = Math.min(minLeft, isBoundary(node) ? box.x() - PORT_LABEL_ALLOWANCE : box.x());
        }
        if (minLeft < MARGIN && minLeft < Double.MAX_VALUE) {
            double shift = MARGIN - minLeft;
            Map<String, Layout.Box> shifted = new LinkedHashMap<>();
            for (Map.Entry<String, Layout.Box> entry : boxes.entrySet()) {
                Layout.Box box = entry.getValue();
                shifted.put(entry.getKey(), new Layout.Box(box.x() + shift, box.y(), box.width(), box.height()));
            }
            boxes = shifted;
        }
        return new Layout.LayoutFile(Layout.SCHEMA_VERSION, product.modelDigest(),
                product.view().ref(), boxes);
    }

    /** 边界元素的嵌套深度（父链长度），用于保证外层先定位。 */
    private static int boundaryDepth(ViewProduct.NodeRef node, Map<String, ViewProduct.NodeRef> byId) {
        int depth = 0;
        String parent = node.parent();
        while (parent != null && byId.containsKey(parent) && depth < 16) {
            depth++;
            parent = byId.get(parent).parent();
        }
        return depth;
    }

    private static boolean isBoundary(ViewProduct.NodeRef node) {
        return "boundary".equals(node.placement());
    }

    /** 这条边是否只是"边界元素附着到所属节点"——是的话不画线，附着由位置表达。 */
    private static boolean isAttachment(ViewProduct.Product product, ViewProduct.RelationshipRef relationship) {
        if (!"containment".equals(relationship.kind())) {
            return false;
        }
        for (ViewProduct.NodeRef node : product.nodes()) {
            if (node.id().equals(relationship.target()) && isBoundary(node)) {
                return relationship.source().equals(node.parent());
            }
        }
        return false;
    }

    private static void place(String id,
                              int level,
                              Map<String, List<String>> children,
                              Map<String, Double> centerX,
                              Map<String, Integer> depth,
                              double[] cursor,
                              Set<String> placing) {
        if (!placing.add(id)) {
            return;
        }
        depth.put(id, level);
        List<String> kids = children.getOrDefault(id, List.of());
        if (kids.isEmpty()) {
            centerX.put(id, cursor[0]);
            cursor[0] += 1;
        } else {
            for (String kid : kids) {
                place(kid, level + 1, children, centerX, depth, cursor, placing);
            }
            Double first = centerX.get(kids.get(0));
            Double last = centerX.get(kids.get(kids.size() - 1));
            centerX.put(id, first != null && last != null ? (first + last) / 2.0 : cursor[0]++);
        }
    }

    private static double nodeWidth(List<ViewProduct.NodeRef> nodes) {
        int longest = 0;
        for (ViewProduct.NodeRef node : nodes) {
            longest = Math.max(longest, labelOf(node).length());
            for (ViewProduct.CompartmentRef compartment : node.compartments()) {
                longest = Math.max(longest, compartment.title().length() + 2);
                for (ViewProduct.EntryRef entry : compartment.entries()) {
                    int extra = entry.direction() == null ? 0 : entry.direction().length() + 1;
                    longest = Math.max(longest, entry.text().length() + extra + 1);
                }
            }
        }
        double estimated = TEXT_PADDING * 2 + longest * (double) CHAR_WIDTH;
        return Math.max(MIN_NODE_WIDTH, Math.min(MAX_NODE_WIDTH, estimated));
    }

    /** 节点高度随仓格行数变化；边界元素（端口）固定为小方块。 */
    private static double nodeHeight(ViewProduct.NodeRef node) {
        if (isBoundary(node)) {
            return PORT_SIZE;
        }
        int lines = 0;
        for (ViewProduct.CompartmentRef compartment : node.compartments()) {
            lines += 1 + compartment.entries().size();
        }
        return lines == 0 ? NODE_HEIGHT : NODE_HEIGHT + lines * LINE_HEIGHT + COMPARTMENT_PAD;
    }

    private static double topOffset(ViewProduct.Product product) {
        int reasons = product.completeness().reasons().size();
        return MARGIN + TITLE_BLOCK + reasons * REASON_LINE;
    }

    private static String toSvgElement(ViewProduct.Product product,
                                       List<ViewProduct.NodeRef> nodes,
                                       Layout.LayoutFile layout) {
        double width = 0;
        double height = 0;
        for (Layout.Box box : layout.nodes().values()) {
            width = Math.max(width, box.x() + box.width());
            height = Math.max(height, box.y() + box.height());
        }
        width += MARGIN;
        height += MARGIN;

        StringBuilder svg = new StringBuilder();
        svg.append(String.format(Locale.ROOT,
                "<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"%.0f\" height=\"%.0f\" viewBox=\"0 0 %.0f %.0f\">\n",
                width, height, width, height));
        svg.append(style());

        svg.append(String.format(Locale.ROOT,
                "  <text class=\"title\" x=\"%.0f\" y=\"%.0f\">%s</text>\n",
                MARGIN, MARGIN + 14, escape(product.view().ref())));
        double reasonY = MARGIN + 34;
        for (ViewProduct.Reason reason : product.completeness().reasons()) {
            svg.append(String.format(Locale.ROOT,
                    "  <text class=\"reason\" x=\"%.0f\" y=\"%.0f\">%s: %s</text>\n",
                    MARGIN, reasonY, escape(reason.code()), escape(reason.detail())));
            reasonY += REASON_LINE;
        }

        svg.append("  <g id=\"viewport\">\n");
        svg.append("    <g class=\"edges\">\n");
        for (ViewProduct.RelationshipRef relationship : product.relationships()) {
            // 端口/参数与其所属节点之间的包含关系不画连线：附着由位置表达，画线会从节点本体穿过。
            if (isAttachment(product, relationship)) {
                continue;
            }
            Layout.Box source = layout.nodes().get(relationship.source());
            Layout.Box target = layout.nodes().get(relationship.target());
            if (source == null || target == null) {
                continue;
            }
            double x1 = source.x() + source.width() / 2;
            double y1 = source.y() + source.height();
            double x2 = target.x() + target.width() / 2;
            double y2 = target.y();
            double midY = (y1 + y2) / 2;
            svg.append(String.format(Locale.ROOT,
                    "      <path class=\"edge %s\" data-id=\"%s\" data-source=\"%s\" data-target=\"%s\" d=\"M %.1f %.1f L %.1f %.1f L %.1f %.1f L %.1f %.1f\"/>\n",
                    escape(relationship.kind()), escape(relationship.id()),
                    escape(relationship.source()), escape(relationship.target()),
                    x1, y1, x1, midY, x2, midY, x2, y2));
            if (LABELLED_EDGE_KINDS.contains(relationship.kind())) {
                svg.append(String.format(Locale.ROOT,
                        "      <text class=\"edge-label\" x=\"%.1f\" y=\"%.1f\">%s</text>\n",
                        (x1 + x2) / 2, midY - 4.0, escape("\u00ab" + relationship.kind() + "\u00bb")));
            }
        }
        svg.append("    </g>\n");

        svg.append("    <g class=\"nodes\">\n");
        for (ViewProduct.NodeRef node : nodes) {
            Layout.Box box = layout.nodes().get(node.id());
            if (box == null) {
                continue;
            }
            String label = labelOf(node);
            if (label.length() > MAX_LABEL_CHARS) {
                label = label.substring(0, MAX_LABEL_CHARS - 1) + "\u2026";
            }
            boolean boundary = isBoundary(node);
            svg.append(String.format(Locale.ROOT,
                    "      <g class=\"node %s origin-%s%s\" data-id=\"%s\" data-ref=\"%s\" data-origin=\"%s\" transform=\"translate(%.1f %.1f)\">\n",
                    escape(node.graphic()), escape(node.origin()),
                    boundary ? " placement-boundary" : "",
                    escape(node.id()), escape(node.ref()), escape(node.origin()),
                    box.x(), box.y()));
            svg.append(String.format(Locale.ROOT,
                    "        <rect class=\"box\" width=\"%.1f\" height=\"%.1f\" rx=\"4\"/>\n",
                    box.width(), box.height()));
            if (boundary) {
                svg.append(String.format(Locale.ROOT,
                        "        <text class=\"port-name\" x=\"%.1f\" y=\"%.1f\">%s</text>\n",
                        -6.0, box.height() / 2 + 3.0, escape(label)));
            } else {
                svg.append(String.format(Locale.ROOT,
                        "        <text class=\"name\" x=\"%.1f\" y=\"%.1f\">%s</text>\n",
                        box.width() / 2, 17.0, escape(label)));
                svg.append(String.format(Locale.ROOT,
                        "        <text class=\"meta\" x=\"%.1f\" y=\"%.1f\">%s</text>\n",
                        box.width() / 2, 32.0, escape(metaOf(node))));
                appendCompartments(svg, node, box);
            }
            svg.append("      </g>\n");
        }
        svg.append("    </g>\n");
        svg.append("  </g>\n");
        svg.append("</svg>\n");
        return svg.toString();
    }

    private static String labelOf(ViewProduct.NodeRef node) {
        if (node.name() != null) {
            return node.name();
        }
        if (node.ref() != null) {
            return node.ref();
        }
        return "(" + node.metaclass() + ")";
    }

    /** 画仓格：每个仓格一条分隔线 + 标题 + 若干条目。 */
    private static void appendCompartments(StringBuilder svg, ViewProduct.NodeRef node, Layout.Box box) {
        double cursor = NODE_HEIGHT;
        for (ViewProduct.CompartmentRef compartment : node.compartments()) {
            svg.append(String.format(Locale.ROOT,
                    "        <line class=\"compartment-separator\" x1=\"0\" y1=\"%.1f\" x2=\"%.1f\" y2=\"%.1f\"/>\n",
                    cursor, box.width(), cursor));
            cursor += LINE_HEIGHT;
            svg.append(String.format(Locale.ROOT,
                    "        <text class=\"compartment-title\" x=\"%.1f\" y=\"%.1f\">%s</text>\n",
                    8.0, cursor - 4.0, escape(compartment.title())));
            for (ViewProduct.EntryRef entry : compartment.entries()) {
                cursor += LINE_HEIGHT;
                String text = entry.direction() == null ? entry.text() : entry.direction() + " " + entry.text();
                svg.append(String.format(Locale.ROOT,
                        "        <text class=\"compartment-entry\" x=\"%.1f\" y=\"%.1f\">%s</text>\n",
                        14.0, cursor - 4.0, escape(text)));
            }
        }
    }

    private static String metaOf(ViewProduct.NodeRef node) {
        return "workspace".equals(node.origin())
                ? node.graphic()
                : node.graphic() + " \u00b7 " + node.origin();
    }

    private static String style() {
        return """
                  <style>
                    .title { font-family: sans-serif; font-size: 14px; font-weight: bold; fill: #111; }
                    .reason { font-family: sans-serif; font-size: 11px; fill: #b3261e; }
                    .edge { fill: none; stroke: #666; stroke-width: 1.2; }
                    .edge.typing { stroke-dasharray: 5 3; }
                    .edge.specialization { stroke-dasharray: 9 3 2 3; }
                    .edge.subsetting { stroke-dasharray: 2 3; }
                    .edge.redefinition { stroke-dasharray: 9 2 2 2 2 2; }
                    .edge.satisfy { stroke-dasharray: 6 4; }
                    .node .box { fill: #fff; stroke: #444; stroke-width: 1.2; }
                    .node .name { font-family: sans-serif; font-size: 13px; fill: #111; text-anchor: middle; }
                    .node .meta { font-family: sans-serif; font-size: 10px; fill: #777; text-anchor: middle; }
                    .node.origin-library .box { stroke-dasharray: 4 3; }
                    .node.origin-implicit .box { stroke: #aaa; stroke-dasharray: 1 3; }
                    .node.placement-boundary .box { fill: #eef2f8; stroke: #567; }
                    .node .port-name { font-family: sans-serif; font-size: 9px; fill: #456; text-anchor: end; }
                    .compartment-separator { stroke: #e0e0e0; stroke-width: 1; }
                    .compartment-title { font-family: sans-serif; font-size: 9px; fill: #999; }
                    .compartment-entry { font-family: sans-serif; font-size: 11px; fill: #333; }
                    .edge-label { font-family: sans-serif; font-size: 10px; fill: #666; text-anchor: middle; }
                  </style>
                """;
    }

    private static String escape(String value) {
        if (value == null) {
            return "";
        }
        return value
                .replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;");
    }
}
