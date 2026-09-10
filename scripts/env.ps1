# 解析本机依赖位置，供其他脚本 dot-source。
# 所有路径都可用环境变量覆盖，参见 docs/ENVIRONMENT.md。

function Invoke-NativeCommand {
    # 原生命令把信息写到 stderr 时，PowerShell 在 $ErrorActionPreference='Stop' 下会当成异常；
    # 这里统一收敛成"退出码 + 输出"，由调用方判断。
    param(
        [Parameter(Mandatory)][string]$Exe,
        [Parameter(Mandatory)][string[]]$Arguments
    )
    $previous = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    try {
        $output = & $Exe @Arguments 2>&1
    } finally {
        $ErrorActionPreference = $previous
    }
    return [pscustomobject]@{
        ExitCode = $LASTEXITCODE
        Output   = @($output)
    }
}

function Resolve-JavaHome {
    if ($env:SYMLPLOT_JAVA_HOME -and (Test-Path (Join-Path $env:SYMLPLOT_JAVA_HOME 'bin/java.exe'))) {
        return $env:SYMLPLOT_JAVA_HOME
    }
    $candidates = @(
        'C:\Program Files\Android\Android Studio\jbr',
        'C:\01-Programs\03-Tools\JabRef\runtime'
    )
    foreach ($candidate in $candidates) {
        $exe = Join-Path $candidate 'bin/java.exe'
        if (-not (Test-Path $exe)) { continue }
        $probe = Invoke-NativeCommand -Exe $exe -Arguments @('-version')
        $version = $probe.Output | Select-Object -First 1
        if ($version -match 'version "2[1-9]') { return $candidate }
    }
    throw 'No Java 21 found. Set SYMLPLOT_JAVA_HOME to a JDK 21 home directory.'
}

$script:PilotDir = if ($env:SYMLPLOT_PILOT_DIR) {
    $env:SYMLPLOT_PILOT_DIR
} else {
    'D:\03-Work\MBSE\Code2Model-Auto\tools\sysmlv2tool\src\submodules\SysML-v2-Pilot-Implementation'
}

$script:SysMLLibDir = if ($env:SYMLPLOT_LIBDIR) {
    $env:SYMLPLOT_LIBDIR
} else {
    'D:\03-Work\MBSE\Code2Model-Auto\tools\sysmlv2tool-dist\sysml.library'
}

$script:GraphvizPath = if ($env:SYMLPLOT_GRAPHVIZ) {
    $env:SYMLPLOT_GRAPHVIZ
} else {
    'C:\Program Files\Graphviz\bin\dot.exe'
}

$script:JavaHome = Resolve-JavaHome
$script:JavaExe = Join-Path $script:JavaHome 'bin/java.exe'
$script:JavacExe = Join-Path $script:JavaHome 'bin/javac.exe'

function Get-PilotClasspath {
    if (-not (Test-Path $script:PilotDir)) {
        throw "Pilot source tree not found: $script:PilotDir (set SYMLPLOT_PILOT_DIR)"
    }
    $jars = @()
    $jars += Get-ChildItem $script:PilotDir -Recurse -File -Filter '*.jar' -ErrorAction SilentlyContinue |
        Where-Object { $_.FullName -match '\\target\\[^\\]+\.jar$' } |
        Select-Object -ExpandProperty FullName
    $depLib = Join-Path $script:PilotDir 'org.omg.sysml.interactive\target\lib'
    if (Test-Path $depLib) {
        $jars += Get-ChildItem $depLib -File -Filter '*.jar' | Select-Object -ExpandProperty FullName
    }
    return (($jars | Sort-Object -Unique) -join ';')
}

$script:PilotClasspath = Get-PilotClasspath
$script:RepoRoot = Split-Path -Parent $PSScriptRoot
$script:BuildDir = Join-Path $script:RepoRoot 'build'
$script:ClassesDir = Join-Path $script:BuildDir 'classes'
