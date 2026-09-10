# 阶段 1 spike 结论

验证日期：2026-09-10。运行环境与依赖位置见 `docs/ENVIRONMENT.md`。

## 结论

**用 OMG 官方解析器打通"文本 → view/expose 求值 → 图"的链路成立，且比预期省事：**
`view` / `expose` / `filter` 的语义求值官方实现里已经有了，我们不需要自己写。

```text
.sysml 工作区 + sysml.library
        │  SysMLInteractive.readAll + resolveAllInputResources
        ▼
EMF 语义模型（官方解析器 + 链接 + 隐式元素）
        │  ViewUsage.getExposedElement()
        ▼
入图候选元素集合（expose 与 filter 都已求值）
        │  SysMLInteractive.view / viz
        ▼
PlantUML 文本 或 SVG
```

实测结果（`samples/vehicle`）：

| 视图 | expose | filter | exposed 数量 |
|---|---|---:|---:|
| `vehicle structure` | `vehicle::**` | `@SysML::PartUsage` | 3 |
| `vehicle structure (explicit)` | 三个显式 expose | `@SysML::PartUsage` | 3 |
| `vehicle structure (unfiltered)` | `vehicle::**` | 无 | 14 |

前两条一致，说明递归 expose 与显式 expose 在当前样例上等价；第三条的差异全部来自 filter。

## 关键 API（已实测可用）

```java
SysMLInteractive sysml = SysMLInteractive.createInstance();
sysml.setVerbose(false);                       // 关掉逐文件 "Reading ..." 噪音
sysml.loadLibrary("<sysml.library>");           // 读 Kernel / Systems / Domain 三个库目录
sysml.readAll("<workspace>", true);             // 工作区作为 input resources 加载并进索引
sysml.resolveAllInputResources();               // 强制解析代理引用
List<Resource> inputs = sysml.getInputResources();
sysml.setGraphVizPath("<dot.exe>");
VizResult r = sysml.view("VehicleViews::'vehicle structure'",
                         new ArrayList<>(),                 // renders
                         new ArrayList<>(List.of("PUMLCODE")), // styles：含 PUMLCODE 返回文本
                         new ArrayList<>());                // help
```

- `styles` 里含 `PUMLCODE` → 返回 PlantUML 文本；否则返回 SVG（内部走 Graphviz）。
- `view()` 会读取 `ViewUsage.getViewRendering()`，把 `asTreeDiagram` 映射为 `TREE`、
  `asInterconnectionDiagram` 映射为 `INTERCONNECTION`。
- `VizResult` 用 `hasException()` / `formatException()` 判断失败，`getPlantUML()` / `getSVG()` 取结果。

最小依赖：Pilot 各模块 `target/*.jar` 加上 `org.omg.sysml.interactive/target/lib` 下的依赖，
实测共 154 个 jar。构建脚本见 `scripts/env.ps1`。

## 重要机制发现

### 1. 加载工作区不能用 `next()` + `parse()`

`SysMLInteractive.next(ext)` 会新建一个内存资源，`parse(text)` 只是 `reparse` 它的文本。
这条路径**不进 Xtext 的索引**，于是后加载的文件看不到先加载的文件，跨文件引用全部失败。

实测错误（错误做法）：

```text
[ERROR] VehicleViews.sysml line 2: Couldn't resolve reference to Namespace 'VehicleModel'.
[ERROR] VehicleViews.sysml line 17: Couldn't resolve reference to Membership 'vehicle'.
... exposed: 0
```

正确做法是走 `SysMLUtil.readAll(path, isInput)` / `read(paths...)`，它们用
`ResourceSet.getResource(uri, true)` 真正加载并 `addResourceToIndex`。

### 2. `expose` 与 `filter` 在官方实现里是一体的

`ViewUsage.getExposedElement()` 的实现是
`org.omg.sysml.delegate.setting.ViewUsage_exposedElement_SettingDelegate`：

```java
EList<Expression> viewConditions = UsageUtil.getAllViewConditionsOf(owner);
UsageUtil.getExposeImportsOf(owner)
    .flatMap(imp -> imp.importedMemberships(new BasicEList<>()).stream())
    .map(Membership::getMemberElement)
    .filter(element -> ExpressionUtil.checkConditionsOn(element, viewConditions))
    .forEachOrdered(exposedElements::add);
```

即：先按 import 语义（含 `::**` 递归）求 exposed 候选，再对该候选应用
**视图定义与视图用法的全部条件（filter）**。所以这个方法的返回值已经是"入图候选集合"，
不需要我们再叠加一次 filter。

### 3. 递归 expose 会把隐式元素带进来

不加 filter 时，`expose vehicle::**` 返回 14 个元素，其中 8 个是隐式或库里的东西：

```text
(anonymous Multiplicity) × 5
(anonymous Documentation)  × 2
Base::DataValue::self       × 1
```

这正是标准视图定义（如 `GeneralView`）自带 filter 的原因，也解释了为什么在真实代码模型上
`::**` 会一次产生几百个节点。**视图的可用性主要取决于 filter，而不是 expose。**

### 4. 渲染层不是我们要的形态

官方 `viz` 走 PlantUML，实测存在两个问题：

- 节点重复：`engine`、`transmission` 各出现两次（`E2/E4`、`E3/E5`）
- 输出是黑白固定 skin 的 PlantUML/SVG，不接受外部布局与样式

因此渲染层仍要自己做：官方实现给语义，我们负责投影、布局与交互。

## 尚未解决

1. **filter 求值失败的语义未验证**：条件里出现无法解析的引用时，候选是被排除还是被标记为
   "不确定"，尚未实验。
2. **大模型性能未测**：`readAll` + `resolveAllInputResources` 在数百文件规模下的耗时与内存未知。

## 确定性（已做初步验证）

同一输入连续运行两次，三个视图的 exposed 清单与顺序完全一致。这满足了开发规范里"产物必须
可复现"的要求，但结论只覆盖当前样例与本机环境；跨平台、跨进程、大模型下的稳定性仍需在
正式产出 JSON 之后纳入回归测试。

产出 JSON 后补充实测：**第一次比对失败**，两次产物只差 `elementId` 字段——Pilot 每次加载
都会给元素重新生成随机 UUID。因此 `elementId` 已被移出视图产物，节点排序也改用
`限定名 → 元类 → 源码偏移 → exposed 顺序`；修正后两次产物逐字节一致（SHA-256 相同）。

## 关系边的实测（2026-09-10 补充）

`Type.getOwnedSpecialization()` 返回的是**并集**：子集化、重定义、类型化都包含在内，
不需要再分别遍历 `getOwnedSubsetting()` / `getOwnedRedefinition()` / `getOwnedTyping()`
（否则会重复）。按 Java 实际类型分派即可：

```java
for (Specialization s : type.getOwnedSpecialization()) {
    // Redefinition → FeatureTyping → Subsetting → 其余 Specialization
}
```

实测样例 `samples/structure`（10 节点）产出 13 条边：containment 4、typing 5、
specialization 2、subsetting 1、redefinition 1，与模型文本一一对应。

同时发现一个渲染问题：分层布局只按包含关系排布，语义边不参与，于是 typing/specialization
会横穿整张图。已按关系种类区分线型（虚线/点线/点划线）缓解，但真正的解法是按视图类型换
布局，记在 `docs/BACKLOG.md`。

## 视图类型与连接器（2026-09-10 补充）

**视图类型可以判定**：沿 `ViewDefinition` 的泛化闭包（`getOwnedSpecialization()` →
`getGeneral()`）能找到 `StandardViewDefinitions::{GeneralView, InterconnectionView,
ActionFlowView, StateTransitionView, …}`。`view 'x' : InterconnectionView { … }` 这种
直接引用标准视图定义的写法可用，只需 `private import StandardViewDefinitions::*;`。

**`connect` 在语义模型里的形态**：`connect tank.outlet to engine.inlet;` 产生一个匿名
`ConnectionUsage`，它的两个端是匿名 `ReferenceUsage`（名字就是 `source` / `target`），
`exposed` 里三者都会出现。端到真实端口的解析路径是
`end.getOwnedReferenceSubsetting().getReferencedFeature()`（点路径再走 `getChainingFeature()`）。

**端口与属主**：`expose powerSystem::**` 给出的端口是 `Tank::outlet` / `Engine::inlet`
（定义上的端口），而不是用法上的重定义。因此"端口挂在哪个节点边界上"需要按类型闭包判定，
否则端口会变成游离节点。实测 `tank : Tank` + `Tank::outlet` → 挂到 `tank`。

**filter 的作用**：同一模型加 `filter @SysML::PartUsage or @SysML::PortUsage;` 后，
exposed 从 18 个降到 6 个（隐式多重性、库元素、conjugated port definition 都被挡掉），
视图立刻可读。这再次说明视图可用性主要取决于 filter。

## Pilot 自带的按视图渲染（2026-09-10 补充）

`org.omg.sysml.plantuml` 模块（29 个类）是官方按视图类型渲染的实现，是本项目
**投影规则的权威参照**。`SysML2PlantUMLText.MODE` 定义了 8 种口径：

```text
Default  Tree  State  Interconnection  Action  Sequence  Case  MIXED
```

模式到渲染类的映射（`SysML2PlantUMLText` 内）：

| 模式 | 类 | 对应本项目的 `view.kind` |
|---|---|---|
| `Tree`（Default） | `VTree` | `general` |
| `Interconnection` | `VComposite` | `interconnection` |
| `State` | `VStateMachine` | `stateTransition` |
| `Action` | `VAction` / `VBehavior` | `actionFlow` |
| `Sequence` | `VSequence` | `sequence` |
| `Case` | `VCase` | 尚无对应 |

横向模块：`Visitor`（遍历与分派基类）、`VPath`（特征链与连接解析）、`VCompartment`
（仓格内容规则）、`SysML2PlantUMLStyle`（样式）。`SysMLInteractive.view()` 会把模型里
写的 rendering 名映射到模式：`asTreeDiagram` → `TREE`、`asInterconnectionDiagram` →
`INTERCONNECTION`。

值得借鉴的具体规则：

- **端口方向**：`VComposite.isPortOut()` 把端口画成 `portin` / `portout`，判据是"该端口是否为
  连接器的第一个 owned end feature"。源码注释明确指出这是权宜之计——用关系自身 source 判断
  对 ItemFlowEnd 不可靠且低效，所以暂时按端序判定。
- **composite 模式不画 succession**（`caseSuccession` 返回空）。
- **仓格**的完整规则在 `VCompartment`。

**不能复用代码**：它生成 PlantUML 文本，规则与 PlantUML 语法纠缠（`addPUMLLine`、
`insert(pt, "portout ")`），抽不出中性图表示。所以是借规则不借代码。

**一个对比**：Pilot 的 Tree 模式只画包含关系（实测 `-Puml` 输出仅有 `E1 *-- E2` 形态的
组合边）。我们已实现的五类语义边超出了它自带渲染的范围，这部分没有现成参照，需按规范判定。

## 仓格规则的来源（2026-09-10 补充）

仓格不是"固定几个筐"，官方实现（`VCompartment` + `CompartmentEntry`）的做法是：
**把元素自有的成员逐条列出来，按标题分组**，标题由成员关系的类型推导——
`FeatureValue` → `values`，有方向的 → `parameters`，`Subject/Actor/Stakeholder/ObjectiveMembership`
各有专名，`BindingConnector`/`FlowUsage`/`SuccessionFlowUsage` 分别叫
`bindings`/`flows`/`succession flows`，其余按元类名去后缀、拆驼峰、复数化
（`AttributeUsage` → `attributes`）。

排序链（`CompartmentEntry.compareTo`）：成员关系元类名 → 参数优先且按方向 ordinal →
特征元类（`AttributeUsage` 优先）→ 名字。

两个实现要点：

1. **值不在 `ownedFeature` 里**：`attribute mass : Real = 1500;` 的 `FeatureValue`
   挂在**特征自己**的 `ownedRelationship` 上，顺着元素的 `ownedFeature` 找是找不到的；
2. **别重复画**：已经作为节点（部件、端口）或已变成边（连接器）的成员不能再进仓格，
   否则同一个元素会以两种形态同时出现。

实测样例 `samples/parameters`：`Focus`/`Shoot` 出 `parameters`（`in scene: Scene`、
`out image: Image`，顺序与方向均正确），`Settings` 同时出 `attributes` 与 `values`。

## 源码位置的坑（2026-09-10 补充）

Xtext 的 `INode` 有两个口径，用错会把上一行的注释算进元素范围：

- `getTotalOffset()` / `getTotalLength()`：包含节点前面的隐藏 token（注释、空白）
- `getOffset()` / `getLength()`：只覆盖元素本身

而且**即使换成后者，`node.getText()` 仍会带上隐藏子节点**——复合节点的 `getText()` 返回覆盖
全部子节点（含隐藏）的文本，与 `getOffset()/getLength()` 口径不一致。实测 `Focus` 的
`line=8` 但片段里带着第 7 行的 `// 注释`。

可靠做法是按 offset/length 从根节点原文里截取：

```java
INode root = node.getRootNode();
String document = root.getText();
String text = document.substring(node.getOffset(), node.getOffset() + node.getLength());
```

## 语义诊断（已完成）

`SysMLInteractive.validate()` 只校验它自己的"当前资源"，而正规加载路径（`readAll`）不设当前
资源，所以拿不到语义诊断。解决办法是自己建 Xtext injector 取 `IResourceValidator`：

```java
EPackage.Registry.INSTANCE.put(SysMLPackage.eNS_URI, SysMLPackage.eINSTANCE);
KerMLStandaloneSetup.doSetup();
KerMLxStandaloneSetup.doSetup();
SysMLxStandaloneSetup.doSetup();
Injector injector = new SysMLStandaloneSetup().createInjectorAndDoEMFRegistration();
IResourceValidator validator = injector.getInstance(IResourceValidator.class);

List<Issue> issues = validator.validate(resource, CheckMode.ALL, CancelIndicator.NullImpl);
```

实测（反例见 `tests/fixtures/diagnostics/BadModel.sysml`）能同时拿到两类问题：

| 来源 | 问题 | 说明 |
|---|---|---|
| `Resource.getErrors()` | Couldn't resolve reference to Type 'MissingType' | 解析/链接层 |
| validator | An occurrence, item or part must be typed by occurrence definitions | 纯语义规则，EMF 层拿不到 |
| validator | Duplicate of other owned member name ×2 | 纯语义规则，EMF 层拿不到 |

注意：同一个链接错误会同时出现在 `Resource.getErrors()` 和 validator 的结果里，将来输出正式
诊断时需要按 code + 位置去重。

## 复现方式

```powershell
powershell -ExecutionPolicy Bypass -File scripts\build.ps1
powershell -ExecutionPolicy Bypass -File scripts\run-spike.ps1
powershell -ExecutionPolicy Bypass -File scripts\run-spike.ps1 -Puml "VehicleViews::'vehicle structure'"
powershell -ExecutionPolicy Bypass -File scripts\run-spike.ps1 -Svg "VehicleViews::'vehicle structure'" -Out "build\vehicle-structure.svg"
```
