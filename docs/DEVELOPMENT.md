# 开发规范

适用范围：本仓库（`SysmlPlot`）的全部代码、文档、样例与脚本。

- 远程仓库：`https://github.com/zihuizh/SysmlPlot.git`（`origin`）
- 主分支：`main`

规范的目的不是形式主义，而是让"模型是对的、产物是可复现的"这两件事有据可查。
凡是能自动检查的，都放进钩子；钩子查不了的，写成清单。

## 1. 分支与提交

### 1.1 分支

- `main` 始终保持可运行，不直接在上面开发
- 工作分支命名：`feat/…`、`fix/…`、`docs/…`、`chore/…`，例如 `feat/expose-recursive`
- 分支尽量短命，合并后删除

### 1.2 提交信息

采用 Conventional Commits 形式，允许中文描述：

```text
<type>(<scope>): <subject>
```

- `type` 取值：`feat`、`fix`、`docs`、`refactor`、`test`、`chore`、`build`、`perf`、`style`、`ci`、`revert`
- `scope` 可省略，建议使用模块名：`parser`、`view`、`layout`、`render`、`samples`、`docs`、`hooks`
- `subject` 用一句话说明做了什么，不加句号，不超过 72 个字符
- 正文（可选）说明**为什么**这么做，以及验证方式

示例：

```text
feat(view): 求值 expose 的递归形式
fix(parser): 加载工作区时补齐标准库路径
docs(env): 记录 Java 21 的位置与原因
```

纯格式调整用 `style`，与行为无关的重构用 `refactor`，不要把两者混在功能提交里。

`Merge`、`Revert` 开头的信息由 Git 生成，钩子放行。

## 2. 目录与命名

```text
SysmlPlot/
  README.md
  SYSML-VIEW-TOOL-FEASIBILITY.md     立项评估（历史文档，不随代码演进而改写结论）
  docs/                              规划、方案、环境事实、规范
  schema/                            产物的机器可读契约（JSON Schema）
  samples/<案例>/{model,views}/        SysML v2 样例，按输入文件角色分目录
  scripts/                           构建、运行、校验脚本
  src/main/java/                     工具源码
  src/test/java/                     测试
  tests/fixtures/                    反例夹具与期望输出（不参与 pre-commit 的 samples 校验）
```

命名约定：

- 文档文件与目录：大写英文 + 连字符，如 `PHASE-1-PLAN.md`；文档内容用中文
- Java 包名：`io.github.zihuizh.sysmlplot.<模块>`
- 类名 `UpperCamel`，方法与变量 `lowerCamel`，常量 `UPPER_SNAKE_CASE`
- 脚本：动词开头的短名，如 `build.ps1`、`run-view.ps1`
- 样例目录：小写短名，如 `samples/vehicle/`

## 3. 代码约定

- 语言级别：**Java 21**（OMG Pilot 的产物是 class file 65，低于 21 无法运行）
- 缩进 4 空格；文件编码 UTF-8；行尾 LF（由 `.gitattributes` 保证）
- 单个方法保持单一职责；类与公开方法必须有说明用途的注释
- **不许吞异常**：捕获后要么处理，要么带着上下文重新抛出，要么转成显式的诊断
- **不许静默丢弃**：算不出来的语义事实要作为"不完整原因"向上传递，不能当作"没有"
- 提交里不要留下无说明的 `TODO`；确需保留的写成 `TODO(issue): …`

## 4. 文档约定

- 每份文档开头写清用途与适用范围
- 文档内容用中文；代码标识符、命令、路径保持原文
- **实测事实与推测必须分开写**：实测标注验证日期，推测明确写"未验证"
- 涉及环境、路径、版本的结论写进 `docs/ENVIRONMENT.md`，不要散落在别处
- 方案变更是新写文档或改方案文档，不要改写历史评估文档的结论

## 5. 依赖与外部资产

- **不把二进制依赖提交进仓库**：不提交 `*.jar`、`*.class`、归档包、构建产物
- OMG Pilot、SysML 标准库等外部资产按绝对路径引用，位置记录在 `docs/ENVIRONMENT.md`
- 机器相关路径通过环境变量或 `local.env.ps1`（已在 `.gitignore` 中）覆盖，不写死进代码
- 引入新的外部依赖必须在 `docs/ENVIRONMENT.md` 记录来源、版本、许可证

## 6. 验证要求

本项目的核心风险是"看起来能画，其实语义不对"，因此验证是规范的一部分：

1. **样例必须能被官方解析器通过**：`samples/` 下的模型改动后必须重新验证
2. **产物必须可复现**：同一输入重复运行，节点、边、顺序、编号完全一致
3. **新能力必须带样例**：新增视图能力时，在 `samples/` 补最小可复现模型
4. **对照官方行为**：视图求值结果要与 OMG Pilot 或对照工具一致，不一致要记录原因
5. 无法自动验证的结论，必须在提交信息或文档里写明人工验证方式

渲染器与界面类改动，除了产物比对，还要用无头浏览器实际打开一次并截图确认（本机可用
`chrome --headless=new --screenshot=…` 或 Edge 的同名参数）。"文件生成了"不等于"渲染对了"。

## 7. 提交前检查

### 7.1 自动检查（钩子）

钩子放在 `.githooks/`，随仓库版本化。启用方式：

```bash
git config core.hooksPath .githooks
```

新克隆仓库后执行一次；Windows 上可直接运行 `scripts/install-hooks.ps1`。

`pre-commit` 检查项：

| 检查 | 说明 |
|---|---|
| 禁止路径 | `build/`、`out/`、`target/`、`dist/`、`node_modules/`、`local.env.ps1` |
| 禁止文件类型 | `jar`、`class`、`exe`、`dll`、`so`、`dylib`、归档包等二进制 |
| 单文件大小 | 默认上限 2 MB，可用 `SYMLPLOT_MAX_FILE_KB` 调整 |
| 样例校验 | staged 中出现 `samples/**/*.sysml` 时，调用官方解析器验证 |

样例校验依赖 Java 21 与本地对照工具；未找到时跳过并提示，不阻断提交。

`commit-msg` 检查提交信息格式（见 1.2）。

### 7.2 人工检查清单

- [ ] 这次改动是否引入了新的"猜语义"逻辑？语义问题应在语义层解决，不在渲染层凑
- [ ] 解析或求值失败时，是否给出了显式原因，而不是显示空白
- [ ] 文档是否同步更新（尤其 `docs/ENVIRONMENT.md` 与方案文档）
- [ ] 是否引入了不可复现的输入（绝对路径、时间戳、随机顺序）

## 8. 例外处理

- 紧急情况下可用 `git commit --no-verify` 绕过钩子，但必须在提交信息里说明原因，
  并在下一次提交前补上检查
- 钩子误报时改钩子，不要靠绕过；钩子本身属于代码，同样需要审阅
