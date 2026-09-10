# 编译 src/main/java 到 build/classes。
# 阶段 1 直接用 javac + 显式 classpath，不引入构建工具。
param(
    [switch]$Clean
)

$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'env.ps1')

if ($Clean -and (Test-Path $script:BuildDir)) {
    Remove-Item $script:BuildDir -Recurse -Force
}

$sources = Get-ChildItem (Join-Path $script:RepoRoot 'src/main/java') -Recurse -File -Filter '*.java' |
    Select-Object -ExpandProperty FullName
if (-not $sources) { throw 'No Java sources found under src/main/java' }

New-Item -ItemType Directory -Force -Path $script:ClassesDir | Out-Null

Write-Host "Java   : $script:JavaHome"
Write-Host "Classes: $script:ClassesDir"
Write-Host "Sources: $($sources.Count)"

$javacArgs = @(
    '-encoding', 'UTF-8',
    '-d', $script:ClassesDir,
    '-cp', $script:PilotClasspath
) + $sources

$result = Invoke-NativeCommand -Exe $script:JavacExe -Arguments $javacArgs
$result.Output | ForEach-Object { Write-Host $_ }
if ($result.ExitCode -ne 0) { throw "javac failed with exit code $($result.ExitCode)" }

Set-Content -Path (Join-Path $script:BuildDir 'classpath.txt') -Encoding UTF8 -Value $script:PilotClasspath
Write-Host 'Build OK'
