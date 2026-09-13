# 生成视图产物。不带 -View 时列出可用视图。
param(
    [string]$Workspace = 'samples/vehicle',
    [string]$View,
    [string]$Out,
    [string]$Svg,
    [string]$Html,
    [string]$Layout,
    [string]$EmitLayout,
    [string]$At,
    [string]$Index,
    [switch]$Check,
    [string]$Report,
    [string]$AllViews,
    [string]$Query,
    [string]$Ref,
    [int]$Depth = 1,
    [string]$LocalView
)

$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'env.ps1')

# 允许调用方传绝对路径（例如差分脚本把产物写到 build 下的临时目录）
function Resolve-OutPath([string]$Path) {
    if ([System.IO.Path]::IsPathRooted($Path)) { return $Path }
    return (Join-Path $script:RepoRoot $Path)
}

# -Workspace 也允许绝对路径（例如指向官方语料目录）
function Resolve-WorkspacePath([string]$Path) {
    if ([System.IO.Path]::IsPathRooted($Path)) { return $Path }
    return (Join-Path $script:RepoRoot $Path)
}

$classes = $script:ClassesDir
if (-not (Test-Path $classes)) { throw 'build/classes not found, run scripts/build.ps1 first' }

$mainClass = 'io.github.zihuizh.sysmlplot.cli.Main'
$argsList = @(
    '-cp', "$classes;$script:PilotClasspath",
    $mainClass,
    '--libdir', $script:SysMLLibDir,
    '--workspace', (Resolve-WorkspacePath $Workspace)
)
if ($View) { $argsList += @('--view', $View) }
if ($Out) { $argsList += @('--out', (Resolve-OutPath $Out)) }
if ($Svg) { $argsList += @('--svg', (Resolve-OutPath $Svg)) }
if ($Html) { $argsList += @('--html', (Resolve-OutPath $Html)) }
if ($Layout) { $argsList += @('--layout', (Resolve-OutPath $Layout)) }
if ($EmitLayout) { $argsList += @('--emit-layout', (Resolve-OutPath $EmitLayout)) }
if ($At) { $argsList += @('--at', $At) }
if ($Index) { $argsList += @('--index', (Resolve-OutPath $Index)) }
if ($Check) { $argsList += @('--check') }
if ($Report) { $argsList += @('--report', (Resolve-OutPath $Report)) }
if ($AllViews) { $argsList += @('--all-views', (Resolve-OutPath $AllViews)) }
if ($Query) { $argsList += @('--query', $Query, '--ref', $Ref, '--depth', $Depth) }
if ($LocalView) { $argsList += @('--local-view', $LocalView, '--depth', $Depth) }

$result = Invoke-NativeCommand -Exe $script:JavaExe -Arguments $argsList
$result.Output | ForEach-Object { Write-Host $_ }
exit $result.ExitCode
