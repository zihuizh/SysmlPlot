# 待办与已知缺口

记录当前**已知但尚未做**的事情，避免依赖记忆。每项做完后从本文件移除，结论进对应文档
（契约进 `docs/VIEW-PRODUCT.md`，实测事实进 `docs/PHASE-1-FINDINGS.md`）。

更新日期：2026-09-10

## 阶段 1 剩余项（按依赖顺序）

### 1. 关系边补全 ✅（已完成）

`containment` / `typing` / `specialization` / `subsetting` / `redefinition` 五类已实现，
来源见 `docs/VIEW-PRODUCT.md` 第 3 节。验证样例：`samples/structure`。
仍待补的语义边：`satisfy`、`verify`、`allocate`、`flow`、`connection`。

### 2. 按视图类型分投影（部分完成）

- ✅ **Interconnection**：连接器变边、端口贴节点边界（`view.kind` + `placement` + `connection`）
- ❌ **Action Flow**：动作/控制节点 + 有序流边（现在按 interconnection 规则处理，够用但不完整）
- ❌ **State Transition**：状态节点 + 带 trigger/guard/effect 的迁移边

仍待补的语义边：`satisfy`、`verify`、`allocate`、`flow`（`connection` 已完成）。

注：Action Flow 与 State Transition 在标准库里特化自 InterconnectionView，目前判定为各自的
`kind` 但投影走 interconnection 规则；两者的专有节点/边规则尚未实现。

### 3. 仓格与源码联动 ✅（已完成）

- ✅ **仓格**：`nodes[].compartments`，标题规则与排序对齐官方 `VCompartment`
- ✅ **源码联动（单向）**：`source.snippet` 带原文片段，交互式页面里可显示来源并跳转编辑器
  （`vscode://file/<path>:<line>`）
- ❌ **反向联动**：编辑器光标位置驱动图上高亮，需要编辑器宿主（VS Code 扩展或 LSP 客户端）
- ❌ 仓格里的 `documentation`（doc 文本）尚未收入；官方在 `VCompartment.addDocumentation` 里
  有专门处理，doc 通常占用节点下方的独立区域

### 4. 端口方向（新）

互联视图里端口目前只有位置，没有方向。Pilot 的 `VComposite.isPortOut()` 用"是否为连接器
第一个 owned end feature"判定 `portin` / `portout`（官方注释承认该判据是权宜之计）。
需要决定我们采用什么判据，并在产物里给端口加方向字段（如 `direction: in/out/inout`）。

更可靠的方向来源是端口特征自身的方向（`in` / `out` / `inout` 有向特征），优先级应高于端序推断。

### 5. 逐视图对照 oracle ✅（已完成）

`scripts/diff-oracle.ps1` + `scripts/oracle_diff.py`：对四个样例视图比较节点名集合与边数量，
结论与已知差异见 `docs/ORACLE-DIFF.md`。目前四个样例节点名集合全部一致。

后续：把差分验证纳入提交前检查（当前需手动运行，单次约 50 秒，放钩子里太重）。

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
