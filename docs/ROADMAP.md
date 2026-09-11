# 路线图

## 阶段 0 · 评估与准备（已完成）

- 完成 SysML v2 视图工具可行性评估（见 `SYSML-VIEW-TOOL-FEASIBILITY.md`）
- 摸清本机环境与可复用资产（见 `docs/ENVIRONMENT.md`）
- 初始化仓库

## 阶段 1 · 解析与视图生成（当前）

**目标**：使用 OMG 官方解析器（SysML v2 Pilot Implementation）解析 SysML v2 文本，
并按照标准的 `view` / `expose` 机制生成视图。

> 剩余项与已知缺口集中记录在 `docs/BACKLOG.md`。

**范围**

1. 解析：多文件工作区 + 标准库，能拿到解析后的语义模型并报告诊断
2. 视图求值：识别 `view def` / `view`，求值 `expose`（成员、命名空间、递归），
   应用视图定义与用法的 `filter`
3. 投影：把选中的语义元素投影为视图无关的图中间表示（节点 / 边）
4. 布局与输出：确定性布局，产出结构化 JSON 与可查看的图形（先 SVG）
5. 信息回溯：节点能对应回源码位置与限定名

**验收标准**

- 给定一个样例模型，能列出其中所有 view，并对其中的 General View 输出节点/边集合
- 同一模型重复运行，输出稳定（节点与边顺序、ID 分配确定）
- 视图中不含语义范围外的元素；解析失败的元素以显式的"不完整原因"呈现，而不是静默丢弃
- 与对照工具（`sysmlv2-tool`）在 exposed 元素集合上一致

**明确不做**（留到后续阶段）

- 图形编辑与文本回写（阶段 3 以后）
- `filter` 的完整元数据与表达式求值
- Sequence / State / Action 等行为视图（先打通 General View）
- 增量解析与按键级实时刷新

## 阶段 2 · 可用 MVP

- ✅ General + Interconnection 视图
- ✅ 属性面板（交互式页面的检查器）
- ✅ 暴露范围：`expose` 求值（官方实现）+ 递归展开自有结构特征 + 视图定义泛化闭包判定类型
- ✅ `expose` 三种形式（成员 / 命名空间 / 递归）与视图定义继承：样例 `samples/expose-forms`，纳入差分验证
- ✅ 与 OMG Pilot 的差分验证（`scripts/diff-oracle.ps1`，结论见 `docs/ORACLE-DIFF.md`）
- ✅ SVG 导出
- ✅ PNG 导出（`scripts/render-png.ps1`：SVG + 无头浏览器光栅化，支持 `-Scale`）
- ✅ 模型浏览器（交互式页面左侧大纲树，点击选中并居中）
- ✅ 源码反查的引擎侧（`--at <path>:<line>[:<col>]` 列出覆盖该位置的节点，最内层在前）
- ❌ 编辑器宿主那一半（编辑器光标驱动图上高亮，需要 VS Code 扩展或 LSP 客户端接上 `--at`）
- ✅ 手工调整布局并独立保存（拖动节点 → 导出 layout.json → `-Layout` 重放，闭环已实测）
- ❌ 与 SysON 的差分验证（SysON 为 Docker 部署，启动成本高，优先级低于 Pilot）

## 阶段 3 · 工程化

- 行为视图（Action / State / Sequence）
- 大模型懒加载、缓存、稳定 ID
- 快照回归测试与布局稳定性测试

## 阶段 4 · 双向编辑

- 图上创建/删除/重连元素
- 语义命令转文本补丁，保留注释与原格式
