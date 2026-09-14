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
        # 保留相对目录结构：语料里存在**同名不同目录**的文件，摊平复制会互相覆盖，
        # 实测 "251 档"因此少了一个文件（报告 files=250），也丢失了目录语义。
        $relative = $file.Substring($Corpus.Length).TrimStart('\', '/')
        $destination = Join-Path $target $relative
        New-Item -ItemType Directory -Force -Path (Split-Path -Parent $destination) | Out-Null
        Copy-Item -LiteralPath $file -Destination $destination
    }

    $report = Join-Path $script:BuildDir "perf-$scale.json"
    $sw = [System.Diagnostics.Stopwatch]::StartNew()
    & powershell -NoProfile -ExecutionPolicy Bypass -File (Join-Path $PSScriptRoot 'run-view.ps1') `
        -Workspace $target -Check -Report $report 2>&1 | Out-Null
    $wall = $sw.Elapsed.TotalSeconds

    $json = Get-Content $report -Raw | ConvertFrom-Json

    # 阶段 3 新增的两段：索引构建与追溯矩阵。两者都包含固定的加载成本，
    # 看净耗时用"wall - loadSeconds"。
    $sw.Restart()
    & powershell -NoProfile -ExecutionPolicy Bypass -File (Join-Path $PSScriptRoot 'run-view.ps1') `
        -Workspace $target -Index (Join-Path $script:BuildDir "perf-$scale.index.json") 2>&1 | Out-Null
    $indexWall = $sw.Elapsed.TotalSeconds

    $sw.Restart()
    & powershell -NoProfile -ExecutionPolicy Bypass -File (Join-Path $PSScriptRoot 'run-view.ps1') `
        -Workspace $target -Matrix 2>&1 | Out-Null
    $matrixWall = $sw.Elapsed.TotalSeconds

    $rows += [pscustomobject]@{
        files        = $json.summary.files
        errors       = $json.summary.errors
        warnings     = $json.summary.warnings
        loadSeconds  = [Math]::Round($json.summary.loadMillis / 1000.0, 1)
        checkSeconds = [Math]::Round($json.summary.checkMillis / 1000.0, 1)
        indexSeconds = [Math]::Round($indexWall, 1)
        matrixSeconds = [Math]::Round($matrixWall, 1)
        wallSeconds  = [Math]::Round($wall, 1)
    }
    Write-Host ("  {0,4} 文件  load={1,6}s  check={2,5}s  index={3,6}s  matrix={4,6}s  wall={5,6}s" -f `
        $scale, $rows[-1].loadSeconds, $rows[-1].checkSeconds, $rows[-1].indexSeconds, $rows[-1].matrixSeconds, $rows[-1].wallSeconds)
}

Write-Host ''
$rows | Format-Table -AutoSize
$out = Join-Path $script:BuildDir 'perf-baseline.json'
# 不带 BOM 的 UTF-8，和仓库里其他产物一致
[System.IO.File]::WriteAllText($out, ($rows | ConvertTo-Json -Depth 4), (New-Object System.Text.UTF8Encoding($false)))
Write-Host "基线写入 $out"
