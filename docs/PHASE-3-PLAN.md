# 阶段 3 计划：模型查询与追溯

日期：2026-09-13。上游：`docs/ROADMAP.md` 的阶段 3；前置：阶段 2 已完成并合并。

## 1. 阶段目标

阶段 2 解决的是"把视图画出来"。阶段 3 解决的是**用模型回答问题**：

> 从"单视图渲染"升级为"跨视图、跨模型的查询与追溯"——不靠 grep、不靠人肉，直接问模型。

一句话验收：**下面六个问题全部能从工具里答出来，并且答案能落回源码位置。**

| # | 问题 | 现在能答吗 |
|---|---|---|
| 1 | 需求 X 由哪些部件满足？ | ✅ `satisfy` 边反向查 |
| 2 | 部件 P 的行为链是什么？ | ✅ `allocate` / `flow` / `perform` / `succession` 四类边齐备 |
| 3 | 改动元素 E 会影响哪些需求？ | ✅ 反向可达查询（`--query impact`、`/impact`） |
| 4 | 哪些需求没有任何实现或验证？ | ✅ 追溯矩阵 + 覆盖率门禁（`--matrix --gaps-only --gate`） |
| 5 | 同一元素出现在哪些视图里？ | ✅ 已验证（同一 ref 出现在 3 个视图） |
| 6 | 以某元素为中心，它连到谁？ | 🟡 有 1 跳关系，但不可点击、无多跳、无局部图 |

## 2. 范围与非目标

**做**

- 关系可导航：点关系里的元素即可切换中心，带历史回退
- **模型索引**：跨视图、全模型的关系索引 + 查询层
- 跨视图跳转、局部关系视图、影响范围分析
- 需求追溯矩阵与覆盖率门禁
- 补齐支撑上述功能的语义边：`verify`、`derive`、`perform`、`succession`
- 需求编号与正文进产物（顺带补 `documentation` 仓格）

**不做**

- 图形编辑与文本回写（阶段 4）
- Action / State / Sequence 的完整规范符号（本阶段只到"能表达关系"的程度）
- 多人协同、权限、云端模型库

## 3. 架构增量

现有形状（阶段 2 结束）：

```text
.sysml → OMG 解析 → [builder] → view-product.json（每视图一份）→ SVG / PNG / 交互页
```

本阶段新增一条支线：**全模型索引**，它是四个新功能的共同底座。

```text
.sysml → OMG 解析 → [builder] → view-product.json（不变）→ 图渲染
                            └→ workspace-index.json（新增）
                                    ├→ viewsOf(ref)       跨视图跳转（问题 5）
                                    ├→ ego(ref, depth)    局部关系视图（问题 6）
                                    ├→ impact(ref, kinds) 影响范围分析（问题 3）
                                    └→ matrix(rows, cols) 追溯矩阵（问题 1、4）
```

### 3.1 索引契约（草案）

```json
{
  "schemaVersion": 0,
  "modelDigest": "sha256:…",
  "elements": [
    { "ref": "Model::tank", "name": "tank", "metaclass": "PartUsage",
      "reqId": null, "ownerMembership": "OwningMembership",
      "origin": "workspace", "source": { "document": 0, "line": 12, "snippet": "…" },
      "views": ["Views::structure"] }
  ],
  "relations": [
    { "kind": "satisfy", "source": "Model::vehicleDesign",
      "target": "Model::massLimitReq", "authored": true }
  ]
}
```

另外两个字段是为追溯矩阵加的：`reqId`（需求号，如 `1.1`）让矩阵按编号排列；
`ownerMembership`（所属成员关系的元类）让矩阵能排除**编译器生成的包装用法**——
`objective { … }` 会生成一个需求用法，`verify X;` 又会生成一个被验证需求副本，
它们不是被建模的需求（Pilot 给前者起名叫 `obj`，官方渲染器同样特殊处理它）。

三条硬约束：

1. **索引用 `ref`，不用产物的 `id`**——`id` 只在单个产物内有效（阶段 2 已确立）。
2. **只存最小信息**，细节按需回查；大模型上索引会很大，预览服务提供按需接口。
3. **必须带 `origin` 与 `authored`**——影响分析与覆盖率都要能把隐式/库元素过滤掉
   （实测：递归展开不带过滤会多出 11 个隐式/库元素）。

### 3.2 查询层

四个消费者共用一个遍历函数，避免各做一套：

```text
traverse(ref, direction = out | in | both, kinds = [...], depth = N)
```

| 消费者 | 调用方式 |
|---|---|
| 关系列表（1 跳） | `traverse(ref, both, 全类型, 1)` |
| 局部关系视图 | `traverse(ref, both, 全类型, N)` → 生成**子产物** |
| 影响范围分析 | `traverse(ref, in, 影响型边, N)`，再按 `authored`/`origin` 过滤 |
| 追溯矩阵 | `traverse(reqRef, in, [satisfy, verify, derive], 1)` |

### 3.3 追溯矩阵与覆盖率门禁

矩阵是**全模型**口径（走索引，不看某个视图的 `expose` 边界），行是需求用法、列是关系：

```text
[matrix] requirements=2 satisfied=1 verified=1 gaps=1

## RequirementsModel  (需求 2 / 已满足 1 / 已验证 1 / 缺口 1)
- [1.1] massLimitReq  (RequirementsModel.sysml:14)
    满足方: vehicleDesign
    验证方: MassTest, massTest
    派生出: chassisMassReq
- [1.2] chassisMassReq  (RequirementsModel.sysml:35)  [缺口]
    派生自: massLimitReq
```

三点设计：

1. **稀疏列表**而不是网格——真实模型里需求成百上千，网格大半是空格子，既看不清也读不动；
2. **按包分块**——矩阵的读者是建模的人，包是他们的工作单元；
3. **缺口 = 既没有满足方、也没有验证方**，这是"没做"的可枚举证据，也是门禁的判据。

命令行：`--matrix`（打印上面的文本）、`--gaps-only`（只留缺口的行）、`-Out <file>`（输出 JSON）、
`--gate`（有缺口时以退出码 4 结束）。预览服务另有 `GET /matrix?gaps=1`。

门禁跑在**应当没有缺口**的工作区上（清单在 `scripts/check-trace.ps1`）：
`samples/traceability` 是正样本（每条需求都被满足且被验证），`samples/requirements`
是反样本（故意留一条派生需求没有满足方/验证方），所以不在默认名单里。

**局部视图做成产物变换，不做成渲染器功能**：`ego(ref)` 生成一个格式相同的子产物，于是
SVG / PNG / 交互页三种出口免费都支持，不用为它写第二套渲染。

## 4. 工作项

| 编号 | 工作项 | 依赖 | 交付物 | 预估 |
|---|---|---|---|---|
| S3-1 | 关系可导航 | — | 检查器里的关系条目可点击切换中心 + 历史回退（面包屑 / 后退栈） | 0.5 天 |
| S3-2 | 模型索引与查询层 | — | `workspace-index.json` + `traverse()` + 预览服务按需接口 | 2 天 |
| S3-3 | 跨视图跳转 | S3-2 | 检查器显示"出现在哪些视图"，点击切到该视图 | 1 天 |
| S3-4 | 局部关系视图 | S3-2 | `ego(ref, depth)` 子产物 + 星形/放射布局 | 1 天 |
| S3-5 | 影响范围分析 | S3-2 | 反向可达 + 边类型过滤 + 落回源码位置 | 1 天 |
| S3-6 | 语义边补齐 | — | `verify` / `derive` / `perform` / `succession`；需求 `reqId` 与正文；`documentation` 仓格 | 1.5 天 |
| S3-7 | 追溯矩阵与覆盖率门禁 | S3-2、S3-6 | 稀疏列表 + 按包分块矩阵 + gaps-only；覆盖率进提交前检查 | 2 天 |

S3-2 是关键路径：S3-3/4/5/7 都挂在它上面。S3-1 与 S3-6 可与它并行。

## 5. 各工作项验收

| 编号 | 验收标准 |
|---|---|
| S3-1 | 点关系里的元素能把中心切过去；连点三层后能逐步退回起点 |
| S3-2 | 同一工作区的索引两次运行逐字节一致；索引里每个 `ref` 都能在对应产物里找到 |
| S3-3 | 检查器列出该元素出现的全部视图；点击后页面切到那个视图且中心仍是该元素 |
| S3-4 | 局部视图节点集 = 中心 + N 跳邻居；去掉任意一条边后重算结果一致；三种导出都能用 |
| S3-5 | 改动一个部件能列出受影响的需求并给出各自源码位置；隐式/库元素默认不出现在结果里 |
| S3-6 | 四类新边在样例上与官方渲染**零 missing**；无法对照的部分要写清依据（`succession` 在动作视图里有官方对照，见 `docs/ORACLE-DIFF.md`） |
| S3-7 | 覆盖率数字可复算；未实现/未验证需求可枚举；门禁在样例上能卡住有缺口的模型 |

## 5.1 测试层级

单个工作项的验收标准不够——它们只保证"这一项做对了"，不保证"改动没把别处弄坏、
换一批模型也站得住"。所以再叠四层，全部可执行：

| 层级 | 范围 | 跑法 | 通过标准 | 频率 |
|---|---|---|---|---|
| **T0 样例回归** | `samples/` 的 9 个自建样例、18 个文件（12 个视图） | `scripts/diff-oracle.ps1` + 确定性比对 + schema 校验 + `scripts/check-trace.ps1` | 与官方渲染零 missing；两次运行产物逐字节一致；全部过 schema；应当无缺口的工作区真的没有缺口 | 每次提交（pre-commit 覆盖样例校验与覆盖率门禁） |
| **T1 语料解析验收** | 官方 SysML v2 Release 全量 403 个 `.sysml`/`.kerml` | `run-view.ps1 -Workspace <corpus> -Check -Report …` | 逐文件错误数可复算；与官方工具 `validate` 的差异逐条解释 | 手动 / 每个工作项收尾 |
| **T2 官方视图验收** | 语料里含 `view`/`expose` 的 5 个模型 | 生成产物 + 与官方 PUML 差分 | 零 missing；产物两次一致 | 手动 / 阶段收尾 |
| **T3 扩面验收** | 语料里**没有 view** 的 246 个模型 | 自动生成 view 包装（见 5.2）+ T2 同样的检查 | 全部能生成产物；与官方渲染一致或差异可解释 | 手动 / 阶段收尾 |
| **T4 性能基线** | 分档规模（10 / 50 / 100 / 251 / 403 文件） | 见 5.3 | 不超过基线阈值，且分阶段耗时可比 | 手动 / 每阶段一次 |

## 5.2 官方语料验收

语料就位在本机的 Pilot 目录旁（`SysML-v2-Release`，与 0.57 版 Pilot 配套）：

| 目录 | 文件数 | 用途 |
|---|---:|---|
| `sysml/src/examples` | 95 | 真实样例（Vehicle Example、Spec Annex A、Metadata、Geometry、Interaction Sequencing…） |
| `sysml/src/training` | 100 | 培训模型，覆盖面广、单文件小 |
| `sysml/src/validation` | 56 | 专为校验设计，部分**故意非法**——这类文件的标准是"诊断与官方一致"，不是"零错误" |
| `kerml/src` | 58 | KerML 层 |
| `sysml.library` | 94 | 标准库本身（也可当压力语料） |

**含 `view`/`expose` 的官方模型（T2 的直接素材）**：

```text
examples/Simple Tests/ViewTest.sysml
examples/Vehicle Example/SysML v2 Spec Annex A SimpleVehicleModel.sysml
training/42. Views/Views Example.sysml
validation/11-View and Viewpoint/11a-View-Viewpoint.sysml
validation/11b-Safety and Security Feature Views.sysml
```

**没有 view 的模型怎么验收（T3）**：自动生成视图包装——为每个模型的目标包生成一个包装文件，
放在与模型同一个工作区里：

```sysml
package CorpusView_<模型名> {
    private import <目标包>::*;
    private import StandardViewDefinitions::*;

    view 'corpus <目标包>' : GeneralView {
        expose <目标包>::**;
    }
}
```

生成器要做三件事：找出每个文件/目录的顶层包名、把模型复制（或链接）到独立工作区、
写入包装文件。这样 251 个模型**全部**能被视图流水线覆盖，而不是只有那 5 个。

**已知的落地细节**：

- 我们的工具按"一个工作区目录"加载，所以 T3 要为每个模型建独立工作区，不能把 251 个模型堆在一个目录里（跨文件重名会互相干扰）；
- `validation/` 里故意非法的文件在 T1 会报错，这是**预期**；判定标准是"与官方 `validate` 的
  错误集合一致"，因此需要把官方工具的输出也解析成逐文件计数再比对；
- 官方工具与我们的工具都按目录递归加载，两者口径一致，可直接对比。

### 5.2.1 验收记录（滚动更新）

**T1 语料解析验收（2026-09-13）**

| 范围 | 文件 | 结果 |
|---|---:|---|
| `sysml/src` | 251 | **0 个文件有错误**（0 错误 / 11 警告）；load 241.9s、check 8.8s |
| 10 / 50 / 100 文件子集 | — | 0 错误；其中 100 文件档有 5 条警告 |

11 条警告的具体内容与分布**尚未归类**，列为待办（见 `docs/PERF-BASELINE.md` 的"待补"）。

**复跑（2026-09-14，阶段 3 收尾）**：`sysml/src` 251 文件仍是 **0 错误 / 11 警告**，
load 242.3s、check 9.2s——与首次一致，说明阶段 3 的改动没有碰坏解析与语义校验。

**T2 官方视图验收（2026-09-14）**

做法上有个关键点：**必须把整个语料当一个工作区**。官方示例是"片段"——`training/42. Views/Views Example.sysml`
里 `expose vehicle::**` 的 `vehicle` 定义在另一个目录，单独加载那个文件夹会解析不通、暴露为零。
所以 T2 用"一次加载 + 导出全部视图"的模式（`--all-views` / `--all-puml`），而不是一模型一工作区。

| 视图 | 我方节点/边 | 官方节点/边 | 结果 |
|---|---|---|---|
| `11a…::'system structure generation'` | 75 / 45 | 官方拒绝渲染（`asElementTable`） | 不可比 |
| `11b…::vehicleMandatorySafetyFeatureViewStandalone` | 51 / 45 | 官方拒绝渲染（`asElementTable`） | 不可比 |
| `'Views Example'::'vehicle structure view'` | 13 / 12 | 13 / 21 | ✅ 节点一致（边多出的是官方重复画节点） |
| `'Views Example'::'safety features view'` | 13 / 12 | 官方拒绝渲染（`asTextualNotationTable`） | 不可比 |
| `SimpleVehicleModel…::vehiclePartsTree_Safety` | 3 / 0 | 3 / 0 | ✅ 一致 |
| `11b…::vehicleMandatorySafetyFeatureView` | 2 / 0 | 2 / 0 | ✅ 一致 |
| 其余 6 个（含 3 个 `columnView` 渲染用法） | 0 或不可比 | 同上 | 见下 |

**合计：12 个官方视图里 7 个可比，零 missing。** 另外 5 个是**官方渲染器自己拒绝**——
它只支持 TREE 与 INTERCONNECTION 两种 rendering，遇到 `asElementTable` / `asTextualNotation` /
`asTextualNotationTable` / 自定义 rendering 直接报错。这部分没有对照物，按 §6 风险表的约定标注为"无对照"。

**顺带修掉一个比较脚本的假阳性**：官方标签带多重性后缀（`seatBelt[2]`），我们标 `seatBelt`，
第一版比对把它们算成 missing。归一化规则里补上"剥掉尾部 `[...]`"之后归零。

**复跑（2026-09-14，阶段 3 收尾，12 个视图）**：

| 视图 | 我方节点/边 | 官方节点/边 | 结果 |
|---|---|---|---|
| `'Views Example'::'vehicle structure view'` | 13 / 12 | 13 / 21 | ✅ 节点一致 |
| `SimpleVehicleModel…::vehiclePartsTree_Safety` | 3 / 0 | 3 / 0 | ✅ 一致 |
| `11b…::vehicleMandatorySafetyFeatureView` | 2 / 0 | 2 / 0 | ✅ 一致 |
| `11a…::'system structure generation'` | 38 / 45 | 0 / 0（`asElementTable`，官方拒绝渲染） | 无对照 |
| `11b…::vehicleMandatorySafetyFeatureViewStandalone` | 21 / 45 | 0 / 0（同上） | 无对照 |
| `'Views Example'::'safety features view'` 等 | 13 / 12 或 0 | 0 / 0（`asTextualNotationTable` / `columnView`） | 无对照 |

**合计 12 个视图，零 missing。** 三处节点数与首跑不同，原因已定位且是**改进**：阶段 3 把
`ConstraintUsage` 从节点展开里排除（约束按官方行为进 `constraints` 仓格），于是两个表格类视图
不再把约束的表达式子树（`Invariant` / `OperatorExpression` / `LiteralInteger` / 匿名多重性…）
物化成节点——`75 → 38`、`51 → 21` 掉的就是这些。

**T3 扩面验收（2026-09-14）**

| 范围 | 结果 |
|---|---|
| 全量语料扫描 | 251 个源文件中 **248 个有顶层包**，3 个是无包声明的片段（`Camera.sysml`、`ControlNodeTest.sysml`、`DecisionTest.sysml`） |
| 前 30 个模型 | 生成 29 个工作区，**29/29 通过** |
| 前 10 个模型 | 10/10 通过 |

**这一轮暴露的两个坑（都已修，且都是我们自己的问题、不是语料的问题）**：

1. **包装文件里包名带空格必须保留单引号**——`import 'Turbojet Stage Analysis'::*;`。
   第一版把引号剥掉，生成出语法非法的包装文件，报错看起来像语料有错，其实是生成器的错。
   这条正是"验收脚本的价值"：它把错误指向了正确的地方。
2. **每个模型要复制整个目录**，不能只复制单个文件——同目录的语料经常互相 import，
   只挑一个文件出来会立刻断链（实测 10 个里挂 5 个，改成整目录复制后 10/10）。

**全量复跑（2026-09-14，产物级）**

用 `scripts/make-corpus-views.py --out build/corpus-views --run-views` 跑完整语料：
**248 个工作区里 211 个零错误出产物**。剩下 37 个的失败原因**不是我们的工具**，而是
语料本身的"片段化"——这些模型 import 的包在**别的目录**里（例如 `Camera.sysml` 依赖
`'Action Decomposition'`、`Flow Usage Example.sysml` 依赖 `'Port Example'`），
而 T3 按目录建工作区，跨目录的 import 自然断链。

这 37 个**全部**用 `--compare-only` 与官方 `validate` 做了逐文件错误数比对：
**37 / 37 完全一致**（例：`Camera` 15=15、`Interface-Example` 13=13、
`Flow-Usage-Example` 48=48）。

这也正是计划里给 T1 定的判定标准——**"与官方诊断一致"，不要求零错误**。

> 资源代价（用户实测后补记到 `AGENTS.md`）：一轮全量约 55–65 分钟，单进程常驻
> 700MB–1GB，风扇长时间满载。跑批前要先告知，并把驱动进程降到 `BelowNormal`。

全量 248 个模型不进 pre-commit，作为阶段收尾的手动门禁。

**S3-7 覆盖率门禁（2026-09-14）**

两个方向都实测过，不是"看起来能卡住"：

| 工作区 | 矩阵结果 | `--gate` 退出码 | 说明 |
|---|---|---:|---|
| `samples/traceability` | `requirements=2 satisfied=2 verified=2 gaps=0` | 0 | 正样本：每条需求都被满足且被验证，门禁不误报 |
| `samples/requirements` | `requirements=2 satisfied=1 verified=1 gaps=1` | 4 | 反样本：`<'1.2'> chassisMassReq` 只有派生关系，没有满足方/验证方 |

矩阵数字可复算：`gaps` 就是稀疏列表里带 `[缺口]` 的行数；`--gaps-only` 的输出与
`--gate` 的判据同源（都读 `TraceMatrix.Matrix.gaps()`）。

## 5.3 性能校验

**要测的指标**（分阶段计时，避免只有总数看不出瓶颈）：

| 阶段 | 现状参考 | 说明 |
|---|---|---|
| 冷启动 + 标准库加载 | 约 8.0s（实测 `samples/`：load=8.0s） | 与工作区规模基本无关，是固定成本 |
| 工作区解析 + 链接 | 约 **0.9 s/文件**（251 文件档 load 232s 减去固定成本；实测见 `docs/PERF-BASELINE.md`） | 随文件数增长 |
| 语义校验 | 0.2s / 14 文件（实测） | 与元素数相关 |
| 产物构建（单视图） | 待测 | 随"暴露范围"节点数增长 |
| 索引构建（全模型） | **94 ms/文件**（251 文件档净增 23.5s） | 随全模型元素数与视图数增长 |
| 追溯矩阵 | **79 ms/文件**（251 文件档净增 19.9s） | 随需求数与关系数增长 |
| 渲染：SVG / PNG / HTML | 待测 | PNG 还要算上浏览器光栅化 |

**复测结论（2026-09-14）**：新增的索引与矩阵两段都**不是瓶颈**——251 文件档里它们分别
净增 23.5s / 19.9s，而官方解析与链接要 232s。真正的时间花在官方解析器上（约 0.9 s/文件），
所以"大模型慢"这件事要在加载策略上解决（增量、缓存、复用 JVM），而不是优化我们的遍历。

**规模档位**：`10 / 50 / 100 / 251` 个文件——前几档用官方语料切子集，最后一档是
`sysml/src` 全量。（原计划的 `403` 档实测**不是一个可用工作区**：`kerml/src` 里是 58 个
`.kerml`、0 个 `.sysml`，SysML 工作区加载器读不进去；`sysml.library` 的 58 个 `.sysml`
当工作区跑是干净的，但它平时就是 `--libdir`。两个数字都记在 `docs/PERF-BASELINE.md`。）
另外准备**合成放大模型**（把一个包复制 N 份）用来单独压"单视图节点数"这个维度，
因为真实语料里单个视图的节点数都不大。

**阈值策略**：第一次跑出基线，之后以"基线 × 1.5"作为回归线，超出即视为回归并需要解释。
不在没有数据的情况下先编数字。

**记录位置**：新建 `docs/PERF-BASELINE.md`，每次跑完把机器、日期、档位、各阶段耗时、
节点/边数量写进去——和 `docs/ORACLE-DIFF.md` 一样留证据，而不是只报一句"变慢了"。

**首次基线（2026-09-13，见该文档）**：固定成本约 8s；解析与链接约 **1 秒/文件**且是绝对大头；
我们自己的语义校验只要 35 ms/文件（251 文件 8.8s）。251 个官方文件全量零错误、11 条警告。
结论：**瓶颈在官方解析器的链接阶段，不在我们的代码**——优化前先分阶段测量，别猜。

**与门禁的关系**：全量 403 文件的 T1/T4 太慢，不进 pre-commit；进 pre-commit 的是
**T0 + 一小撮固定语料**（例如那 5 个含 view 的官方模型），保证提交时能在十几秒内跑完。

## 6. 风险

| 风险 | 说明 | 应对 |
|---|---|---|
| 索引规模 | 全模型索引在大模型上可能几十 MB | 只存最小信息；按需接口；必要时按包分片 |
| `ref` 跨版本失配 | 重命名后 `ref` 变化；Pilot 的 `elementId` 每次加载随机，不能当身份 | 本阶段只保证**同一版本内**可追溯；跨版本比对作为独立议题（需源码显式 id） |
| 隐式/库元素污染影响分析 | 实测递归展开会多出隐式元素 | 默认按 `authored` + `origin` 过滤，并把过滤条件写进结果 |
| 无官方对照物 | Pilot 的 composite 模式**不画 succession**，也不画 `verify`/`derive` | 这部分靠规范 + 人工样例核对，并在文档里标注"无对照" |
| 局部视图布局 | 星形/放射是新增布局 | 布局与产物分离（`layout.json`），新增布局不影响已有产物 |
| 语料里的非法模型 | `validation/` 有故意非法的文件 | T1 的判定改成"与官方诊断一致"，不要求零错误 |
| 验收耗时 | 全量语料一轮几十分钟量级 | 分成"提交门禁（十几秒）"与"阶段验收（手动）"两级 |

## 7. 第一步

顺序上把**验收底座**排在功能前面：没有它，后面每加一项都只是在"看起来对"。

1. **补齐验收底座**
   - T1：把官方语料 `-Check` 跑通一遍，记下逐文件诊断与分阶段耗时（同时得到性能基线第一版）；
   - T3：写语料视图生成器（找顶层包 → 建独立工作区 → 写 view 包装），让 251 个模型全部进视图流水线；
   - 建 `docs/PERF-BASELINE.md`，把第一版数字落盘。
2. **S3-1**（关系可点击 + 历史回退）——不依赖索引，改动小、见效快。
3. **S3-2 收尾**（查询层 `traverse()` + 预览服务按需接口）。索引骨架与 `-Check` 入口已落地：
   在 `samples/` 上验证过"索引两次运行逐字节一致"与"产物里的 ref 全都在索引里"。
4. **S3-3 / S3-4 / S3-5** 依次接上（都挂在索引上）。
5. **S3-6 / S3-7**：语义边补齐 → 追溯矩阵与覆盖率门禁。

### 当前状态（2026-09-13）

| 项 | 状态 |
|---|---|
| 索引骨架（`WorkspaceIndex` / `WorkspaceIndexBuilder` / `--index`） | ✅ 已实现并在 `samples/` 上验证（确定性 + ref 覆盖一致） |
| 语料批量检查入口（`--check` / `--report`） | ✅ 已实现并在 `samples/`（14 文件）上验证：0 错误、load 8.0s、check 0.2s |
| T1 全量语料跑批 | ✅ 已跑（`sysml/src` 251 文件：0 错误 / 11 警告；基线见 `docs/PERF-BASELINE.md`） |
| T4 性能基线首版 | ✅ 已建（14 / 10 / 50 / 100 / 251 五档；瓶颈在官方解析器链接，约 1 秒/文件） |
| T3 语料视图生成器 | ✅ 已实现（`scripts/make-corpus-views.py`）；30 个模型 29/29 通过，248 全量待跑 |
| T2 官方视图验收 | ✅ 已跑（12 个官方视图：7 个可比且零 missing；5 个官方渲染器自身不支持） |
| S3-1 关系可导航 | ✅ 已完成（关系条目可点击换中心 + 面包屑 + 后退；三层往返已自动验证） |
| S3-2 查询层（引擎侧） | ✅ 已完成（`ModelQuery`：邻居 / 影响范围 / 子图 / 所属视图；`--query` 可命令行验证） |
| S3-2 预览服务按需接口 | ✅ 已完成（`/views`、`/neighbors`、`/impact`、`/local`、`/view?focus=`） |
| S3-3 跨视图跳转 | ✅ 已完成（检查器列出所在视图；点击切视图并聚焦同一元素，已截图验证） |
| S3-4 局部关系视图 | ✅ 已完成（`ego` 产物变换：JSON / SVG / HTML 三种出口都可用） |
| S3-5 影响范围 | ✅ 已完成（表格页 + 源码列；默认排除 containment） |
| S3-6 语义边（`verify` / `derive` / `perform` / `succession` / `reqId` / `documentation`） | ✅ 已完成（T0 全绿、差分不新增 missing；`succession` 有官方对照，见 `docs/ORACLE-DIFF.md`） |
| S3-7 追溯矩阵 | ✅ 已完成（`TraceMatrix`：稀疏列表 + 按包分块 + `--gaps-only`；索引补 `reqId` / `ownerMembership` 两个字段） |
| S3-7 覆盖率门禁 | ✅ 已完成（`--gate` 退出码 4；`scripts/check-trace.ps1` 进 pre-commit；正/反样本各一，见 §5.2.1） |

## 8. 剩余工作与执行计划（待批准）

§1–§7 是"做什么、怎么验收"；本节是"接下来按什么顺序动手"。
**先出计划、后执行**：下面每一项都写清交付物、对应的验收层级（§5.1）与性能校验点，
未获批准前不动代码。

### 8.1 起点快照（2026-09-14）

| 类别 | 内容 |
|---|---|
| 已提交并推送 | 验收底座（T1 / T2 / T4 基线 + T3 生成器）、S3-1、S3-2、S3-3/4/5；分支 `feat/model-index`（PR #17，堆叠在 #16 之上） |
| R1 交付（已提交推送） | S3-6 的一部分：`verify` / `perform` 边、需求 `reqId`、`documentation` 仓格，含样例与 `schema` / `scripts/oracle_diff.py` 的配套改动，外加文档同步 |
| R2 交付（本轮） | `derive`（`#derivation connection`）与 `succession`（`first A then B`）两类边；新增 `samples/actions`（ActionFlow + General 两个视图，官方有对照）；顺带修掉视图类型判定的不确定性（`Map.ofEntries` → 有序 `List`） |
| R3 / R4 交付（本轮） | 追溯矩阵（`TraceMatrix` + `--matrix` / `--gaps-only` + `/matrix`）、覆盖率门禁（`--gate` 退出码 4 + `scripts/check-trace.ps1` 进 pre-commit）、新增 `samples/traceability` 正样本 |
| R5 完成 | T3 全量（211 / 248 零错误出产物；37 个跨目录 import 断链，与官方诊断 **37 / 37** 逐文件一致）；T2 复跑（12 视图零 missing）；T1 复跑（251 文件 0 错误 / 11 警告）；T4 复测（含索引 94 ms/文件、矩阵 79 ms/文件两段） |
| R6 进行中 | BACKLOG / ROADMAP / README 已同步；PR #17 的描述补验收证据，合并顺序 #16 → #17 |

### 8.2 剩余工作项

| 编号 | 工作项 | 依赖 | 交付物 | 验收（对应 §5） | 性能校验 |
|---|---|---|---|---|---|
| R1 | 收尾 S3-6 已做部分 | — | 文档同步（`VIEW-PRODUCT.md` 的关系类型表补 `verify` / `perform`、节点补 `reqId`、`documentation` 仓格；`ORACLE-DIFF.md` 补官方标签归一化）+ 提交推送 | T0：7 个样例两次运行逐字节一致、全部过 schema；T2 不新增 missing | 固定成本不变（≈8s），不引入新的全量遍历 |
| R2 | S3-6 剩余：`derive` / `succession` | R1 | 两类新边 + 样例；无官方对照的写清依据 | 有对照的走 T0/T2；`succession` 走人工核对（官方 composite 模式不画） | 同上 |
| R3 | S3-7 追溯矩阵 | R2 | 稀疏列表 + 按包分块 + `--gaps-only` | 矩阵数字可复算；未实现 / 未验证需求可枚举 | 矩阵构建计入 T4 分阶段计时 |
| R4 | S3-7 覆盖率门禁 | R3 | CLI 退出码 + 可选进 pre-commit | 有缺口的模型能被卡住；无缺口模型不误报 | 门禁只跑样例级，控制在十几秒内 |
| R5 | 验收收尾 | R1–R4 | T3 全量 248 跑批记录 + T2 复跑 + T4 复测（含索引与矩阵两个新阶段） | T3 全量结果落盘；T2 仍零 missing | 复测数字写进 `docs/PERF-BASELINE.md` |
| R6 | 阶段收尾 | R5 | 更新 `docs/BACKLOG.md` / `docs/ROADMAP.md`；说明 #16 → #17 的合并顺序 | 文档与代码一致；PR 描述带验收证据 | — |

### 8.3 每步的门禁

1. **R1**：`scripts/build.ps1` 出现 `Build OK`；`scripts/diff-oracle.ps1` 与确定性复跑通过；
   文档与代码在同一提交里更新（§4 的文档约定）。
2. **R2**：新边先补样例，再跑 T0；有官方对照的更新 `docs/ORACLE-DIFF.md`，
   无对照的在文档里标注依据与人工核对方式。
3. **R3 / R4**：矩阵与门禁各配一个"有缺口"的样例，先证明能卡住，再证明不误报。
4. **R5**：T3 全量一轮约 40 分钟，作为阶段收尾的手动门禁，不进 pre-commit。

**性能回归线**：以 `docs/PERF-BASELINE.md` 的首次基线 × 1.5 为界，超出即视为回归并要求解释。

### 8.4 阶段 3 完成定义（DoD）

- §1 的六个问题**全部**能从工具里答出来，且每条答案都能落回源码位置；
- T0 / T1 / T2 / T3 / T4 都有**最新一次**的记录（不是"上次跑过"）；
- `docs/BACKLOG.md` 里属于阶段 3 的待办清空，或明确写成"延后到阶段 4"并说明原因。
