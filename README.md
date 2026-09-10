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
| `docs/PHASE-1-PLAN.md` | 阶段 1 技术方案：解析器接入、视图求值、产物契约 |
| `docs/ENVIRONMENT.md` | 本机已验证的环境事实与依赖位置 |

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
```

可加 `-Puml "<view 限定名>"` 输出 PlantUML，或用 `-Svg "<view 限定名>" -Out build\view.svg`
导出 SVG。当前结论见 `docs/PHASE-1-FINDINGS.md`。

视图产物的契约见 `docs/VIEW-PRODUCT.md`，机器可读 schema 见
`schema/view-product.schema.json`（可用 `python -m jsonschema` 校验产物）。

渲染走同一个命令：`-Svg` 出图，`-EmitLayout` 落盘布局，`-Layout` 用既有布局重放
（产物与布局一致时，重放结果与首次渲染逐字节相同）。
`-Html` 输出自包含的交互式页面（平移、缩放、点选节点看属性、按来源过滤），可离线打开。
`scripts\render-png.ps1` 导出 PNG（先出 SVG，再用无头浏览器光栅化，`-Scale` 可放大，
需要本机有 Chrome 或 Edge）。

## 当前状态

阶段 0 完成（评估与仓库初始化），阶段 1 准备开工：
**接入 OMG 官方解析器（SysML v2 Pilot Implementation），按 `view` / `expose` 机制生成视图。**

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
