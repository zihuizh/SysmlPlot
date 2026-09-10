# SysML v2 视图工具 可行性评估

- 日期：2026-09-10
- 来源：会话「评估 SysML v2 视图工具」
- 项目目录：`D:\03-Work\SysmlPlot`
- 文档性质：可行性评估（结论 + 难度分解 + 工期 + 风险），不是设计方案

## 1. 要做什么

做一个 SysML v2 的视图生成与查看工具，初始设想的功能分三层：

1. 基础：把 SysML v2 文本渲染成图形。
2. 进阶：在图形上查看元素信息，并能反向定位/反推回文本。
3. 关键约束：视图不是随便画的关系图，而是按 SysML v2 标准语义中的
   `view` / `expose` 机制建立。

参考对象：OMG Pilot Implementation、SysON、sysml2d（SysMLD）、sysmlv2-gui、
spec42、sysmlv2-lsp。

## 2. 总体结论

可做，而且现有开源项目能省掉一部分基础工作。但如果目标是**严格实现 SysML v2 的
`view / expose / filter / render` 语义**，并支持图形修改后可靠回写文本，难度属于
中高到高，不是普通的"AST 转关系图"项目。

一句话定位建议：

> 以 SysML v2 文本为事实源、严格解释 `view/expose/filter/render`、支持交互检查
> 并逐步图形编辑的本地优先视图引擎。

不建议把这个项目的目标设成"重新实现一个 SysON"。

## 3. 技术核心

规范定义的不是"解析文件后画图"，而是：

```text
SysML 文本
  → 语法树 / CST
  → 名称解析、类型与继承关系
  → 求值 ViewUsage
  → 执行 expose
  → 叠加 ViewDefinition 与 ViewUsage 的 filter
  → 确定 rendering
  → 构造视图投影
  → 布局和渲染
```

标准要求先按 `expose` 导入元素，再应用视图定义和视图用法继承/拥有的过滤条件，最后
按 rendering 生成视图产物。`expose` 本质上是一种特殊 import，支持成员、命名空间和
递归三种形式。

因此架构上必须把"模型"和"视图"分开：

```text
Semantic Model
      │
      ├── Expose Resolver
      ├── Filter Evaluator
      └── View Projector
               │
       View-neutral Graph IR
          ├── General Renderer
          ├── Interconnection Renderer
          ├── Action Renderer
          ├── State Renderer
          └── Sequence Renderer
```

**不要让画布上的节点和边直接充当 SysML 模型**。一旦涉及继承特征、重定义、隐式成员、
端口特征链和递归 expose，结构会很快失控。

## 4. 功能难度分解

| 功能 | 难度 | 主要问题 |
|---|---:|---|
| 文本解析成 AST | 中 | 可复用现成解析器，但覆盖度各不相同 |
| AST 画静态结构图 | 低～中 | 节点、边、仓格、基础布局 |
| 名称解析与跨文件引用 | 中～高 | import、alias、qualified name、标准库 |
| `expose` 求值 | 中～高 | 多种导入形式、递归、去重、视图嵌套、循环 |
| `filter` 求值 | 高 | 元数据、类型判断、模型级表达式、继承过滤 |
| General / Definition View | 中 | 元素种类多，但布局通用 |
| Interconnection View | 高 | 嵌套部件、边界端口、连接端、特征链、流 |
| Action / State View | 高 | 控制节点、守卫、分叉/汇合、伪状态 |
| Sequence View | 高 | lifeline、事件排序、消息与时间关系 |
| 信息查看 / 跳转源码 | 低～中 | 需要稳定 ID 与 source range |
| 图形操作后生成规范文本 | 高 | 模型变更、名字选择、所属关系、语法生成 |
| 保持原格式的双向编辑 | 很高 | 注释、空白、顺序、简写形式、最小文本补丁 |

## 5. "反推文本"要分三档，成本差一个数量级

1. 点击图形定位到原始文本：简单，通常增加 1～2 周。
2. 在图里新增/删除/重连后重新生成规范化文本：中高，增加约 2～4 个月。
3. 修改后尽量保留用户原有的注释、空白、元素顺序和语法简写，只产生小范围 diff：
   很高，再增加 3～6 个月。

第三档必须保留 CST / token / source range / trivia，不能只保存抽象语义模型。这也是
整个工程中最容易被低估的部分。

## 6. 参考仓库的可复用价值

### OMG Pilot Implementation

最适合作为语义正确性的参考和最终验证器。包含 Xtext 解析器、标准库、模型逻辑和可视化
实现；开发环境较重（Eclipse、EMF、Xtext、Java 21、多个插件工程）。

- 建议：作为一致性测试与语义对照。
- 若团队熟悉 Eclipse/EMF，可直接复用模型层。
- 若目标是轻量桌面或 Web 工具，不建议把整个 Pilot UI 当产品底座。

### SysON

目前最完整的 Web 图形建模参考之一，基于 Sirius Web、Spring、React、GraphQL、
PostgreSQL，已覆盖图形建模、属性面板、表格和模型操作。

关键认知：**"正确显示 exposed elements"本身代码量不大，但它强依赖一整套语义模型与
图编辑基础设施**。SysON 当前对文本更偏向导入/导出和部分直接编辑，如果强调 Git 友好的
文本 round-trip，需要额外设计。

两条用法：

- 想尽快拥有完整 Web 建模器 → 基于 SysON/Sirius Web 二次开发。
- 想做轻量、文本优先、本地文件优先的工具 → 把它作为行为参考和测试对照，不 fork 整体
  架构。

### Spec42

架构与目标最接近：本地文件优先、语义分析、VS Code、CLI、视图生成、SVG/JSON 导出，
已列出 General、Interconnection、Action Flow、State、Sequence、Browser、Grid 等视图。
Rust 核心、MIT 许可，适合借鉴或作为语义引擎候选。

风险：项目仍在快速演进，官方 conformance matrix 明确说明 SysML/KerML parsing 仍是
partial，不能假定覆盖全部语言构造。

### sysml2d / SysMLD

适合复用"视图 composer → 中间图描述 → 确定性布局 → SVG/PNG"这一段。其
`.sysml`（模型）/ `.json`（视图意图）/ `.sysmld`（布局与渲染描述）/ `.svg`（产物）
的分层值得借鉴。

注意：它的 intent 文件不完全等价于标准的 ViewUsage/Expose 语义，可以借布局与渲染
思想，不宜用 intent 替代标准 View 模型。

### sysmlv2-gui

Rust/egui，viewer-first 路线，轻量 parser 不追求完整 KerML 语义，把语义验证交给外部
validator。适合参考：桌面/Web 共用渲染、画布+模型树+属性面板布局、文件监听、
viewer-first 的迭代节奏。不适合作为严格 `view-expose-filter` 语义引擎的唯一基础。

### sysml-v2-lsp / VS Code 工具

适合复用文档符号、跳转、引用查找、source ranges、diagnostics、Webview 集成、增量文档
生命周期。但 LSP 协议本身没有标准的"给我完整 SysML 语义图"接口，通常需要自定义请求，
或直接把 parser/语义引擎作为库嵌入。

## 7. 工期估算

前提假设：开发者熟悉 TypeScript / Rust / Java 中至少一种，了解图编辑框架和编译器基本
概念；不含多人协同、权限、云端模型库和完整 3D Geometry View。

单人投入：

| 目标 | 工期 |
|---|---|
| 只读 PoC | 3～5 周 |
| 可实际使用的 MVP | 3～5 个月 |
| 支持主要标准视图、较完整语义与稳定交互 | 6～10 个月 |
| 真正双向、尽量保持原文本格式 | 10～16 个月 |

2～3 人小团队做到可发布 Beta：约 4～7 个月比较现实。

基于 SysON/Sirius Web 二开的另一条路径：原型 4～8 周；完成定制化、部署与 UX 收敛
3～6 个月；深度修改文本 round-trip 仍需额外 3～6 个月。代价是 Java/Spring/EMF/
Sirius Web 技术栈重，二次开发和升级维护成本明显高于轻量 viewer。

## 8. 建议的实施阶段

### 阶段 A：语义验证 PoC（3～5 周）

范围严格限制为：单工作区多文件解析、General View、`view def` 与 `view`、基础
`expose X` 与递归 `expose X::**`、类型/元数据类 `filter`、自动布局、SVG 导出、
点击节点显示 qualified name / 类型 / 所属关系 / 源码位置。

目标不是好看，而是证明：

```sysml
view 'vehicle structure' : 'Part Structure View' {
    expose vehicle::**;
    filter @SysML::PartUsage;
    render asTreeDiagram;
}
```

能产生语义正确、可追踪回源的结果。

### 阶段 B：可用 MVP（累计 3～5 个月）

补齐 MembershipExpose / NamespaceExpose / recursive 组合；ViewDefinition 继承的
filter 与 render；General + Interconnection View；模型浏览器、属性查看、源码双向定位；
增量刷新；手工调整位置并独立保存布局；SVG/PNG 导出；100～300 个语义测试模型；
使用 Pilot 与 SysON 做差分验证。

### 阶段 C：工程化 Beta（累计 6～10 个月）

增加 Action Flow、State Transition、Sequence View；继承特征与重定义显示；复杂端口、
连接、流与特征链；视图嵌套；大模型懒加载与缓存；稳定 ID；文本暂时不完整时保留上一版
有效视图；快照回归测试与布局稳定性测试。

### 阶段 D：双向编辑（累计 10～16 个月）

图上创建元素/连接/端口；修改名称、类型、多重性；reconnect；自动增删 `expose`；
语义命令转文本补丁；冲突检测；undo/redo；保留注释与格式；图、文本、诊断的原子同步。

## 9. 推荐技术路线

若走"文本优先的轻量工具"：

- 语法与语义核心：深入评估 Spec42 的 parser 与语义发布机制，OMG Pilot 作为权威验证器。
- 视图中间层：自定义稳定的 ViewGraph IR，不绑定 React Flow / ELK / egui 的对象。
- 布局：ELK.js / ELK、Graphviz，或参考 sysml2d 的确定性布局。
- 前端：VS Code Webview 或 React 独立桌面/Web UI。
- 文本编辑：Monaco + LSP。
- 图形编辑：第一版只产生语义命令，统一交给模型层改文本。
- 测试：对照 OMG 示例、Pilot 输出与 SysON 行为。

若走"尽快交付完整 Web 图形建模产品"：直接基于 SysON/Sirius Web 二次开发。

## 10. 与 SysON 的关系（已实测的约束）

以下结论来自同期的 SysON view/expose 实验，直接影响本工具的设计边界：

- 导入/建图/排布是三个独立阶段：`expose` 只决定"哪些语义元素成为显示候选"，不决定
  坐标、布局方向、缩放，也不保证表示已排布。
- 标准图描述默认布局选项为 `NONE`，自动排布是单独的布局计算步骤。
- `expose X::*` 不展开后代；`expose X::**` 会递归展开。在真实代码模型上递归 expose
  会一次产生数百个节点与边（AntennaPod 实测 542 节点 / 498 边），缩放到画布后不可读。
- 标准 View 类型（General / Interconnection / ActionFlow / StateTransition）是在
  expose 候选集之上做**二次过滤**与表现差异，不影响文本引用的解析。
- 大图的实际瓶颈通常在边的密度与交叉，其次才是可见元素总数。

结论：SysON 适合作为语义与显示行为的对照基准，但其逐元素、逐请求的交互式工作方式
不适合承担"批量、确定性生成视图"的职责，这正是新工具的空间。

## 11. 风险清单

1. "回写并保持原格式"很可能占整个工程三分之一以上的工作量，容易被低估。
2. 一开始就追求覆盖所有标准视图，风险高；应先用 General View 打通整条链路。
3. 解析器覆盖度不足会同时影响正确性与可信度，需要明确的 conformance 边界与验证器。
4. 大模型（数千元素）下的布局与交互性能，需要一开始就设计懒加载与拆图策略。
5. 若把画布对象当作模型，后期加入继承、重定义、端口链时会引发大规模返工。
6. 复用 SysON 走二开路线时，技术栈与升级维护成本会成为长期负担。

## 12. 结论

最合理的定位不是"重新实现一个 SysON"，而是一个以 SysML v2 文本为事实源、严格解释
`view/expose/filter/render`、支持交互检查并逐步图形编辑的本地优先视图引擎。

首期目标建议锁定 **General View + Interconnection View + 信息检查 + 源码定位**。
单人 3～5 个月可形成有价值的 MVP；2～3 人团队约 2～3 个月。一开始就追求全部视图与
无损双向编辑，风险很高。
