<#
  ChunkyFly 安装脚本：把 dist 里的 jar 装进游戏实例的 mods 目录

  规则（沿用原工作流的约定）：
    - 任何时刻**只允许一个** chunkyfly jar 处于启用状态（重复 mod id 会让 Fabric 直接崩游戏）
    - 旧版本一律改名成 `<文件名>.disabled` 保留，从不删除
    - 游戏在运行时 jar 会被锁住：加 -WaitForGame 会等 java/javaw 退出后自动完成替换

  用法：
    powershell -File scripts\install.ps1 -Jar versions\26.2\dist\chunkyfly-2.0.1+26.2.jar
    powershell -File scripts\install.ps1 -Jar <jar> -WaitForGame          # 游戏在跑时等它退出再替换
    powershell -File scripts\install.ps1 -Jar <jar> -Mods "D:\...\Petra\mods"   # 指定别的实例

  默认目标实例：26.2 实例（`...\.minecraft\versions\26.2-Fabric\mods`）
  要装到 1.21.11 的 Petra 实例，用 -Mods 显式指定。
#>
param(
    [Parameter(Mandatory = $true)][string]$Jar,
    [string]$Mods = 'D:\1sd_dt\mc\-shot 2.6.8\.minecraft\versions\26.2-Fabric\mods',
    [switch]$WaitForGame,
    [int]$WaitHours = 12
)

$ErrorActionPreference = 'Stop'
$Root = Split-Path -Parent $PSScriptRoot

function Fail($msg) { Write-Host "[FAIL] $msg" -ForegroundColor Red; exit 1 }
function Ok($msg)   { Write-Host "[ OK ] $msg" -ForegroundColor Green }
function Info($msg) { Write-Host "[ .. ] $msg" -ForegroundColor Cyan }

if (-not (Test-Path -LiteralPath $Jar))      { Fail "找不到 jar：$Jar" }
if (-not (Test-Path -LiteralPath $Mods))     { Fail "找不到 mods 目录：$Mods" }
$Jar = (Resolve-Path -LiteralPath $Jar).Path
$newName = Split-Path -Leaf $Jar
$newHash = (Get-FileHash -LiteralPath $Jar -Algorithm SHA256).Hash

# 校验 jar 里的 mod id，避免装错东西
$tmp = Join-Path $env:TEMP ("cf-install-" + [guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Force -Path $tmp | Out-Null
try {
    $jdkPathFile = Join-Path $PSScriptRoot 'jdk-25.path'
    $jdk = if (Test-Path -LiteralPath $jdkPathFile) { (Get-Content -LiteralPath $jdkPathFile -Raw).Trim() } else { $null }
    $jarExe = if ($jdk -and (Test-Path -LiteralPath (Join-Path $jdk 'bin\jar.exe'))) { Join-Path $jdk 'bin\jar.exe' } else { 'jar' }
    & $jarExe --extract --file $Jar -C $tmp 'fabric.mod.json' 2>$null
    $modJson = Join-Path $tmp 'fabric.mod.json'
    if (-not (Test-Path -LiteralPath $modJson)) { Fail "jar 里没有 fabric.mod.json：$Jar" }
    $meta = Get-Content -LiteralPath $modJson -Raw -Encoding UTF8 | ConvertFrom-Json
    if ($meta.id -ne 'chunkyfly') { Fail "这不是 chunkyfly 的 jar（id=$($meta.id)）：$Jar" }
    Ok "待安装：$newName  (id=$($meta.id) version=$($meta.version))"
    Ok "sha256：$newHash"
} finally {
    Remove-Item -LiteralPath $tmp -Recurse -Force -ErrorAction SilentlyContinue
}

function Disable-OldJars {
    # 把除目标之外的所有启用中的 chunkyfly jar 改成 .disabled
    $old = @(Get-ChildItem -LiteralPath $Mods -Filter 'chunkyfly*.jar' -File | Where-Object { $_.Name -ne $newName })
    foreach ($f in $old) {
        Rename-Item -LiteralPath $f.FullName -NewName ($f.Name + '.disabled') -Force -ErrorAction Stop
    }
    return $old
}

$disabled = @()
try {
    $disabled = Disable-OldJars
} catch {
    if (-not $WaitForGame) {
        Fail "旧 jar 被占用（游戏可能正在运行）：$($_.Exception.Message)`n      加 -WaitForGame 可等游戏退出后自动替换。"
    }
    Info "旧 chunkyfly jar 被占用，等游戏退出后自动替换（最长 $WaitHours 小时，每 10 秒重试一次）..."
    # 不靠"java 进程是否还在"判断（机器上可能有别的 java 进程）：定时重试改名，
    # 改名成功就说明游戏已经放开这个 jar 了
    $deadline = (Get-Date).AddHours($WaitHours)
    $done = $false
    while ((Get-Date) -lt $deadline) {
        Start-Sleep -Seconds 10
        try { $disabled = Disable-OldJars; $done = $true; break } catch { }
    }
    if (-not $done) { Fail "等待超时，旧 jar 仍被占用，未安装 $newName" }
}

if ($disabled.Count -gt 0) { Ok "已停用旧版本：$(($disabled | ForEach-Object { $_.Name }) -join ', ')" }

Copy-Item -LiteralPath $Jar -Destination (Join-Path $Mods $newName) -Force
$installed = Join-Path $Mods $newName
$installedHash = (Get-FileHash -LiteralPath $installed -Algorithm SHA256).Hash
if ($installedHash -ne $newHash) { Fail "复制后哈希不一致：$installedHash" }

$active = @(Get-ChildItem -LiteralPath $Mods -Filter 'chunkyfly*.jar' -File)
if ($active.Count -ne 1) { Fail "实例里有 $($active.Count) 个启用中的 chunkyfly jar（必须恰好 1 个）：$(($active | ForEach-Object { $_.Name }) -join ', ')" }

Ok "安装完成：$($active[0].Name)  sha256=$($installedHash.Substring(0,16))..."
Ok "启用中的 chunkyfly jar 数量 = 1"

# 安装日志写到该 jar 所属构建线的 build\ 下（从 jar 路径里识别 versions\<线>\）
$line = 'unknown'
$m = [regex]::Match($Jar, '[\\/]versions[\\/]([^\\/]+)[\\/]')
if ($m.Success) { $line = $m.Groups[1].Value }
$logDir = Join-Path $Root "versions\$line\build"
if ($line -eq 'unknown' -or -not (Test-Path -LiteralPath (Join-Path $Root "versions\$line"))) {
    $logDir = $PSScriptRoot
}
if (-not (Test-Path -LiteralPath $logDir)) { New-Item -ItemType Directory -Force -Path $logDir | Out-Null }
$logFile = Join-Path $logDir 'install-log.txt'
$entry = "$(Get-Date -Format 'yyyy-MM-dd HH:mm:ss') installed $newName sha256=$installedHash into $Mods"
[System.IO.File]::AppendAllLines($logFile, [string[]]@($entry), (New-Object System.Text.UTF8Encoding($false)))
