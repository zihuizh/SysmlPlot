# 对每个样例视图，把我们的产物与官方渲染输出做差集对比。
# 用法：powershell -ExecutionPolicy Bypass -File scripts\diff-oracle.ps1
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'env.ps1')

$cases = @(
    @{ Workspace = 'samples/vehicle';        View = "VehicleViews::'vehicle structure'";          Label = 'vehicle / structure' },
    @{ Workspace = 'samples/structure';      View = "StructureViews::'structure (parts)'";       Label = 'structure / parts' },
    @{ Workspace = 'samples/interconnection'; View = "PowerViews::'power interconnection'";       Label = 'interconnection / power' },
    @{ Workspace = 'samples/parameters';     View = "ParamViews::'parameters and values'";       Label = 'parameters / values' }
    @{ Workspace = 'samples/expose-forms';   View = "ExposeViews::membership";                    Label = 'expose / membership' }
    @{ Workspace = 'samples/expose-forms';   View = "ExposeViews::namespace";                     Label = 'expose / namespace' }
    @{ Workspace = 'samples/expose-forms';   View = "ExposeViews::recursive";                     Label = 'expose / recursive' }
    @{ Workspace = 'samples/requirements';   View = "RequirementsViews::'requirement trace'";     Label = 'requirements / satisfy' }
    @{ Workspace = 'samples/flows';          View = "FlowsViews::'power flows'";                  Label = 'flows / allocate' }
)

$scratch = Join-Path $script:BuildDir 'oracle-diff'
New-Item -ItemType Directory -Force -Path $scratch | Out-Null

$failed = 0
foreach ($case in $cases) {
    $slug = ($case.Label -replace '[^A-Za-z0-9]+', '-')
    $productPath = Join-Path $scratch "$slug.product.json"
    $pumlPath = Join-Path $scratch "$slug.puml.txt"

    & powershell -NoProfile -ExecutionPolicy Bypass -File (Join-Path $PSScriptRoot 'run-view.ps1') `
        -Workspace $case.Workspace -View $case.View -Out $productPath | Out-Null
    & powershell -NoProfile -ExecutionPolicy Bypass -File (Join-Path $PSScriptRoot 'run-spike.ps1') `
        -Workspace $case.Workspace -Puml $case.View 2>&1 | Set-Content -Path $pumlPath -Encoding UTF8

    & python (Join-Path $PSScriptRoot 'oracle_diff.py') --product $productPath --puml $pumlPath --label $case.Label
    if ($LASTEXITCODE -ne 0) { $failed++ }
}

if ($failed -gt 0) {
    Write-Host "`n有 $failed 个视图存在 missing（官方画了、我方没有）"
    exit 1
}
Write-Host "`n所有视图的节点集合与官方一致"
exit 0
