# 与官方渲染的差分验证

目的：用官方实现（OMG Pilot 的 PlantUML 渲染）作为对照，检查我们的投影有没有漏画东西。
它比"只对 exposed 集合"更严——官方会做自己的投影，能暴露我们遍历范围上的差距。

## 怎么跑

```powershell
powershell -ExecutionPolicy Bypass -File scripts\build.ps1
powershell -ExecutionPolicy Bypass -File scripts\diff-oracle.ps1
```

脚本对每个样例视图做两件事：

1. 用我们的工具产出视图产物（JSON）
2. 用官方渲染输出 PlantUML（`run-spike.ps1 -Puml`）

然后由 `scripts/oracle_diff.py` 比较**节点名集合**与边数量。

## 怎么解读

| 现象 | 含义 |
|---|---|
| `missing`（官方画了、我方没有） | **真缺陷**，脚本以退出码 1 报错——我们的遍历范围不够 |
| `extra`（我方画了、官方没有） | 需要人判断：可能是我们的投影选择，也可能是多余 |
| 边数量不同 | 见下面的"已知差异"，不需要一致 |

**刻意不做"必须完全相等"的断言**：官方渲染有它自己的选择（递归进已暴露元素、重复画同一
元素、省略无名元素、把参数画成端口），差异需要人来判断。这个脚本的职责是把差异摆出来。

## 当前结果（2026-09-10）

四个样例的节点名集合**全部一致**，无 missing：

| 样例 | 官方节点 | 我方节点 | 官方边 | 我方边 |
|---|---:|---:|---:|---:|
| vehicle / structure | 3 | 3 | 2 | 2 |
| structure / parts | 10 | 10 | 18 | 13 |
| interconnection / power | 5 | 5 | 5 | 3 |
| parameters / values | 6 | 7 | 0 | 4 |

## 已知差异及其原因

**1. 边数量比官方少——官方会重复画节点。**

`structure` 样例：官方把 `frontLeft`、`sportsAxle` 各画了两遍（树里一份、类型关系里一份），
于是同一条 subsetting / redefinition / typing 边也出现两次，18 条里有 5 条是重复。
我们的产物不重复，五类边的语义与端点与官方一一对应。

**2. 边界元素的附着不生成边。**

`interconnection` 样例：官方画 `tank o-- outlet`（部件到端口的聚合边）。我们的产物用
`nodes[].placement = "boundary"` + `parent` 表达同一件事，不另生成边；渲染器据此把端口画在
父节点边界上，也不画连线（画了会从节点本体中间穿过）。这是表示差异，不是漏画。

**3. 参数被官方画成端口。**

`parameters` 样例：官方把 `in scene : Scene` 渲染成 `portin "scene"`，而不是 `parameters` 仓格。
我们已对齐这一行为——有向特征进暴露范围、作为边界元素处理，因此节点从 3 个变成 7 个
（`Focus`/`Shoot` 各带两个参数），与官方的 6 个去重后一致（我方 `image` 出现两次，
分别是 `Focus` 和 `Shoot` 的参数，是两个不同元素）。

**4. 我方边数多于官方（parameters：4 vs 0）。**

官方在 Tree 模式下不画参数的类型边；我们画了 4 条 `containment`（父节点到参数）。
这与第 2 条是同一个表示差异。
