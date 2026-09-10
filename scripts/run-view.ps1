# 生成视图产物。不带 -View 时列出可用视图。
param(
    [string]$Workspace = 'samples/vehicle',
    [string]$View,
    [string]$Out,
    [string]$Svg,
    [string]$Layout,
    [string]$EmitLayout
)

$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'env.ps1')

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
if ($Out) { $argsList += @('--out', (Join-Path $script:RepoRoot $Out)) }
if ($Svg) { $argsList += @('--svg', (Join-Path $script:RepoRoot $Svg)) }
if ($Layout) { $argsList += @('--layout', (Join-Path $script:RepoRoot $Layout)) }
if ($EmitLayout) { $argsList += @('--emit-layout', (Join-Path $script:RepoRoot $EmitLayout)) }

$result = Invoke-NativeCommand -Exe $script:JavaExe -Arguments $argsList
$result.Output | ForEach-Object { Write-Host $_ }
exit $result.ExitCode
