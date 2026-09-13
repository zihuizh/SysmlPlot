# 需求覆盖率门禁：对"应当没有缺口"的工作区跑追溯矩阵，有缺口就失败。
#
# 用法：powershell -ExecutionPolicy Bypass -File scripts\check-trace.ps1
#       powershell -ExecutionPolicy Bypass -File scripts\check-trace.ps1 -Targets samples/traceability
#
# 注意：samples/requirements **故意**留着一条没有满足方/验证方的派生需求，它是门禁的反例，
# 所以默认不在检查名单里（要手动跑：run-view.ps1 -Workspace samples/requirements -Matrix -Gate）。
param(
    [string[]]$Targets = @('samples/traceability')
)

$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'env.ps1')

$failed = 0
foreach ($target in $Targets) {
    $workspace = Join-Path $script:RepoRoot $target
    if (-not (Test-Path $workspace)) {
        Write-Host "  [x] 工作区不存在: $target"
        $failed++
        continue
    }
    & powershell -NoProfile -ExecutionPolicy Bypass -File (Join-Path $PSScriptRoot 'run-view.ps1') `
        -Workspace $target -Matrix -GapsOnly -Gate | ForEach-Object { Write-Host $_ }
    $code = $LASTEXITCODE
    if ($code -eq 4) {
        Write-Host "  [x] $target 存在覆盖率缺口（上面列出）"
        $failed++
    } elseif ($code -ne 0) {
        Write-Host "  [x] $target 追溯矩阵计算失败（退出码 $code）"
        $failed++
    } else {
        Write-Host "  [ok] $target 没有覆盖率缺口"
    }
}

if ($failed -gt 0) {
    Write-Host "`n覆盖率门禁未通过：$failed 个工作区有问题"
    exit 1
}
Write-Host "`n覆盖率门禁通过"
exit 0
