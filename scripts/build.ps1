<#
  ChunkyFly 构建脚本（两条构建线通用，javac 直接对标，不用 Gradle / Loom）

  用法：
    powershell -File scripts/build.ps1                              # 默认就是 26.2
    powershell -File scripts/build.ps1 -Version 2.0.1+26.2          # 发新版本
    powershell -File scripts/build.ps1 -NoPackage                   # 只编译不打包
    powershell -File scripts/build.ps1 -Line 1.21.11                # 旧线（仅存档，默认不再编译）

  参数：
    -Line       构建线，**默认 26.2**：26.2（Mojang 官方名 / JDK 25 / class 版本 69）
                或 1.21.11（intermediary 名 / JDK 21 / class 版本 65，仅存档，需显式指定）
    -Version    写进 resources/fabric.mod.json 的版本号；省略则沿用当前值
    -NoPackage  只编译，不生成 jar
    -Jdk        javac 所在 JDK 主目录；省略时按 scripts\jdk-<主版本>.path -> 常见路径 自动探测
    -OutJar     指定 jar 输出路径（默认 versions\<Line>\dist\chunkyfly-<version>.jar）；
                校验重建时可输出到临时目录，避免覆盖 dist 里的发布件

  产物：
    versions\<Line>\dist\chunkyfly-<version>.jar

  编码注意：本文件必须以 UTF-8 **带 BOM** 保存，Windows PowerShell 5.1 才能正确读中文；
            改成无 BOM 会导致解析失败（scripts\fix-encoding.ps1 可一键修复）。
#>
param(
    [ValidateSet('1.21.11', '26.2')][string]$Line = '26.2',
    [string]$Version,
    [switch]$NoPackage,
    [string]$Jdk,
    [string]$OutJar
)

$ErrorActionPreference = 'Stop'
$Root     = Split-Path -Parent $PSScriptRoot
$LineDir  = Join-Path $Root "versions\$Line"
$DepsDir  = Join-Path $Root "deps\mc-$Line"
$BuildDir = Join-Path $LineDir 'build'
$Classes  = Join-Path $BuildDir 'classes'
$Stage    = Join-Path $BuildDir 'stage'
$DistDir  = Join-Path $LineDir 'dist'

function Fail($msg) { Write-Host "[FAIL] $msg" -ForegroundColor Red; exit 1 }
function Step($msg) { Write-Host "[ .. ] $msg" -ForegroundColor Cyan }
function Ok($msg)   { Write-Host "[ OK ] $msg" -ForegroundColor Green }

if (-not (Test-Path -LiteralPath $LineDir)) { Fail "找不到构建线目录：$LineDir" }
if (-not (Test-Path -LiteralPath $DepsDir)) { Fail "找不到依赖目录：$DepsDir" }

# ---------- 0. 构建线参数 ----------
# 1.21.11：intermediary 名，运行时是 Java 21 -> 用 JDK 21 编（class 版本 65），与原产物一致
# 26.2   ：Mojang 官方名，class 版本 69 -> 必须 JDK 25
if ($Line -eq '26.2') { $JdkMajor = 25; $ReleaseArgs = @('--release', '25') }
else                  { $JdkMajor = 21; $ReleaseArgs = @() }   # JDK 21 默认 target 就是 21

# ---------- 1. 定位 JDK ----------
$candidates = @()
if ($Jdk) { $candidates += $Jdk }
$jdkPathFile = Join-Path $PSScriptRoot "jdk-$JdkMajor.path"
if (Test-Path -LiteralPath $jdkPathFile) { $candidates += (Get-Content -LiteralPath $jdkPathFile -Raw).Trim() }
$candidates += @(
    "D:\1sd_dt\mc\-shot 2.6.8\PCL\zulu$JdkMajor.*-ca-jdk$JdkMajor*-win_x64\zulu$JdkMajor.*"
    "D:\1sd_dt\mc\-shot 2.6.8\PCL\zulu$JdkMajor*-win_x64"
    "C:\Program Files\BellSoft\LibericaJDK-$JdkMajor"
    "C:\Program Files\Zulu\zulu-$JdkMajor"
    "C:\Program Files\Eclipse Adoptium\jdk-$JdkMajor*"
    "$env:JAVA_HOME"
)
$JdkHome = $null
foreach ($c in $candidates) {
    if (-not $c) { continue }
    foreach ($p in (Resolve-Path -Path $c -ErrorAction SilentlyContinue)) {
        $javacExe = Join-Path $p.Path 'bin\javac.exe'
        if (Test-Path -LiteralPath $javacExe) {
            $verOut = & $javacExe -version 2>&1 | Out-String
            if ($verOut -match 'javac\s+(\d+)') {
                if ([int]$Matches[1] -eq $JdkMajor) { $JdkHome = $p.Path; break }
            }
        }
    }
    if ($JdkHome) { break }
}
if (-not $JdkHome) {
    Fail "找不到 JDK $JdkMajor。请用 -Jdk <JDK主目录> 指定，或把路径写进 scripts\jdk-$JdkMajor.path"
}

$JavacExe = Join-Path $JdkHome 'bin\javac.exe'
$JarExe   = Join-Path $JdkHome 'bin\jar.exe'
Ok "构建线 $Line ：JDK $JdkMajor @ $JdkHome"

# ---------- 2. 生成 sources.txt（UTF-8 无 BOM） ----------
$SrcDir = Join-Path $LineDir 'src'
$srcFiles = @(Get-ChildItem -LiteralPath $SrcDir -Recurse -Filter '*.java' | Sort-Object FullName | ForEach-Object { $_.FullName.Replace('\', '/') })
if ($srcFiles.Count -eq 0) { Fail "源码目录里没有 .java：$SrcDir" }
$sourcesTxt = Join-Path $BuildDir 'sources.txt'
New-Item -ItemType Directory -Force -Path $BuildDir | Out-Null
[System.IO.File]::WriteAllLines($sourcesTxt, [string[]]$srcFiles, (New-Object System.Text.UTF8Encoding($false)))
Ok "sources.txt：$($srcFiles.Count) 个源文件"

# ---------- 3. 版本号（可选改写 fabric.mod.json） ----------
$modJson = Join-Path $LineDir 'resources\fabric.mod.json'
if ($Version) {
    $json = Get-Content -LiteralPath $modJson -Raw -Encoding UTF8
    $new  = [regex]::Replace($json, '("version"\s*:\s*")[^"]*(")', ('${1}' + $Version + '${2}'), 1)
    if ($new -ne $json) {
        [System.IO.File]::WriteAllText($modJson, $new, (New-Object System.Text.UTF8Encoding($false)))
        Ok "fabric.mod.json version -> $Version"
    }
}
$modVersion = ([regex]::Match((Get-Content -LiteralPath $modJson -Raw -Encoding UTF8), '"version"\s*:\s*"([^"]*)"')).Groups[1].Value
if (-not $modVersion) { Fail "无法从 fabric.mod.json 读出 version" }

# ---------- 4. 组装 classpath，写进 argfile（避开 Windows 命令行长度限制） ----------
$cpJars = @()
$cpJars += Get-ChildItem -LiteralPath $DepsDir -Filter '*.jar' -File | ForEach-Object { $_.FullName }
foreach ($sub in 'mods', 'fapi', 'libs') {
    $d = Join-Path $DepsDir $sub
    if (Test-Path -LiteralPath $d) { $cpJars += Get-ChildItem -LiteralPath $d -Filter '*.jar' -File | ForEach-Object { $_.FullName } }
}
if ($cpJars.Count -eq 0) { Fail "依赖目录里没有 jar：$DepsDir" }
$cp = ($cpJars | ForEach-Object { $_.Replace('\', '/') }) -join ';'

$argsFile = Join-Path $BuildDir 'javac-args.txt'
# 注意 1：javac 的 -J 选项不允许写在 argfile 里，只能放命令行（见下方调用）
# 注意 2：javac 不支持 argfile 嵌套（@file 里再写 @file），所以源文件列表直接写进本文件
# 注意 3：PowerShell 里逗号优先级高于 +，拼接出来的值必须加括号，否则会被拆成多个数组元素
$argLines = @(
    '-nowarn'
    '-proc:none'
    '-encoding', 'UTF-8'
) + $ReleaseArgs + @(
    '-d', $Classes.Replace('\', '/')
    '-cp', ('"' + $cp + '"')
) + $srcFiles
[System.IO.File]::WriteAllLines($argsFile, [string[]]$argLines, (New-Object System.Text.UTF8Encoding($false)))
Ok "classpath：$($cpJars.Count) 个 jar（argfile: build\javac-args.txt，含 $($srcFiles.Count) 个源文件）"

# ---------- 5. 编译 ----------
if (Test-Path -LiteralPath $Classes) { Remove-Item -LiteralPath $Classes -Recurse -Force }
New-Item -ItemType Directory -Force -Path $Classes | Out-Null
Step "javac ..."
$javacLog = Join-Path $BuildDir 'javac.log'
$out = & $JavacExe '-J-Duser.language=en' '-J-Duser.country=US' "@$argsFile" 2>&1
$exit = $LASTEXITCODE
$out | Set-Content -LiteralPath $javacLog -Encoding UTF8
if ($exit -ne 0) {
    $out | Select-Object -First 40 | ForEach-Object { Write-Host $_ }
    Fail "javac exit=$exit（完整日志：$javacLog）"
}
$classFiles = @(Get-ChildItem -LiteralPath $Classes -Recurse -Filter '*.class')
if ($classFiles.Count -eq 0) { Fail "javac exit=0 但没有产出 class" }
Ok "javac exit=0，$($classFiles.Count) 个 class"

# ---------- 6. 打包 ----------
if ($NoPackage) { Ok "（-NoPackage）跳过打包"; exit 0 }

New-Item -ItemType Directory -Force -Path $DistDir | Out-Null
if (Test-Path -LiteralPath $Stage) { Remove-Item -LiteralPath $Stage -Recurse -Force }
New-Item -ItemType Directory -Force -Path $Stage | Out-Null
Copy-Item -Path (Join-Path $LineDir 'resources\*') -Destination $Stage -Recurse -Force
Copy-Item -Path (Join-Path $Classes '*') -Destination $Stage -Recurse -Force

$jarName = "chunkyfly-$modVersion.jar"
if ($OutJar) {
    # 指定输出路径（例如校验重建时输出到临时目录，不覆盖 dist 里的发布件）
    $jarPath = $OutJar
    $outDir = Split-Path -Parent $jarPath
    if ($outDir) { New-Item -ItemType Directory -Force -Path $outDir | Out-Null }
} else {
    New-Item -ItemType Directory -Force -Path $DistDir | Out-Null
    $jarPath = Join-Path $DistDir $jarName
}
if (Test-Path -LiteralPath $jarPath) { Remove-Item -LiteralPath $jarPath -Force }
# 只用 jar.exe --create（.NET ZipFile 写出的 zip 有时 Fabric 读不到条目）
& $JarExe --create --file $jarPath -C $Stage .
if ($LASTEXITCODE -ne 0) { Fail "jar 打包失败 exit=$LASTEXITCODE" }

# ---------- 7. 校验 ----------
$entries = & $JarExe --list --file $jarPath
$need = @('fabric.mod.json', 'chunkyfly.mixins.json', 'chunkyfly.uniform.mixins.json',
          'com/chunkyfly/ChunkyFlyMod.class', 'com/chunkyfly/mixin/MixinInGameHud.class')
$missing = @($need | Where-Object { $entries -notcontains $_ })
if ($missing.Count -gt 0) { Fail "jar 缺少条目：$($missing -join ', ')" }
$jarClasses = @($entries | Where-Object { $_ -like '*.class' }).Count
$size = (Get-Item -LiteralPath $jarPath).Length
$sha  = (Get-FileHash -LiteralPath $jarPath -Algorithm SHA256).Hash

Ok "产物：$jarPath"
Write-Host "       version=$modVersion  class=$jarClasses  size=$size  sha256=$sha" -ForegroundColor Green
