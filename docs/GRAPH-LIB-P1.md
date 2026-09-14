# 图库 P1：原生布局、交互与动画的实测

状态：已完成。**结论：主推 G6 5.1.1，Cytoscape 3.34.3 保留为体积敏感的备选。**

日期：2026-09-14。机器与依赖同 `docs/ENVIRONMENT.md`：Windows、Node 24.19、Chrome 无头、
`build/vendor/node_modules` 下的 `@antv/g6@5.1.1` 与 `cytoscape@3.34.3`（不进仓库）。

## 1. 判据

这一轮只看两件事：

1. **排布易读**——同一份数据，能不能一眼看出结构，而不是一坨线。
2. **语义表达正确**——端口、嵌套、仓格、关系边这四类必须画对。

**不要求与现有 Java SVG 渲染器的布局一致。** 那是无头导出的回归基线，不是展示层的目标。
P0 页面里那次 `preset`（读 Java 坐标）在这一轮保留为可选对照项，但不作验收依据。

## 2. 这一轮做了什么

与 P0 的根本区别：**不再吃 Java 的 `layout.json` 坐标**，布局、交互、动画全部交给图库，
视图产物只当语义输入。

```
view-product.json
      │  适配层（引擎无关，见 scripts/make-proto-page-p1.py 的 adapt()）
      ▼
展示模型 { nodes, ports, edges, nested, matched, stats }
      ├── G6 适配：combo / 原生 port / 内置 html 节点（仓格）
      └── Cytoscape 适配：compound / 子节点模拟端口 / 多行标签
```

页面能力（两个引擎同壳）：切视图、切布局、只看连接类边、隐藏隐式元素、隐藏库元素、
搜索高亮、选中与检查器、点模型树聚焦、折叠容器、拖拽/缩放/平移、适配视口开关、动画开关。

页面同时是**测量台**：暴露

```js
window.__PROTO = { ready, report(), run(name, arg) }
```

## 3. 怎么跑

```powershell
# 生成（单文件 HTML，库与数据全内联，双击即开）
python scripts\make-proto-page-p1.py --engine g6 `
    --workspace samples/interconnection `
    --views "PowerViews::'power interconnection'" "PowerViews::'power structure'" `
    --lib build/vendor/node_modules/@antv/g6/dist/g6.min.js `
    --out build/proto/g6-p1-small.html

# 测量：CDP 驱动真实时钟的无头 Chrome，跑脚本化交互并截图
python scripts\measure-proto.py --html build/proto/g6-p1-small.html `
    --metrics build/proto/g6-p1-small.metrics.json `
    --shot build/proto/g6-p1-small.png `
    --call "window.__PROTO.run('layout','force')" `
    --call "window.__PROTO.run('filter',{hideImplicit:true})"
```

**为什么不用 P0 的 `--dump-dom` + `--virtual-time-budget`**（实测两条，都会让结果失真）：

1. 虚拟时间下 `performance.now()` 在同步任务里不推进——拿它做忙碌等待会**死循环**，CPU 密集段
   的耗时也量不出来；
2. 无头一轮只给 1–3 帧，页面里的多步测量跑不完。

`scripts/measure-proto.py` 因此自己实现了最小 CDP 客户端（socket + 手写 WebSocket 帧，
不引入 pip 依赖）：导航、等页面自报就绪、逐步执行交互、取指标、截图，全部走真实时钟
（实测 rAF 稳定 60 帧/秒，`performance.memory` 可用）。

> 本机沙箱内无头 Chrome 起不来（`mojo platform_channel: 拒绝访问`），测量需要沙箱外执行一次。

## 4. 实测数字

同一台机器、同一份产物、动画关闭、`-` 表示该次未测。

| 页面 | 引擎 | 单文件 | gzip | 节点/边 | 建图 | 重排 | 筛选重建 | 搜索 | 堆 |
|---|---|---:|---:|---|---:|---:|---:|---:|---:|
| 小图 `samples/interconnection` | G6 5.1.1 | 1398 KB | 394 KB | 3 / 1 | 10 ms | 9 ms | 12 ms | 2 ms | 9.5 MB |
| 小图 `samples/interconnection` | Cytoscape 3.34.3 | 469 KB | 145 KB | 3 / 1 | 20 ms | 5 ms | 30 ms | 0.3 ms | 9.5 MB |
| 大图 `2a-Parts Interconnection` | G6 5.1.1 | 1505 KB | 404 KB | 104 / 56 | 89 ms | 57–60 ms | 98 ms | 35 ms | 20 MB |
| 大图 `2a-Parts Interconnection` | Cytoscape 3.34.3 | 576 KB | 156 KB | 104 / 56 | 61 ms | 20 ms | 146 ms | 3 ms | 20 MB |

口径：

- **建图**＝数据就绪 → 布局完成并绘制首帧（含引擎初始化与 DOM 重建）。
- **重排**＝同一份数据换布局并重新适配视口（不重建引擎）。
- **筛选重建**＝改筛选条件后重新适配 + 重建 + 布局。
- 大图那一行是**隐藏隐式元素之后**的规模（221 → 104 节点）。隐藏前：G6 建图 280 ms、
  Cytoscape 357 ms（168 节点 / 80 边）。大图上两个引擎都默认关动画——221 节点开动画时
  `render()` 会一直不结算（实测），这也是页面加"大图默认关动画"的原因。
- 体积：G6 的单文件是 Cytoscape 的 3.2 倍（原始）/ 2.6 倍（gzip）。G6 官方 dist 的
  `limit-size` 配置给的是 gzip 上限 400 KB，与实测 381 KB 相符；真要压还可以做 tree-shaking。

## 5. 能力对照

| 能力 | G6 5.1.1 | Cytoscape 3.34.3 |
|---|---|---|
| **端口** | ✅ 原生 `style.ports`，`placement` 直接给 `left`/`right`/`top`/`bottom`，边用 `sourcePort`/`targetPort` | ⚠️ 无端口概念，只能用子节点模拟；大图上这些圆点会抢戏，且被布局当成普通节点摆放 |
| **嵌套容器** | ✅ combo 吃原生布局（`antv-dagre` + `sortByCombo`、`combo-combined`）；**但不能作为边的端点**（见第 6 节） | ⚠️ compound 父框位置由子节点算出（原生布局下可用）；必须父先入，否则自动补空壳 |
| **层级（树）** | ✅ `compact-box` / `indented` 直接吃 `children`，绕开 combo 限制 | ⚠️ `breadthfirst` 只有自上而下且铺得很宽；要 dagre 得另装扩展 |
| **仓格** | ✅ 内置 `html` 节点（`innerHTML`），**不需要装扩展包** | ⚠️ 多行标签可用；要真正的 HTML 需 `node-html-label` 扩展 |
| **布局** | 19 种内置（dagre / combo-combined / d3-force / concentric / radial / grid / 树布局…） | 6 种核心（breadthfirst / cose / concentric / grid / circle / preset） |
| **交互** | ✅ zoom / drag-canvas / drag-element / click-select / hover-activate / focus-element / collapse-expand | ✅ 内置平移、缩放、框选、拖拽 |
| **动画** | ✅ 全局 `animation` + 布局过渡 + 展开收起动画 | ✅ `cy.animate`（元素平移/缩放） |
| **导出** | `toDataURL` | `png()` / `svg()`（后者需扩展） |

页面上的默认值按易读性定：大图默认 100% 缩放（不把整图塞进一屏）、默认只看连接类边
（藏 `typing`/`subsetting`/`redefinition` 这类边）、大图默认关动画。这些是展示层默认值，
不影响产物。

## 6. 硬约束与踩到的坑

**一、G6 的 combo 不能当边的端点。** 这不是 P0 的误记——小样例上不复现，但**真实语料上必现**：
`Uncaught Error: Node not found for id: n211`（最初一次，`n97`；换一份映射就换一个 id）。
适配层因此定死三条规则（写进 `adapt()`）：

1. 带端口的容器**留节点形态**——端口要挂在节点上；
2. 参与关系的容器**留节点形态**——否则它会成为 combo，边无处可落；
3. 父容器不作嵌套时，子容器也不能嵌套——否则那条包含边会指向 combo，同样画不出来。

结果是：**嵌套是一棵从顶层连续往下的树**；落不进嵌套的容器，用包含边表达归属。
这也是 G6 侧必须保留"树布局"这条路线的原因：`compact-box`/`indented` 用 `children`
表达层级，完全不碰 combo，就没有上面这些限制。

**二、端口方向在语义层还没有。** `docs/BACKLOG.md` 已记（要从端口特征读 `in`/`out`/`inout`）。
P1 的端口贴边用**连接器端序**决定左右侧（出边贴右、入边贴左、都没连则贴下），
这是展示层的确定性规则，不是语义推断；语义层补上方向后应当改为读 `direction`。

**三、Cytoscape 构造时就给 `layout` 会错过 `layoutstop`。** 监听器挂上之前布局已经跑完，
只能靠超时兜底，实测把 3 个节点的建图时间虚报成 410 ms。改成构造用 `preset`、之后显式
`cy.layout(opts).run()` 才拿到真实数字。

**四、逐节点 `setElementState` 会每节点触发一次重绘。** 104 个节点上搜索高亮要 **1712 ms**；
改用一次传 `id → states` 的批量形式后是 **35 ms**（43 倍）。这是展示层必须注意的写法。

**五、布局/动画的 promise 可能不结算。** 大图开动画时 `render()` 一直悬着，
`window.__PROTO.ready` 也就永远不完成，测量台直接卡死。页面里加了兜底超时，
把"超时"当作一条结论上报，而不是让工具悬着。

**六、标签兜底不能用产物内部的 `id`。** 匿名元素没有 `name`/`ref`，P0 页面显示成 `n97`——
那是在人眼前暴露只在一个产物内有效的内部编号。P1 改成用元类兜底（`(PartUsage)` 这种）。

**七、`@antv/g6/dist/g6.min.js` 里 `html` 节点是可用的**（P0 文档说"核心没有 HTML 节点、
要扩展包"，那条不准确）：图级或数据级 `type: 'html'` 都能渲染出真实 DOM 节点。
第一次探针失败是因为我在图级 `node.type` 里写了 `rect`，把数据级的 `html` 覆盖掉了。

## 7. 选型建议

**主推 G6 5.1.1。** 理由是四条硬需求里它占了三条的原生实现：端口（原生 port，别的引擎要自己造）、
嵌套（combo 能吃原生布局）、仓格（内置 html 节点，零扩展）。代价是单文件体积大——gzip 后
404 KB，可以靠 tree-shaking 或 gzip 内联继续压，属于工程量问题而不是能力缺口。

**Cytoscape 3.34.3 保留为备选。** 它的价值在体积（gzip 156 KB，只有 G6 的 38%）和
力导向布局在互联类视图上的观感；但端口要靠子节点模拟、层级只有自上而下的 breadthfirst，
这两样恰好是我们最核心的表达，长期要自己维护一套渲染补丁。

**还需要人工确认的**（数字答不了）：真实浏览器里拖拽/缩放/动画的手感；含仓格的真实视图
（`samples/structure`、`samples/parameters`）打开后的观感。

## 8. 下一步（P2 候选）

| 项 | 说明 |
|---|---|
| 适配层定契约 | 把 `adapt()` 从原型脚本提成正式模块，两个引擎共用；Java 侧不再新增展示算法 |
| 扩面到含仓格的视图 | `samples/structure`、`samples/parameters`、`samples/actions` 各出一张页，验证仓格与动作流 |
| 体积 | tree-shaking 构建，或 gzip + `DecompressionStream` 内联 |
| 展示层回归 | 把 `scripts/measure-proto.py` 的步骤断言化（阈值写进脚本），纳入提交前检查 |
| 大图策略 | 定性结论：默认不整图适配、优先筛选与折叠；后续再做小地图/分层下钻 |

## 9. 与 P0 的关系

P0（`docs/GRAPH-LIB-P0.md`）用 Java 坐标对比两个引擎"能不能表达端口与嵌套"，结论是
G6 领先、体积落后。P1 把坐标拿掉、换成框架原生布局与真实交互后，结论没变，但**依据换了**：
不再是"combo 能吃我们的坐标"，而是"combo 能吃原生布局、端口原生、仓格零扩展"，
并且把 P0 那条"combo 不能作边端点"的约束在真实语料上复现、量化成了适配层的三条规则。
