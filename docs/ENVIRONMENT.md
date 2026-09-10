# 本机环境事实（已验证）

验证日期：2026-09-10。以下都是实测结果，不是假设；环境变化后需要重新核对。

## 一、Java：必须 21，默认的是 17

OMG Pilot 的构建产物编译目标是 Java 21（class file version 65）。本机 PATH 上的默认
`java` 是 Java 17，直接运行会报 `UnsupportedClassVersionError`。

| 用途 | 路径 | 版本 | 可用性 |
|---|---|---|---|
| 默认 PATH | `C:\Program Files\Java\jdk-17` | 17.0.12 | ❌ 跑不了 Pilot |
| Android Studio 自带 JBR | `C:\Program Files\Android\Android Studio\jbr` | 21.0.8 (JetBrains Runtime) | ✅ 已实测可运行 |
| JabRef 运行时 | `C:\01-Programs\03-Tools\JabRef\runtime` | 21.0.2 | ✅ 备选 |

当前采用 Android Studio 的 JBR 21 作为开发与验证用 JDK。它是完整 JDK（含 `javac`），
但不随系统 PATH，需要显式指定 `JAVA_HOME` / 绝对路径。长期建议装一个独立的 JDK 21。

## 二、其他工具

| 工具 | 版本 | 备注 |
|---|---|---|
| git | 已安装 | 仓库身份 `ZhangZiHui <1564587746@qq.com>` |
| Node.js | v24.19.0 | 后续可用于 ELK 布局 |
| Python | 3.11.0 | 脚本与校验 |
| Graphviz `dot` | 已安装 | `C:\Program Files\Graphviz\bin\dot.exe`，可作确定性布局与渲染 |
| Maven / Gradle | 未安装 | 构建暂不依赖它们，见下 |
| Rust / cargo | 未安装 | 无需 |

## 三、OMG SysML v2 Pilot Implementation（本地已存在且已构建）

源码位置：

```text
D:\03-Work\MBSE\Code2Model-Auto\tools\sysmlv2tool\src\submodules\SysML-v2-Pilot-Implementation
```

- git HEAD：`a5a602d28`（2026-02-15，Merge PR #737）
- 版本：`0.57.0-SNAPSHOT`
- **已有构建产物**，无需重新构建即可依赖：
  - `org.omg.sysml\target\org.omg.sysml-0.57.0-SNAPSHOT.jar`
  - `org.omg.sysml.interactive\target\org.omg.sysml.interactive-0.57.0-SNAPSHOT.jar`
  - `org.omg.kerml.xtext\target\...jar`、`org.omg.kerml.expressions.xtext\target\...jar`
  - 依赖 jar 集中在各模块的 `target\lib\` 下

标准库（SysML v2 Release）：

```text
D:\03-Work\MBSE\Code2Model-Auto\tools\sysmlv2tool-dist\sysml.library
```

## 四、对照工具（不是我们的产品，只作为行为参照）

```text
D:\03-Work\MBSE\Code2Model-Auto\tools\sysmlv2tool-dist\sysmlv2-tool-fat.jar
```

- 第三方 CLI（SysML v2 Tool 1.2.0），内部直接使用 OMG Pilot Implementation
- 已实测在 JBR 21 下可运行，命令：`validate` / `diagram` / `views` / `structure`
- 用途：作为"官方解析器会给出什么结果"的对照基准（oracle），例如 `views` 能列出
  View / Viewpoint 元素
- **不要**把它整包引入作为运行时依赖；我们要自己做解析与视图求值

## 五、网络

沙箱默认禁止外网，访问 GitHub 等需要逐次审批。因此阶段 1 的依赖策略是**优先复用本地
已有产物**，不依赖联网构建。

## 六、已打通的端到端事实

用对照工具在 JBR 21 下跑 `samples/vehicle`：

- `validate` 通过，模型文件与视图文件均无错误、无警告
- `views` 能识别出 1 个 ViewDefinition（`Part Structure View`）与 2 个 ViewUsage
  （`vehicle structure`、`vehicle structure (explicit)`）

由此确认：本机 Pilot 产物与标准库配套可用，`view def` / `view` / `expose` / `render`
写法能被官方解析器正确解析。

## 七、已知的坑（实测）

1. **标准库的 rendering 需要显式导入**：`render asTreeDiagram` 里的 `asTreeDiagram`
   定义在标准库 `Views` 包中，不 import 会报 "Couldn't resolve reference to Feature"。
   视图文件里需要 `private import Views::*;`。
2. **不要在用法里重复声明继承来的特征**：`part vehicle : Vehicle { part engine : Engine; }`
   当 `Vehicle` 定义里已有 `engine` 时会产生 "Duplicate of inherited member name" 警告。
   要么用 `redefines`，要么让用法自己补充结构（本仓库样例采用后者）。
3. **对照工具按传入路径加载文件**：只传视图文件时，它不会自动加载同工作区的模型文件，
   于是跨文件引用全部报错。要传整个目录。

## 八、尚未验证的事项

- Pilot 的 Java API 调用方式（`SysMLInteractive` 等入口尚未实测）
- 从 Java 直接加载工作区 + 标准库所需的最小 classpath 组合
