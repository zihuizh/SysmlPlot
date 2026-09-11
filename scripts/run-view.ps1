# 生成视图产物。不带 -View 时列出可用视图。
param(
    [string]$Workspace = 'samples/vehicle',
    [string]$View,
    [string]$Out,
    [string]$Svg,
    [string]$Html,
    [string]$Layout,
    [string]$EmitLayout
)

$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'env.ps1')

# 允许调用方传绝对路径（例如差分脚本把产物写到 build 下的临时目录）
function Resolve-OutPath([string]$Path) {
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
    '--workspace', (Join-Path $script:RepoRoot $Workspace)
)
if ($View) { $argsList += @('--view', $View) }
if ($Out) { $argsList += @('--out', (Resolve-OutPath $Out)) }
if ($Svg) { $argsList += @('--svg', (Resolve-OutPath $Svg)) }
if ($Html) { $argsList += @('--html', (Resolve-OutPath $Html)) }
if ($Layout) { $argsList += @('--layout', (Resolve-OutPath $Layout)) }
if ($EmitLayout) { $argsList += @('--emit-layout', (Resolve-OutPath $EmitLayout)) }

$result = Invoke-NativeCommand -Exe $script:JavaExe -Arguments $argsList
$result.Output | ForEach-Object { Write-Host $_ }
exit $result.ExitCode
