# PNG 导出：先出 SVG，再用无头浏览器把 SVG 光栅化成 PNG。
#
# PNG 光栅化需要浏览器（本机 Chrome 或 Edge）；Java 侧不做光栅化，保持引擎与宿主解耦。
# 浏览器路径可用环境变量 SYMLPLOT_BROWSER 覆盖。
param(
    [Parameter(Mandatory)][string]$View,
    [string]$Workspace = 'samples/vehicle',
    [string]$Out = 'build/view.png',
    [int]$Scale = 1,
    [switch]$Transparent
)

$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'env.ps1')

function Resolve-OutPath([string]$Path) {
    if ([System.IO.Path]::IsPathRooted($Path)) { return $Path }
    return (Join-Path $script:RepoRoot $Path)
}

function Resolve-Browser {
    if ($env:SYMLPLOT_BROWSER -and (Test-Path $env:SYMLPLOT_BROWSER)) { return $env:SYMLPLOT_BROWSER }
    foreach ($candidate in @(
        'C:\Program Files\Google\Chrome\Application\chrome.exe',
        'C:\Program Files (x86)\Google\Chrome\Application\chrome.exe',
        'C:\Program Files (x86)\Microsoft\Edge\Application\msedge.exe',
        'C:\Program Files\Microsoft\Edge\Application\msedge.exe')) {
        if (Test-Path $candidate) { return $candidate }
    }
    throw 'PNG export needs a browser: install Chrome/Edge or set SYMLPLOT_BROWSER to the executable.'
}

$svgPath = [System.IO.Path]::ChangeExtension((Resolve-OutPath $Out), '.svg')
$pngPath = Resolve-OutPath $Out

& powershell -NoProfile -ExecutionPolicy Bypass -File (Join-Path $PSScriptRoot 'run-view.ps1') `
    -Workspace $Workspace -View $View -Svg $svgPath | Out-Null
if (-not (Test-Path $svgPath)) { throw "SVG not produced: $svgPath" }

# 从 SVG 根元素读尺寸，作为浏览器窗口尺寸（否则会截成默认视口大小）
$svgText = Get-Content $svgPath -Raw
$match = [regex]::Match($svgText, '<svg[^>]*\swidth="(\d+)"\s+height="(\d+)"')
if (-not $match.Success) { throw "cannot read size from $svgPath" }
$width = [int]$match.Groups[1].Value * $Scale
$height = [int]$match.Groups[2].Value * $Scale

# 缩放：改写 SVG 的 width/height（保留 viewBox），浏览器就会按比例放大绘制内容。
# 只放大窗口而不改这两个属性的话，图形仍按原尺寸画在左上角。
$renderSvg = $svgPath
if ($Scale -ne 1) {
    $renderSvg = Join-Path $script:BuildDir 'png-render.svg'
    $scaled = $svgText -replace '(<svg[^>]*\swidth=")\d+(" height=")\d+(")',
        ('${1}' + $width + '${2}' + $height + '${3}')
    Set-Content -Path $renderSvg -Value $scaled -Encoding UTF8 -NoNewline
}

$pngDir = Split-Path -Parent $pngPath
if ($pngDir) { New-Item -ItemType Directory -Force -Path $pngDir | Out-Null }

$browser = Resolve-Browser
$fileUri = 'file:///' + ($renderSvg -replace '\\', '/')

# 用独立的 user-data-dir，且每次调用都换一个：
#   1) 本机已有 Chrome 实例时，新进程会把命令转发给旧实例后立刻退出，截图可能不落盘；
#   2) 同一 profile 被两次调用共用会锁冲突（实测连续导出时第二次失败）。
$profileDir = Join-Path $script:BuildDir ('.chrome-profile-' + [System.IO.Path]::GetRandomFileName())
New-Item -ItemType Directory -Force -Path $profileDir | Out-Null

$browserArgs = @(
    '--headless=new', '--disable-gpu', '--hide-scrollbars',
    "--user-data-dir=$profileDir", '--no-first-run', '--no-default-browser-check',
    "--window-size=$width,$height",
    "--screenshot=$pngPath"
)
if ($Transparent) { $browserArgs += '--default-background-color=00000000' }
$browserArgs += $fileUri

try {
    $result = Invoke-NativeCommand -Exe $browser -Arguments $browserArgs
    if (-not (Test-Path $pngPath)) {
        $result.Output | ForEach-Object { Write-Host $_ }
        throw "browser did not produce $pngPath"
    }
} finally {
    Remove-Item -Recurse -Force $profileDir -ErrorAction SilentlyContinue
}

Write-Host ("[png] {0} ({1}x{2}, {3} bytes)" -f $pngPath, $width, $height, (Get-Item $pngPath).Length)
