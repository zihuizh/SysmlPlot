# 待办与已知缺口

记录当前**已知但尚未做**的事情，避免依赖记忆。每项做完后从本文件移除，结论进对应文档
（契约进 `docs/VIEW-PRODUCT.md`，实测事实进 `docs/PHASE-1-FINDINGS.md`）。

更新日期：2026-09-10

## 阶段 1 剩余项（按依赖顺序）

### 1. 关系边补全 ✅（已完成）

`containment` / `typing` / `specialization` / `subsetting` / `redefinition` 五类已实现，
来源见 `docs/VIEW-PRODUCT.md` 第 3 节。验证样例：`samples/structure`。
仍待补的语义边：`satisfy`、`verify`、`allocate`、`flow`、`connection`。

### 2. 按视图类型分投影

现在只有一种通用投影。至少要区分：

- **Interconnection**：嵌套 part、端口贴节点边界、连接器当边；端口与 connector usage
  不是并列的框（端点解析规则与 General 不同）
- **Action Flow**：动作/控制节点 + 有序流边
- **State Transition**：状态节点 + 带 trigger/guard/effect 的迁移边

顺便要补的语义边：`satisfy`、`verify`、`allocate`、`flow`、`connection`。

### 3. 仓格与源码联动

- 节点上显示属性的仓格（attributes / ports / values / doc），目前只有名字 + 图形类别
- 点击节点跳到源码（现在只显示文档与行号）；需要宿主侧提供打开编辑器的能力

## 阶段 2/3 的已知项

| 项 | 说明 |
|---|---|
| 布局按视图类型分化 | 现在只有分层树，且**只按包含关系分层，语义边不参与布局**——实测 `samples/structure` 里 typing/specialization 边会横穿整张图。需要按视图类型换布局：General 按类型层级、Interconnection 要正交路由与端口约束、State/Action 要分层流、Sequence 要泳道 |
| 手工布局的交互回写 | `emitLayout` 已有命令行版本，图形界面的拖拽回写未做 |
| 折叠/展开、搜索定位 | 大图可用性的必需项 |
| 大模型性能 | `readAll` + 解析 + 求值在数百文件规模下的耗时与内存未知 |
| 增量产物 | 现在每次全量生成 |

## 语义与质量待验证项

| 项 | 说明 |
|---|---|
| filter 求值失败的语义 | 条件求值不了时，候选是"被排除"还是"标记不确定"，尚未实测。规范倾向后者，我们目前无法区分 |
| 诊断去重 | 同一个链接错误会被 `Resource.getErrors()` 与 Xtext validator 各报一次，输出正式诊断前需按 code + 位置去重 |
| 隐式元素的分类边界 | `origin` 现在按"所在资源 + 是否有源码节点"判定，实测出现 Documentation 归为 `library` 的情况，判定规则需要再校 |
| `getExposedElement()` 顺序的跨平台稳定性 | 已在本机验证两次一致，跨平台未验证 |

## 仓库与流程

| 项 | 说明 |
|---|---|
| 功能分支清理 | `feat/pilot-parser-spike`、`feat/view-product`、`feat/svg-renderer`、`feat/interactive-renderer` 已合并且本地/远端都还在，待统一删除 |
| pre-commit 未覆盖产物回归 | 钩子目前只做"样例能通过官方解析器校验"，还没有比对产物与渲染结果 |
| 没有单元测试框架 | 验证目前靠命令行脚本 + 人工比对，尚未引入测试目录与断言 |
