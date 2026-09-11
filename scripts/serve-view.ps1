# 启动本地预览服务：交互式页面 + 编辑器光标通道。
#
# 页面：      http://127.0.0.1:<port>/
# 光标通道：  POST /cursor?path=<文件>&line=<行>&col=<列>
#             编辑器（VS Code 扩展、脚本、快捷键）调它即可驱动图上高亮。
param(
    [string]$View,
    [string]$Workspace = 'samples/vehicle',
    [int]$Port = 8765
)

$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'env.ps1')

$classes = $script:ClassesDir
if (-not (Test-Path $classes)) { throw 'build/classes not found, run scripts/build.ps1 first' }

$argsList = @(
    '-cp', "$classes;$script:PilotClasspath",
    'io.github.zihuizh.sysmlplot.cli.Main',
    '--libdir', $script:SysMLLibDir,
    '--workspace', (Join-Path $script:RepoRoot $Workspace),
    '--serve', $Port
)
if ($View) { $argsList += @('--view', $View) }

Write-Host "服务启动后按 Ctrl+C 结束；先看视图列表就去掉 -View 参数。"
& $script:JavaExe @argsList
