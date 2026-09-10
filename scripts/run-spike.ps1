# 运行阶段 1 的 spike：加载工作区、报告诊断与 view、可选渲染。
param(
    [string]$Workspace = 'samples/vehicle',
    [string]$Puml,
    [string]$Svg,
    [string]$Out
)

$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'env.ps1')

$classes = $script:ClassesDir
if (-not (Test-Path $classes)) { throw 'build/classes not found, run scripts/build.ps1 first' }

$mainClass = 'io.github.zihuizh.sysmlplot.spike.PilotSpike'
$argsList = @(
    '-cp', "$classes;$script:PilotClasspath",
    $mainClass,
    '--libdir', $script:SysMLLibDir,
    '--workspace', (Join-Path $script:RepoRoot $Workspace),
    '--graphviz', $script:GraphvizPath
)
if ($Puml) { $argsList += @('--puml', $Puml) }
if ($Svg) { $argsList += @('--svg', $Svg) }
if ($Out) { $argsList += @('--out', (Join-Path $script:RepoRoot $Out)) }

$result = Invoke-NativeCommand -Exe $script:JavaExe -Arguments $argsList
$result.Output | ForEach-Object { Write-Host $_ }
exit $result.ExitCode
