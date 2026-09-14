# T0 的产物级回归：确定性比对 + schema 校验 + 与入库"期望产物"逐字节比对。
#
# 用法：
#   powershell -ExecutionPolicy Bypass -File scripts\check-products.ps1             # 默认：golden + schema + 抽样确定性
#   powershell -ExecutionPolicy Bypass -File scripts\check-products.ps1 -Full       # 确定性比对覆盖全部样例
#   powershell -ExecutionPolicy Bypass -File scripts\check-products.ps1 -Update     # 重新生成 tests/golden（产物有意变化时用）
#   powershell -ExecutionPolicy Bypass -File scripts\check-products.ps1 -Samples bindings,vehicle
#
# 期望产物里**不含机器相关路径**：`documents[].uri` 的绝对前缀被换成 `file://<WORKSPACE>/…`，
# 因此换一台机器、换一个克隆目录也能逐字节比对（见 docs/DEVELOPMENT.md 第 7 节）。
param(
    [switch]$Full,
    [switch]$Update,
    [string[]]$Samples
)

$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'env.ps1')

$repo = $script:RepoRoot
$runView = Join-Path $PSScriptRoot 'run-view.ps1'
$schema = Join-Path $repo 'schema\view-product.schema.json'
$goldenRoot = Join-Path $repo 'tests\golden'
$workRoot = Join-Path $script:BuildDir 'check-products'

if (-not (Test-Path $script:ClassesDir)) { throw 'build/classes not found, run scripts/build.ps1 first' }
if (-not (Test-Path $schema)) { throw "schema not found: $schema" }

$allSamples = Get-ChildItem -Directory (Join-Path $repo 'samples') | Select-Object -ExpandProperty Name | Sort-Object
if ($Samples) { $allSamples = $allSamples | Where-Object { $Samples -contains $_ } }
if (-not $allSamples) { throw 'no samples selected' }

# 产物里的绝对路径 → 稳定占位符：workspace 内的换前缀，workspace 外的只留文件名。
function Convert-ToStableProduct([string]$path, [string]$sample) {
    $text = [System.IO.File]::ReadAllText($path, [System.Text.UTF8Encoding]::new($false))
    $workspace = [System.IO.Path]::GetFullPath((Join-Path $repo "samples\$sample")) -replace '\\', '/'
    $workspacePattern = 'file:///' + [regex]::Escape($workspace)
    $text = [regex]::Replace($text, $workspacePattern, 'file://<WORKSPACE>', 'IgnoreCase')
    # 兜底：任何剩下的绝对 file URI（标准库、外部依赖）只保留文件名
    return [regex]::Replace($text, 'file:///[^"]*/', 'file://<EXTERNAL>/')
}

function Invoke-ProductPass([string]$label) {
    $outRoot = Join-Path $workRoot $label
    if (Test-Path $outRoot) { Remove-Item $outRoot -Recurse -Force }
    $normalizedRoot = Join-Path $outRoot '_normalized'
    New-Item -ItemType Directory -Force -Path $normalizedRoot | Out-Null
    foreach ($sample in $allSamples) {
        $out = Join-Path $outRoot $sample
        & powershell -NoProfile -ExecutionPolicy Bypass -File $runView `
            -Workspace "samples/$sample" -AllViews $out | Out-Null
        if (-not (Test-Path $out)) { throw "no products written for $sample" }
        $target = Join-Path $normalizedRoot $sample
        New-Item -ItemType Directory -Force -Path $target | Out-Null
        foreach ($file in Get-ChildItem -File $out -Filter '*.json') {
            $text = Convert-ToStableProduct $file.FullName $sample
            [System.IO.File]::WriteAllText((Join-Path $target $file.Name), $text,
                [System.Text.UTF8Encoding]::new($false))
        }
    }
    return $normalizedRoot
}

$failed = 0
Write-Host "==> 产物回归（$($allSamples.Count) 个样例）"

$normalized = Invoke-ProductPass 'run1'

if ($Update) {
    if (Test-Path $goldenRoot) { Remove-Item $goldenRoot -Recurse -Force }
    New-Item -ItemType Directory -Force -Path $goldenRoot | Out-Null
    foreach ($sample in $allSamples) {
        $source = Join-Path $normalized $sample
        Copy-Item -Recurse -Path $source -Destination (Join-Path $goldenRoot $sample)
    }
    Write-Host "  [ok] 期望产物已更新：$goldenRoot"
} else {
    $compared = 0
    foreach ($sample in $allSamples) {
        $actual = Join-Path $normalized $sample
        $expected = Join-Path $goldenRoot $sample
        if (-not (Test-Path $expected)) {
            Write-Host "  [x] $sample 缺少期望产物：$expected（首次入库请用 -Update）"
            $failed++
            continue
        }
        # 注意要用 @() 强制成数组：只有一个产物时 `+` 会把两个字符串拼起来，
        # 文件名会被拼成一个不存在的名字（踩过一次）。
        $expectedFiles = @(Get-ChildItem -File $expected -Filter '*.json' | Select-Object -ExpandProperty Name)
        $actualFiles = @(Get-ChildItem -File $actual -Filter '*.json' | Select-Object -ExpandProperty Name)
        $names = @($expectedFiles + $actualFiles | Sort-Object -Unique)
        foreach ($name in $names) {
            $expectedPath = Join-Path $expected $name
            $actualPath = Join-Path $actual $name
            if (-not (Test-Path $expectedPath)) {
                Write-Host "  [x] $sample/$name 是新增产物，期望产物里没有（确认后跑 -Update）"
                $failed++
                continue
            }
            if (-not (Test-Path $actualPath)) {
                Write-Host "  [x] $sample/$name 本次没有生成（期望产物里有）"
                $failed++
                continue
            }
            $compared++
            if ([System.IO.File]::ReadAllText($expectedPath) -ne [System.IO.File]::ReadAllText($actualPath)) {
                Write-Host "  [x] $sample/$name 与期望产物不一致"
                $failed++
            }
        }
    }
    if ($failed -eq 0) { Write-Host "  [ok] $compared 份产物与期望产物逐字节一致" }
}

# --- schema 校验（用 python jsonschema，与 CI 口径一致）----------------------
$schemaScript = @'
import glob, json, jsonschema, sys
root = sys.argv[1]
schema = json.load(open(sys.argv[2], encoding="utf-8"))
bad = 0
checked = 0
for path in glob.glob(root + "/**/*.json", recursive=True):
    checked += 1
    try:
        jsonschema.validate(json.load(open(path, encoding="utf-8")), schema)
    except jsonschema.ValidationError as error:
        bad += 1
        print("  [x] %s: %s" % (path, error.message[:160]))
print("  [%s] schema 校验 %d 份，%d 份不合格" % ("ok" if bad == 0 else "x", checked, bad))
sys.exit(1 if bad else 0)
'@
$schemaFile = Join-Path $workRoot 'validate.py'
[System.IO.File]::WriteAllText($schemaFile, $schemaScript, [System.Text.UTF8Encoding]::new($false))
& python $schemaFile $normalized $schema
if ($LASTEXITCODE -ne 0) { $failed++ }

# --- 确定性：同一输入跑两遍，产物必须逐字节一致 ------------------------------
$determinismSamples = if ($Full -or $Samples) { $allSamples } else { $allSamples | Select-Object -First 2 }
$second = Invoke-ProductPass 'run2'
$mismatch = 0
$checkedFiles = 0
foreach ($sample in $determinismSamples) {
    foreach ($file in Get-ChildItem -File (Join-Path $normalized $sample) -Filter '*.json') {
        $checkedFiles++
        $other = Join-Path (Join-Path $second $sample) $file.Name
        if (-not (Test-Path $other) -or
            [System.IO.File]::ReadAllText($file.FullName) -ne [System.IO.File]::ReadAllText($other)) {
            Write-Host "  [x] $sample/$($file.Name) 两次运行不一致"
            $mismatch++
        }
    }
}
if ($mismatch -eq 0) {
    Write-Host "  [ok] 确定性：$checkedFiles 份产物两次运行逐字节一致（覆盖 $($determinismSamples.Count) 个样例）"
} else {
    $failed++
}

if ($failed -ne 0) {
    Write-Host "`n产物回归未通过：$failed 项"
    exit 1
}
Write-Host "`n产物回归通过"
exit 0
