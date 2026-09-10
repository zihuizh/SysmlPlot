# 视图产物契约（view-product v0）

适用范围：本文件定义工具产出的**视图产物**——阶段 1 的核心交付物。
机器可读形式见 `schema/view-product.schema.json`。

## 1. 这份产物是什么

一个 view 一次生成一个产物，内容是**语义投影**：这个视图里有哪些元素、它们之间是什么关系、
各自来自哪里。它**不是一张画好的图**。

投影的范围是**暴露范围**，不只是 `expose` 直接列出的元素：官方渲染会往已暴露元素的内部走，
把其中的结构特征（部件、端口、有向特征）也画成框。我们对齐该行为——见 3.2 节。

设计原则：

1. **语义与布局分离**：产物不带坐标、不带尺寸、不带颜色。布局是后续独立阶段，存在单独的
   `layout.json` 里（可丢弃、可重算、手工调整的结果也放那里）。
2. **确定性**：同一输入必须产出逐字节相同的产物。排序与编号规则见第 4 节。
3. **不静默丢弃**：算不出来的事实进 `completeness.reasons`，带类型，不假装没有。
4. **不烤样式**：产物只带"这是什么"（`metaclass` / `graphic`），不带"画成什么样"。
   具体形状、颜色、主题由渲染器决定。

因为产物是语义层，同一个产物可以喂给任何渲染器：自己会布局的交互式库（React Flow、
Cytoscape.js、yFiles）、需要坐标的确定性输出（Graphviz、ELK、PlantUML），都能吃。

## 2. 顶层结构

```json
{
  "schemaVersion": 0,
  "modelDigest": "sha256:…",
  "view": { … },
  "documents": [ … ],
  "nodes": [ … ],
  "relationships": [ … ],
  "completeness": { "complete": true, "reasons": [] }
}
```

| 字段 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `schemaVersion` | int | 是 | 本契约版本，当前 `0` |
| `modelDigest` | string | 是 | 输入内容的摘要，`sha256:<hex>`，算法见第 5 节 |
| `view` | object | 是 | 被渲染的视图 |
| `documents` | array | 是 | 产物引用到的文档（去重后编号） |
| `nodes` | array | 是 | 投影出的元素 |
| `relationships` | array | 是 | 投影出的关系 |
| `completeness` | object | 是 | 完整性 |

### 2.1 `view`

| 字段 | 类型 | 说明 |
|---|---|---|
| `ref` | string | 视图的限定名，例如 `VehicleViews::'vehicle structure'` |
| `name` | string? | 视图名 |
| `definition` | string? | 视图定义名（`view def` 的名字） |
| `kind` | string? | 视图类型，见下；`unclassified` 表示视图定义没有特化任何标准视图定义 |
| `rendering` | string? | 模型里写的 rendering，例如 `asTreeDiagram` |
| `source` | object? | 视图声明的位置，见 `source` 结构 |

`kind` 取值：`general`、`interconnection`、`actionFlow`、`stateTransition`、`sequence`、
`grid`、`browser`、`geometry`、`unclassified`。判定方式：沿视图定义的泛化闭包找
`StandardViewDefinitions` 里的标准视图定义，从最具体的一个开始匹配（`ActionFlowView`
特化 `InterconnectionView`，因此会判成 `actionFlow` 而不是 `interconnection`）。

### 2.2 `documents[]`

| 字段 | 类型 | 说明 |
|---|---|---|
| `id` | int | 产物内编号，从 0 开始 |
| `uri` | string | 文件 URI |
| `workspace` | bool | `true` = 工作区文件；`false` = 标准库等外部文件 |

### 2.3 `nodes[]`

| 字段 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `id` | string | 是 | 产物内编号，形如 `n1`；**不是语义身份**，不可持久化 |
| `ref` | string? | | 限定名；匿名元素没有 |
| `name` | string? | | 元素名 |
| `metaclass` | string | 是 | 元类名，如 `PartUsage` |
| `graphic` | string | 是 | 建议的图形类别，见第 3 节；只是提示，渲染器可覆盖 |
| `origin` | string | 是 | `workspace` / `library` / `implicit` |
| `placement` | string? | | `boundary` = 贴在父节点边界上的元素（端口、有向特征即参数）；缺省表示普通内部节点 |
| `source` | object? | | 源码位置；`implicit` 元素没有 |
| `parent` | string? | | 包含它的节点 id（如果有） |
| `types` | string[]? | | 声明的类型名（`types` 原文，不做继承展开） |
| `compartments` | array? | | 仓格内容，见 2.3.1 |

**不含 `elementId`**：实测 Pilot 每次加载都会为元素重新生成随机 UUID，同一个模型两次运行得到的
`elementId` 完全不同，因此它既不能作为稳定身份，也不能进入产物（否则产物不可复现）。
跨工具对齐需要作者在文本里显式声明 id，属于后续议题。

#### 2.3.1 仓格（compartments）

```json
"compartments": [
  { "title": "parameters", "entries": [ { "text": "scene: Scene", "direction": "in" } ] }
]
```

条目字段：`text`（必填，形如 `name: Type`）、`ref`（限定名）、`direction`（`in`/`out`/`inout`）、
`inherited`。

**收哪些**：元素自有的特征，外加挂在特征上的值（`= 表达式`）。两条排除规则：

1. **已经作为节点画出来的不再收**——部件、端口已经是框，不能同时在仓格里再列一遍；
2. **已经变成边的连接器不再收**（互联类视图里）。

**标题规则**（照搬官方渲染实现 `org.omg.sysml.plantuml.CompartmentEntry.getTitle()`）：

| 条件 | 标题 |
|---|---|
| 值（`FeatureValue` 成员） | `values` |
| `SubjectMembership` / `ActorMembership` / `StakeholderMembership` / `ObjectiveMembership` | `subject` / `actors` / `stakeholders` / `objectives` |
| 带方向的特征 | `parameters` |
| `BindingConnector` / `FlowUsage` / `SuccessionFlowUsage` | `bindings` / `flows` / `succession flows` |
| 其余 | 元类名去 `Usage`/`Definition` 后缀、拆驼峰、复数化，如 `AttributeUsage` → `attributes` |

**排序**（同样对齐官方）：参数优先且按 `in` → `out` → `inout`；然后按元类名；最后按名字。
仓格之间按标题字典序。类型名在仓格里用简单名（与官方 PUML 输出一致），精确引用放条目的 `ref`。

`source` 结构：`{ "document": int, "line": int, "offset": int, "length": int, "snippet": string? }`，
`line` 从 1 开始，`offset` 是文档内字符偏移。`length` 是元素的文本范围，**包含其子元素**，
因此父节点的范围比它自己的名字长得多；只要起始位置即可定位。

`snippet` 是元素原文的片段（空白折叠为单空格、超过 160 字符截断并加省略号），让宿主不必读文件
就能展示来源。**范围与片段都不包含元素前面的注释与空白**——实测 Xtext 节点的
`getTotalOffset()` 会把上一行的注释算进来，因此这里用 `getOffset()` / `getLength()`。

文档 `uri` 采用标准的 `file:///…` 形式（EMF 默认输出的 `file:/…` 已被规范化）。

### 2.4 `relationships[]`

| 字段 | 类型 | 说明 |
|---|---|---|
| `id` | string | 产物内编号，形如 `r1` |
| `kind` | string | `containment` / `typing` / `specialization` / `subsetting` / `redefinition` |
| `source` | string | 起点节点 id |
| `target` | string | 终点节点 id |
| `authored` | bool | `true` = 用户文本里写出来的；`false` = 由语义推导 |

### 2.5 `completeness`

| 字段 | 类型 | 说明 |
|---|---|---|
| `complete` | bool | 本次投影需要的事实是否都已求值 |
| `reasons` | array | 未求值的事实，每项 `{ "code": string, "detail": string }` |

已经使用的 code：

- `model-errors`：工作区存在解析或语义错误，投影是在有错误的模型上做的

注意：**空的 exposed 集合本身不是不完整**——一个视图可以合法地什么都不显示。只有当某个
必需事实求不出来时才算不完整。

## 3. 词表

`graphic`（渲染提示，不是强制）：

```text
package  part  attribute  item  port  action  state  requirement
enumeration  connection  interface  flow  multiplicity  documentation  other
```

`origin`：

| 值 | 含义 |
|---|---|
| `workspace` | 来自工作区文件，有源码位置 |
| `library` | 来自标准库等外部文件 |
| `implicit` | 隐式生成，没有源码位置（如匿名多重性） |

`relationship.kind`：

| 值 | 含义 | 目标端 |
|---|---|---|
| `containment` | 拥有关系（父子） | 子元素 |
| `typing` | 用法被定义类型化（`: T`） | 类型定义 |
| `specialization` | 特化（`:>`） | 泛化类型 |
| `subsetting` | 子集化（`subsets`） | 被子集化的特征 |
| `redefinition` | 重定义（`:>>`） | 被重定义的特征 |
| `connection` | 连接（`connect` / connection usage） | 连接器的另一个端点 |

出边规则：**只画两端都在本产物节点集内的关系**——端点没被投影就不画边，也不会为了画边而
补节点。四类语义关系只画文本里写出来的，隐式推导的不画；`containment` 两者都画，
由 `authored` 如实标记。后续计划补 `satisfy`、`verify`、`allocate`、`flow`、`connection`。

### 3.1 按视图类型的投影规则

同一个暴露集合，不同 `kind` 的投影不同——这正是视图机制的意义：

| 视图类型 | 投影差异 |
|---|---|
| `general` 及一切 `unclassified` | 所有暴露元素都是节点；连接器也是节点 |
| `interconnection`（含 `actionFlow` / `stateTransition`） | **连接器不是节点，而是 `connection` 边**；连接器自己的端也不是节点；端口带 `placement: boundary` 挂在父节点边界上 |

端口贴在哪个节点上：优先端口属主本身（若已投影）；否则找"类型闭包包含该属主"的用法节点
——`tank : Tank` 上的端口来自 `Tank`，在互联视图里画在 `tank` 的边界上。

连接器端点解析：匿名端（`connect` 自动生成的 `source` / `target`）通过
`Feature.getOwnedReferenceSubsetting()` 解析到真实特征；点路径走 `getChainingFeature()`
取链尾。解析结果不在节点集时沿属主链上找。

### 3.2 暴露范围（scope）

节点集合 = 暴露范围，定义如下：

1. `expose` 直接求值出的元素（官方实现在 `ViewUsage.getExposedElement()` 里已合并 expose 与 filter）；
2. 递归展开：上述元素**自有的结构特征**——端口、有向特征（参数）、部件、项、动作、状态、
   出现（occurrence）。连接器除外，它由视图类型决定是边还是仓格条目；
3. **数据特征不进范围**：无方向的属性、值以仓格（`compartments`）形式呈现；
4. 上限 200 个节点，超出即截断并在 `completeness.reasons` 里记 `scope-truncated`。

边界元素（`placement = boundary`）的附着关系由 `parent` 表达，**不额外生成边**；渲染器据此
把元素画在父节点边界上，也不在两者之间画连线。

## 4. 排序与编号

产物必须可复现，因此排序规则是契约的一部分：

1. `documents` 按 `uri` 字符串升序编号，`id` 从 0 开始。
2. `nodes` 按 `(ref 是否为空, ref, metaclass, 源码偏移, 暴露顺序)` 升序排列后编号 `n1…nN`。
   最后两级保证匿名与隐式元素也有确定顺序：前者来自源码位置，后者来自解析器给出的
   exposed 顺序（该顺序经实测稳定）。
3. `relationships` 按 `(kind, source 编号, target 编号)` 升序排列后编号 `r1…rN`。
4. JSON 采用 2 空格缩进、UTF-8、LF 行尾，字段顺序按本文件定义。

`id` 只在一个产物内有效，跨产物或跨运行引用一律使用 `ref`（或 `elementId`）。

## 5. modelDigest

对工作区输入计算，规则：

```text
对每个工作区文档（按相对路径升序）：
    行 = 相对路径 + "\0" + sha256(文件字节)的十六进制
modelDigest = "sha256:" + sha256(所有行以 "\n" 连接)
```

它只覆盖工作区文件，不含标准库——标准库版本变化不改变产物身份，这一点在 v0 是有意的简化，
将来若需要严格复现再扩展。

## 6. 渲染器契约

渲染器与布局器通过三个动作接入，其余一切都是实现细节：

```text
render(product, layout?)  → 视图          // 产物是输入，布局可选
emitLayout()              → layout.json   // 交互式渲染器把坐标交回来
onSelect(nodeId | null)   → 语义引用       // 宿主据此做属性面板、源码跳转
```

约束（与 Spec42 的做法一致）：

- 渲染器**只能**做布局与样式；不许从名字、标签或位置推断语义关系
- 渲染器不得修改产物；要改语义只能回到文本
- 布局失败或缺失时，渲染器必须能退化渲染（例如按顺序排布），不能报空

### 6.1 layout.json v0

布局是独立的一层，可丢弃、可重算、可编辑：

```json
{
  "schemaVersion": 0,
  "modelDigest": "sha256:…",
  "viewRef": "VehicleViews::'vehicle structure'",
  "nodes": { "n1": { "x": 104.0, "y": 80.0, "width": 124.0, "height": 40.0 } }
}
```

使用规则：

- 只有当 `modelDigest` 与 `viewRef` 都与产物一致、且 `nodes` 的键集与产物节点完全相同时，
  布局才会被采用；否则**整体丢弃重算**，不做部分复用
- `nodes` 的键是产物的节点 `id`（`n1`、`n2`…）。由于 `id` 只在单个产物内有效，
  layout 与产物必须成对使用，不能跨产物拼接
- 手工调整的结果就存在这里；重新生成产物后需要重新对齐

### 6.2 第一个实现：无头 SVG 渲染器

`render/SvgRenderer` 是契约的第一个实现，用途是无头导出与回归测试：

- 布局：分层树。按包含关系分层，叶子从左到右占槽位，父节点居中于子节点跨度；
  节点尺寸统一，保证输出可复现
- 样式：极简 CSS 内联在 SVG 里；`origin` 用线型区分（`workspace` 实线、`library` 虚线、
  `implicit` 点线）
- 不含时间戳、随机 id 或环境相关数据，因此**同一产物 + 同一布局 → SVG 逐字节一致**
- 不完整时在左上角标出 `completeness.reasons`，但照常出图

### 6.3 第二个实现：交互式 HTML 渲染器

`render/HtmlRenderer` 输出一个**自包含**的 HTML（无 CDN、无外部字体、无脚本依赖，可离线打开），
刻意复用 SVG 渲染器的图形与布局，只在上面加交互层——这样正好反过来检验契约：

交互需要的东西是否都已经在产物里？结论是够用，且都有明确出处：

| 交互 | 依赖的产物字段 |
|---|---|
| 选中与高亮 | `nodes[].id`（产物内稳定） |
| 属性面板 | `ref` / `name` / `metaclass` / `graphic` / `origin` / `types` / `parent` |
| 跳到源码 | `source.document` + `source.line` + `documents[].uri` |
| 隐藏库/隐式元素 | `nodes[].origin` |
| 关系列表 | `relationships[]`（含 `authored`） |

交互层**没有**读取任何产物之外的语义，也没有从标签猜关系——契约的限制在这里得到了验证。
检查器里展示的关系用名字（如 `containment → engine`）而不是内部 `id`，因为 `id` 只在一个产物内
有效，不该暴露给人。

交互能力（同样只依赖产物字段）：

| 能力 | 依赖的产物字段 |
|---|---|
| 模型大纲（按包含关系还原成树，点击选中并居中） | `nodes[].parent` / `name` / `placement` |
| 拖动节点（边界元素随所属节点移动） | `nodes[].placement` / `parent` |
| 拖动后重算连线 | `relationships[].source/target` |
| 导出布局（面板里给出 layout JSON） | `modelDigest` / `view.ref` / 布局对象 |

导出的布局可以另存为 `layout.json`，再用 `-Layout` 载入重放。闭环**已实测**：自动布局时
`n3` 在 `(206,184)`，手工改成 `(406,284)` 后重放，渲染结果与之一致。

## 7. 版本与兼容

- `schemaVersion` 递增表示字段语义有不兼容变化；新增可选字段视为兼容，不递增
- 解析方遇到不认识的 `schemaVersion` 必须拒绝，而不是尽力而为
- 遇到不认识的 `kind` / `graphic` / `origin` 取值时，按 `other` 处理并保留原值

## 8. 明确不在 v0 范围内

- 布局算法本身（`layout.json` 已定义，见 6.1；自动布局目前只有分层树一种实现）
- `typing` / 继承 / 子集化 / 重定义等边（先以 `types` 字段承载类型名）
- `filter` 求值失败时的"不确定"标记（当前无法区分）
- 增量产物（每次全量生成）
- 视图中元素的自定义顺序（当前按第 4 节的确定性顺序）
