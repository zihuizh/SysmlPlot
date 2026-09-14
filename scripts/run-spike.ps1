# 运行阶段 1 的 spike：加载工作区、报告诊断与 view、可选渲染。
param(
    [string]$Workspace = 'samples/vehicle',
    [string]$Puml,
    [string]$Svg,
    [string]$Out,
    [string]$AllPuml
)

$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'env.ps1')

# 允许绝对路径：语料目录在工作区之外
function Resolve-AnyPath([string]$Path) {
    if ([System.IO.Path]::IsPathRooted($Path)) { return $Path }
    return (Join-Path $script:RepoRoot $Path)
}

$classes = $script:ClassesDir
if (-not (Test-Path $classes)) { throw 'build/classes not found, run scripts/build.ps1 first' }

$mainClass = 'io.github.zihuizh.sysmlplot.spike.PilotSpike'
$argsList = @(
    '-cp', "$classes;$script:PilotClasspath",
    $mainClass,
    '--libdir', $script:SysMLLibDir,
    '--workspace', (Resolve-AnyPath $Workspace),
    '--graphviz', $script:GraphvizPath
)
if ($Puml) { $argsList += @('--puml', $Puml) }
if ($Svg) { $argsList += @('--svg', $Svg) }
if ($Out) { $argsList += @('--out', (Resolve-AnyPath $Out)) }
if ($AllPuml) { $argsList += @('--all-puml', (Resolve-AnyPath $AllPuml)) }

$result = Invoke-NativeCommand -Exe $script:JavaExe -Arguments $argsList
$result.Output | ForEach-Object { Write-Host $_ }
exit $result.ExitCode
