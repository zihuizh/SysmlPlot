# 为当前仓库启用版本化的 Git 钩子（.githooks 目录）。
$ErrorActionPreference = 'Stop'

$repoRoot = git rev-parse --show-toplevel
if (-not $repoRoot) { throw 'Not inside a git repository.' }
Set-Location $repoRoot

git config core.hooksPath .githooks
Write-Host "core.hooksPath = $(git config core.hooksPath)"
Write-Host 'Hooks enabled:'
Get-ChildItem (Join-Path $repoRoot '.githooks') -File | ForEach-Object { Write-Host "  $($_.Name)" }

