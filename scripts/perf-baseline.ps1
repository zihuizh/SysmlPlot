# 性能基线：按文件数分档跑官方语料，记录各阶段耗时，产出可入库的基线表。
#
# 用法：
#   powershell -ExecutionPolicy Bypass -File scripts\perf-baseline.ps1
#   powershell -ExecutionPolicy Bypass -File scripts\perf-baseline.ps1 -Scales 10,50,100,251
#
# 说明：每档都会复制一份语料子集到 build/perf-corpus-<n>/，再跑一次 `-Check`。
# JVM 冷启动 + 标准库加载是固定成本（实测约 8 秒），看趋势时要把它减掉再比。
param(
    [string]$Corpus = 'D:\03-Work\MBSE\Code2Model-Auto\tools\sysmlv2tool\src\submodules\SysML-v2-Release\sysml\src',
    # 注意：用 -File 调用时参数是字符串，`-Scales 10,50,100` 会被解析成千分位数字 1050100，
    # 所以这里按字符串收，再自己切分。
    [string]$Scales = '10,50,100'
)

$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'env.ps1')

if (-not (Test-Path $Corpus)) { throw "corpus not found: $Corpus" }

$all = Get-ChildItem $Corpus -Recurse -File -Include '*.sysml','*.kerml' |
    Sort-Object FullName |
    Select-Object -ExpandProperty FullName
Write-Host "语料: $Corpus"
Write-Host "总文件数: $($all.Count)"

$scaleList = $Scales -split ',' | ForEach-Object { [int]$_.Trim() } | Where-Object { $_ -gt 0 }
$rows = @()
foreach ($scale in $scaleList) {
    $target = Join-Path $script:BuildDir "perf-corpus-$scale"
    if (Test-Path $target) { Remove-Item $target -Recurse -Force }
    New-Item -ItemType Directory -Force -Path $target | Out-Null
    foreach ($file in ($all | Select-Object -First $scale)) {
        Copy-Item -LiteralPath $file -Destination $target
    }

    $report = Join-Path $script:BuildDir "perf-$scale.json"
    $sw = [System.Diagnostics.Stopwatch]::StartNew()
    & powershell -NoProfile -ExecutionPolicy Bypass -File (Join-Path $PSScriptRoot 'run-view.ps1') `
        -Workspace $target -Check -Report $report 2>&1 | Out-Null
    $wall = $sw.Elapsed.TotalSeconds

    $json = Get-Content $report -Raw | ConvertFrom-Json
    $rows += [pscustomobject]@{
        files        = $json.summary.files
        errors       = $json.summary.errors
        warnings     = $json.summary.warnings
        loadSeconds  = [Math]::Round($json.summary.loadMillis / 1000.0, 1)
        checkSeconds = [Math]::Round($json.summary.checkMillis / 1000.0, 1)
        wallSeconds  = [Math]::Round($wall, 1)
    }
    Write-Host ("  {0,4} 文件  load={1,6}s  check={2,5}s  wall={3,6}s" -f $scale, $rows[-1].loadSeconds, $rows[-1].checkSeconds, $rows[-1].wallSeconds)
}

Write-Host ''
$rows | Format-Table -AutoSize
$out = Join-Path $script:BuildDir 'perf-baseline.json'
# 不带 BOM 的 UTF-8，和仓库里其他产物一致
[System.IO.File]::WriteAllText($out, ($rows | ConvertTo-Json -Depth 4), (New-Object System.Text.UTF8Encoding($false)))
Write-Host "基线写入 $out"
