# 阶段 1 技术方案

## 1. 目标

用 OMG 官方解析器解析 SysML v2 文本，按 `view` / `expose` 机制生成视图。

一句话验收：**给一个 `.sysml` 工作区，能说出每个 view 应该显示哪些元素和关系，并画出
来，且结论可追溯到规范语义与源码位置。**

## 2. 分层

```text
Semantic Source (Pilot 解析 + 标准库)
        │
   ViewCatalog      找出 view def / view，判定标准视图类型
        │
   ExposeResolver   求值 expose（成员 / 命名空间 / 递归），得到候选元素
        │
   FilterEvaluator  应用 view def 与 view usage 的 filter
        │
   ViewProjector    按视图类型投影为节点与边
        │
   ViewGraph IR     与渲染无关的中间表示（可序列化 JSON）
        │
   Layout           确定性布局（先 Graphviz dot，后接 ELK）
        │
   Renderer         SVG / 后续交互式画布
```

关键约束（来自可行性评估）：**画布上的节点和边不是模型**。模型只在语义层，视图层
只消费投影结果；渲染器只做布局和样式，不许从标签推断关系。

## 3. 解析层

- 依赖 OMG Pilot Implementation 的 Java 产物（本机已构建，见 `docs/ENVIRONMENT.md`）
- 运行时 JDK：**Java 21**（本机默认 17 不可用）
- 构建方式：阶段 1 先用 `javac` + 显式 classpath（离线、无构建工具依赖），
  待路线稳定后再迁移到 Maven/Gradle
- 需要同时加载：工作区的 `.sysml` 文件 + `sysml.library`
- 输出：解析后的模型 + 诊断信息（错误必须显式上报，不能静默跳过）

## 4. 视图求值

按 SysML v2 语义，视图生成分两步，且顺序不可颠倒：

1. **expose 决定候选集**：`expose X`（成员）、`expose X::*`（命名空间直接成员）、
   `expose X::**`（递归）。expose 本质是一种特殊 import。
2. **filter 在候选集上二次筛选**：合并 view def 与 view usage 各自拥有的条件；
   多个条件之间是合取；求值不了的条件要给出显式的"不确定"结果，而不是当作假。

投影时还要按标准视图类型区分表现（General / Interconnection / …），但阶段 1 只做
General View。

## 5. 产物契约（草案 v0）

一个 view 一次生成一个产物，字段至少包括：

```json
{
  "schemaVersion": 0,
  "modelDigest": "...",
  "selectedView": { "name": "...", "kind": "GeneralView", "source": { "uri": "...", "range": {} } },
  "nodes": [ { "id": "n1", "ref": "...", "kind": "PartUsage", "name": "...", "source": {} } ],
  "edges": [ { "id": "e1", "ref": "...", "kind": "containment", "source": "n1", "target": "n2" } ],
  "completeness": { "isComplete": true, "reasons": [] }
}
```

约定：

- `modelDigest` 标识输入（内容摘要），同输入必须同输出
- 节点/边按确定规则排序后再分配编号，编号不是语义身份
- 任何"算不出来"的情况进 `completeness.reasons`，带类型

## 6. 验证方式

1. **对照官方**：用 `sysmlv2-tool` 的 `views` / `diagram` 输出作为基准，比对
   exposed 元素集合。注意该工具按传入路径加载文件，必须传整个工作区目录，
   否则跨文件引用会全部报错（见 `docs/ENVIRONMENT.md` 第七节）
2. **样例回归**：`samples/` 下的模型 + 期望产物，纳入版本控制
3. **稳定性**：同一输入重复运行，产物逐字节一致
4. **边界样本**：圆形 expose、未解析引用、递归展开大范围等

## 7. 风险与未决问题

| 风险 | 说明 | 应对 |
|---|---|---|
| Pilot API 不熟 | 尚未实测调用方式 | 先写最小 spike：加载模型并列出元素 |
| 依赖重 | Pilot 是 Xtext/EMF 体系，启动慢、内存高 | 接受；若不可接受，后续评估换内核 |
| JDK 来源非正式 | 当前用 Android Studio 的 JBR 21 | 建议后续装独立 JDK 21 |
| 布局工具 | Graphviz 已装；ELK 需要 Node | 先用 Graphviz，保接口不变 |
| 规范细节 | `expose` 与 `filter` 的边界语义（递归、去重、继承条件） | 以 Pilot 行为 + 规范条款双重校准 |

## 8. 第一步（spike）

1. 用 JBR 21 编译并运行一个最小 Java 程序，加载 `samples/vehicle/` 下的模型与标准库
2. 打印：解析诊断、包与元素清单、找到的 view 清单
3. 确认 Pilot API 的入口与所需最小依赖集合
4. 把这套启动方式固化成 `scripts/` 下的构建与运行脚本

**状态：已完成（2026-09-10）**，结论与实测数据见 `docs/PHASE-1-FINDINGS.md`。
主要修正：视图求值不需要我们自己实现——官方 `ViewUsage.getExposedElement()` 已经同时
应用了 expose 与 filter；工作区必须用 `readAll` 加载而不是 `next()/parse()`。
