# SysmlPlot

SysML v2 视图生成与查看工具。以 SysML v2 文本为事实源，按标准的
`view` / `expose` / `filter` / `render` 机制生成视图，并在此基础上支持在图上查看元素
信息、定位回源码。

## 文档

| 文档 | 内容 |
|---|---|
| `SYSML-VIEW-TOOL-FEASIBILITY.md` | 可行性评估：难度分解、工期、参考仓库取舍、风险 |
| `docs/DEVELOPMENT.md` | **开发规范**：分支与提交、目录命名、代码与文档约定、验证要求、提交前检查 |
| `docs/ROADMAP.md` | 阶段划分与各阶段验收标准 |
| `docs/VIEW-PRODUCT.md` | **视图产物契约**：字段、词表、暴露范围、排序与渲染器契约 |
| `docs/GRAPH-LIB-P0.md` | 图库框架 P0：G6 与 Cytoscape 原型和实测结果 |
| `docs/GRAPH-LIB-P1.md` | 图库框架 P1：原生布局、交互与动画的实测与选型结论 |
| `docs/RELATION-DEMO.md` | 关系与追溯 Demo：元素关系、局部关系图、追溯关系、影响范围、追溯矩阵 |
| `docs/ORACLE-DIFF.md` | 与官方渲染、SysON 的差分验证结论（含已知差异的原因） |
| `docs/PERF-BASELINE.md` | 性能基线：分档耗时与瓶颈定位 |
| `docs/BACKLOG.md` | 已知但尚未做的事情 |
| `docs/ENVIRONMENT.md` | 本机已验证的环境事实与依赖位置 |
| `docs/archive/` | 已完成阶段的计划、验收记录与历史结论 |

## 远程仓库

```text
origin  https://github.com/zihuizh/SysmlPlot.git
```

## 开发准备

仓库使用版本化的 Git 钩子（`.githooks/`），新克隆后执行一次：

```powershell
powershell -ExecutionPolicy Bypass -File scripts/install-hooks.ps1
```

它会设置 `core.hooksPath`，之后每次提交都会自动执行检查。规则见 `docs/DEVELOPMENT.md`。

## 快速开始

需要 Java 21（见 `docs/ENVIRONMENT.md`）。首次运行：

```powershell
powershell -ExecutionPolicy Bypass -File scripts\build.ps1
powershell -ExecutionPolicy Bypass -File scripts\run-view.ps1                        # 列出视图与诊断
powershell -ExecutionPolicy Bypass -File scripts\run-view.ps1 -View "VehicleViews::'vehicle structure'" -Out build\product.json
powershell -ExecutionPolicy Bypass -File scripts\run-view.ps1 -View "VehicleViews::'vehicle structure'" -Svg build\view.svg -EmitLayout build\view.layout.json
powershell -ExecutionPolicy Bypass -File scripts\run-view.ps1 -View "VehicleViews::'vehicle structure'" -Html build\view.html
powershell -ExecutionPolicy Bypass -File scripts\run-spike.ps1                       # 探查用：元素、exposed、PlantUML/SVG
powershell -ExecutionPolicy Bypass -File scripts\diff-oracle.ps1                     # 与官方渲染做差分验证
powershell -ExecutionPolicy Bypass -File scripts\render-png.ps1 -View "VehicleViews::'vehicle structure'" -Out build\view.png
powershell -ExecutionPolicy Bypass -File scripts\run-view.ps1 -View "StructureViews::'structure (parts)'" -Workspace samples/structure -At "samples\structure\model\StructureModel.sysml:20:20"
powershell -ExecutionPolicy Bypass -File scripts\serve-view.ps1 -Workspace samples/structure -View "StructureViews::'structure (parts)'" -Port 8765
powershell -ExecutionPolicy Bypass -File scripts\run-view.ps1 -Workspace samples/requirements -Matrix          # 需求追溯矩阵
powershell -ExecutionPolicy Bypass -File scripts\run-view.ps1 -Workspace samples/requirements -Matrix -GapsOnly -Gate   # 覆盖率门禁（有缺口 → 退出码 4）
powershell -ExecutionPolicy Bypass -File scripts\check-trace.ps1                              # 对"应当无缺口"的工作区跑门禁
powershell -ExecutionPolicy Bypass -File scripts\check-products.ps1                           # 产物回归：期望产物 + schema + 确定性
python scripts\diff-syson.py --list-projects          # 与 SysON 做差分（需本机 SysON 在跑）
```

可加 `-Puml "<view 限定名>"` 输出 PlantUML，或用 `-Svg "<view 限定名>" -Out build\view.svg`
导出 SVG。历史验证结论见 `docs/archive/PHASE-1-FINDINGS.md`。

视图产物的契约见 `docs/VIEW-PRODUCT.md`，机器可读 schema 见
`schema/view-product.schema.json`（可用 `python -m jsonschema` 校验产物）。

现有 Java 渲染器继续作为稳定的导出与回归基线：`-Svg` 出图，`-EmitLayout` 落盘布局，
`-Layout` 用既有布局重放；`-Html` 输出自包含交互页。
`scripts\render-png.ps1` 导出 PNG（先出 SVG，再用无头浏览器光栅化，`-Scale` 可放大，
需要本机有 Chrome 或 Edge）。

## 当前状态

阶段 0–3 已完成：解析、视图产物、基础展示、模型查询和追溯均有可运行实现与回归验证。
已完成阶段的计划和验收记录已移入 `docs/archive/`。

当前重点是**展示层升级**。项目已确定采用图库框架承接视图产物之后的布局、交互和动画，
并已选定 **G6 5.1.1** 作为展示层框架（Cytoscape 保留为体积敏感的备选）。现有自研布局和
渲染器保留为基线与兼容出口，不再作为主要演进方向。P1 原型与实测数据见
`docs/GRAPH-LIB-P1.md`；当前工作和选择标准见 `docs/ROADMAP.md` 与 `docs/BACKLOG.md`。

## 关键约束

**必须用 Java 21。** OMG Pilot 的构建产物是 class file version 65（Java 21），本机
PATH 上的默认 `java` 是 17，无法运行。可用的 Java 21 位置见 `docs/ENVIRONMENT.md`。

## 目录结构

```text
SysmlPlot/
  README.md
  SYSML-VIEW-TOOL-FEASIBILITY.md
  docs/                规划、方案、环境事实
  samples/             用于验证的 SysML v2 样例模型
  scripts/             构建与运行脚本
  src/                 工具源码
```
