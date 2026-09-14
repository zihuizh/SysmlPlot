# 图库 P0：G6 与 Cytoscape 单文件原型对比

目的：在投 S2（单文件模型页）之前，用**同一份产物 + 同一份 Java 布局**把两个候选图库都跑起来，
用数字和截图决定用哪个。**这是 spike，不是产品代码**；原型页放在 `build/proto/`（不进仓库）。

## 怎么跑

```powershell
# 依赖先落到 build/vendor（不进仓库）
npm install @antv/g6@5 cytoscape@3 --prefix build/vendor

python scripts/make-proto-page.py --engine g6 `
    --workspace samples/interconnection `
    --views "PowerViews::'power interconnection'" "PowerViews::'power structure'" `
    --lib build/vendor/node_modules/@antv/g6/dist/g6.min.js `
    --out build/proto/g6-small.html

python scripts/make-proto-page.py --engine cytoscape ... --out build/proto/cyto-small.html
# 大图：--workspace build/corpus-views/2a-Parts-Interconnection，221 节点
```

生成物是**单文件 HTML**（库与数据都内联），双击即可打开、不联网、不起服务。

## 实测数字（同一台机器、同一份数据、无头 Chrome）

四级数据：小模型 `samples/interconnection`（7 节点）、语料大图 `2a-Parts-Interconnection`（221 节点 / 268 边）。

| 页面 | 引擎 | 单文件体积 | 库本体 | 节点/边 | 建图（含首帧） | 重建 | 200 次缩放 |
|---|---|---:|---:|---|---:|---:|---:|
| `g6-small.html` | G6 5.1.1 | **1375 KB** | 1351 KB | 7 / 3 | 84 ms | 2 ms | 1 ms |
| `cyto-small.html` | Cytoscape 3.34.3 | **446 KB** | 425 KB | 7 / 3 | 74 ms | 2 ms | 1 ms |
| `g6-large.html` | G6 5.1.1 | **1483 KB** | 1351 KB | 221 / 268 | 266 ms | 12 ms | 1 ms |
| `cyto-large.html` | Cytoscape 3.34.3 | **554 KB** | 425 KB | 221 / 268 | 171 ms | 22 ms | 1 ms |

口径说明（避免误读）：

- **建图**＝脚本开始 → 两帧后的首帧时间（等两帧是因为 G6 的 canvas 异步绘制，只测 JS 调用会得到"2ms"这种假数字）。
- **重建**＝同一份数据再建一次图（切视图/重排的近似成本）。
- **200 次缩放**是**JS 调用成本，不是绘制成本**——两个引擎的缩放都可能被合并/异步，**不能当帧率看**；
  真实帧率请在浏览器里人工感受（这也是 P0 只把它当参考的原因）。
- 体积 = 内联库 + 数据 + 我们那点 UI 代码；**G6 的单文件比 Cytoscape 贵 3.2 倍**，这是最硬的一条差距。

## 五个能力的实测结论

| 能力 | G6 5.1.1 | Cytoscape 3.34.3 |
|---|---|---|
| **端口 + anchor** | ✅ 原生：节点 `style.ports=[{key,placement:[x,y]}]`，边用 `sourcePort`/`targetPort` 连端口 | ⚠️ 没有端口概念：要么用子节点模拟，要么自定义渲染 |
| **嵌套容器** | ✅ combo 可以**显式给 x/y/width/height**，直接吃我们 `layout.json` 的坐标 | ⚠️ compound 父节点的位置/尺寸**由子节点算出来**，喂进去的坐标会被忽略（实测父框横跨整张图） |
| **仓格表格（HTML 节点）** | ⚠️ 核心没有 HTML 节点，要 `@antv/g6-extension-react` 或 DOM 渲染器 | ⚠️ 要 `node-html-label` 扩展 |
| **模型结构树** | ❌ 不提供面板（本次原型是我们自己写的 `<ul>`，与图库无关） | ❌ 同上 |
| **布局吃我们的坐标** | ✅ `layout: {type:'preset'}` + 显式坐标 | ✅ `layout: {name:'preset'}` + 显式坐标 |

### 踩到的两条硬约束（值得写进选型依据）

1. **G6 的 combo 不能作为边的端点**。第一版把"容器"直接映射成 combo，语料大图立刻报
   `Node not found for id: n211`。现在的规则是：**只有"含非边界子节点、且不参与任何非包含关系"的节点才当
   combo**；含关系的一端只能留在节点形态。只含端口/载荷特征的节点也必须留在节点形态，否则它的端口无处可挂、
   以端口为端点的边会整条丢掉（实测丢过 1 条）。
2. **Cytoscape 的 compound 吃不进外部坐标**（父框位置由子节点决定）。要同时满足"嵌套可见"和"坐标由 Java 定"，
   就只能放弃 compound、改用背景矩形自己画。

## 结论与建议

- **体积**：Cytoscape 明显占优（单文件 1/3）。
- **端口与嵌套**：G6 明显占优（原生端口、combo 可吃坐标）——恰好是我们这套图最核心的两种表达。
- **HTML 仓格**：两家都要额外扩展，打平；本次 P0 用"多行文本"做等价展示。
- **树面板**：与图库无关，自己写。

建议：**主推 G6**（张子辉点名要看的就是它的端口/嵌套能力，而这两点正是我们的痛点），
Cytoscape 作为"体积敏感场景"的备选（例如要嵌入文档/邮件、或要做 tree-shaking 后仍嫌大时）。
体积这一条可以靠后续优化补：tree-shaking 构建（估计能砍掉一半以上）或 gzip + `DecompressionStream` 内联。

## 待人工确认

1. 双击打开 `build/proto/g6-small.html` / `g6-large.html` / `cyto-*.html`，看交互手感（缩放、点选、悬停）。
2. 端口与嵌套的观感是否符合预期（G6 用小圆点表示端口，与官方 PUML 的 port 行不同）。
3. 单文件体积上限：G6 的 1.35 MB 是否可接受（不可接受就走 tree-shaking / gzip 优化）。
