package io.github.zihuizh.sysmlplot.render;

import java.util.ArrayList;
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
    private static final double H_GAP = 36;
    private static final double V_GAP = 64;
    private static final double MIN_NODE_WIDTH = 96;
    private static final double MAX_NODE_WIDTH = 300;
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
        Map<String, Layout.Box> boxes = new LinkedHashMap<>();
        for (ViewProduct.NodeRef node : nodes) {
            double center = centerX.getOrDefault(node.id(), 0.0);
            int level = depth.getOrDefault(node.id(), 0);
            boxes.put(node.id(), new Layout.Box(
                    MARGIN + center * (nodeWidth + H_GAP),
                    top + level * (NODE_HEIGHT + V_GAP),
                    nodeWidth,
                    NODE_HEIGHT));
        }
        return new Layout.LayoutFile(Layout.SCHEMA_VERSION, product.modelDigest(),
                product.view().ref(), boxes);
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
        }
        double estimated = TEXT_PADDING * 2 + longest * (double) CHAR_WIDTH;
        return Math.max(MIN_NODE_WIDTH, Math.min(MAX_NODE_WIDTH, estimated));
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
            svg.append(String.format(Locale.ROOT,
                    "      <g class=\"node %s origin-%s\" data-id=\"%s\" data-ref=\"%s\" data-origin=\"%s\" transform=\"translate(%.1f %.1f)\">\n",
                    escape(node.graphic()), escape(node.origin()),
                    escape(node.id()), escape(node.ref()), escape(node.origin()),
                    box.x(), box.y()));
            svg.append(String.format(Locale.ROOT,
                    "        <rect class=\"box\" width=\"%.1f\" height=\"%.1f\" rx=\"4\"/>\n",
                    box.width(), box.height()));
            svg.append(String.format(Locale.ROOT,
                    "        <text class=\"name\" x=\"%.1f\" y=\"%.1f\">%s</text>\n",
                    box.width() / 2, 17.0, escape(label)));
            svg.append(String.format(Locale.ROOT,
                    "        <text class=\"meta\" x=\"%.1f\" y=\"%.1f\">%s</text>\n",
                    box.width() / 2, 32.0, escape(metaOf(node))));
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
                    .node .box { fill: #fff; stroke: #444; stroke-width: 1.2; }
                    .node .name { font-family: sans-serif; font-size: 13px; fill: #111; text-anchor: middle; }
                    .node .meta { font-family: sans-serif; font-size: 10px; fill: #777; text-anchor: middle; }
                    .node.origin-library .box { stroke-dasharray: 4 3; }
                    .node.origin-implicit .box { stroke: #aaa; stroke-dasharray: 1 3; }
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
