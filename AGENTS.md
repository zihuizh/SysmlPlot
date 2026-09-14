# AGENTS.md — SysmlPlot

写给在本仓库工作的编码代理（Codex / Claude Code 等）。

## 跑批任务的真实资源代价（实测，2026-09-14）

`scripts/make-corpus-views.py --run-views` 会**为语料里的每一个模型单独拉起一整套进程链**：

```text
python scripts/make-corpus-views.py --run-views
  └─ subprocess: powershell run-view.ps1 -Workspace <slug> -AllViews <products>
       └─ java io.github.zihuizh.sysmlplot.cli.Main --workspace <slug>
```

注意：产物生成这条路**不调用 Graphviz**——`dot.exe` 只出现在 `scripts/run-spike.ps1`
（让官方渲染器出 SVG 时才用），别把它算进 `run-view.ps1` 的资源账里。

关键在于：**每个 `java` 进程都要重新构建并加载 `run-view.ps1` 中那条巨大的类路径**
（Eclipse / Xtext / SysML v2 全套 jar，实测单进程常驻内存峰值 700MB–1GB）。
循环体在 `make-corpus-views.py` 的 `if args.run:` 与 `if args.run_views:` 两段里，
`for slug, package, _ in generated:` 一次 `subprocess.run` 起一个 JVM。

实测数据：

| 指标 | 数值 |
|------|------|
| 语料规模 | 248 个含顶层 package 的模型 |
| 单模型耗时 | 约 16 秒 |
| 全量耗时 | 约 55–65 分钟 |
| 负载 | 4–5 个逻辑核持续 40–57%，总 CPU 峰值约 48% |
| 磁盘产物 | 4000+ 文件 / 约 90MB |

对笔记本的实际影响：**风扇持续满载约一小时**，用户在前台办公时能明显感知到卡顿。

## 工作约定

1. **起长批处理前先告知用户**，说明预计耗时和资源占用，不要直接闷头跑全量 248 个模型。
2. **先用 `--limit N` 小范围验证**（例如 `--limit 10`）确认逻辑无误，再决定是否铺全量。
3. 需要长时间占 CPU 时，**主动把驱动进程优先级降到 BelowNormal**。
   子进程会继承父进程的优先级类，因此只需对驱动进程设置一次：
   ```powershell
   (Get-Process -Id <driver-pid>).PriorityClass = 'BelowNormal'
   ```
   实测可把总 CPU 从 15–48% 压到 8–21%。

## 提交前检查的代价（2026-09-14 起）

`pre-commit` 现在跑四件事：路径/大小检查、官方解析器校验 `samples/`、覆盖率门禁
（`scripts/check-trace.ps1`）、**产物回归**（`scripts/check-products.ps1`，约 2 分钟）。
最后一项会在 `samples/`、`src/`、`schema/` 有改动时触发；产物**有意**变化时先跑
`scripts/check-products.ps1 -Update` 更新 `tests/golden`，再连同代码一起提交。

## 优化方向（待办）

- **复用 JVM**：当前「一模型一 JVM」，相当一部分时间花在重复的类加载上。
  改成一次 JVM 调用处理多个 workspace，或增加常驻模式（单 JVM 循环读工作区），
  可以省掉每次启动的固定开销并显著削峰。这是目前最高杠杆的优化。
- **分段执行**：已有的 `--limit` 支持分段；可考虑分段串行跑，避免长时间独占总线。
- **产物缓存**：`products/<slug>` 已生成且未变更的工作区可以跳过，只重跑新增或改动的模型。
