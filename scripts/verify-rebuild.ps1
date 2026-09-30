<#
  ChunkyFly 重建校验：用当前工作区的源码 + 依赖重新构建，并与 dist 里的发布件逐条目比对

  用途：确认迁移后的工作区能**原样复现**已发布的 jar（源码/资源/依赖/JDK/编译参数都对齐）。
  比对的是 jar 内的每个条目（class 与 json），忽略 zip 容器自身的时间戳差异。

  用法：
    powershell -File scripts/verify-rebuild.ps1                     # 默认校验 26.2
    powershell -File scripts/verify-rebuild.ps1 -Line 1.21.11       # 旧线（仅存档）
    powershell -File scripts/verify-rebuild.ps1 -Against versions\26.2\dist\chunkyfly-2.0.0+26.2.jar
#>
param(
    [ValidateSet('1.21.11', '26.2')][string]$Line = '26.2',
    [string]$Against
)

$ErrorActionPreference = 'Stop'
$Root    = Split-Path -Parent $PSScriptRoot
$LineDir = Join-Path $Root "versions\$Line"
$DistDir = Join-Path $LineDir 'dist'

function Fail($msg) { Write-Host "[FAIL] $msg" -ForegroundColor Red; exit 1 }
function Ok($msg)   { Write-Host "[ OK ] $msg" -ForegroundColor Green }
function Info($msg) { Write-Host "[ .. ] $msg" -ForegroundColor Cyan }

if (-not $Against) {
    # 默认取 dist 里版本号最高的 chunkyfly jar（按文件名排序的最后一个非 dis 文件）
    $cand = @(Get-ChildItem -LiteralPath $DistDir -Filter 'chunkyfly-*.jar' -File | Sort-Object Name)
    if ($cand.Count -eq 0) { Fail "dist 里没有 jar：$DistDir" }
    $Against = $cand[-1].FullName
}
$Against = (Resolve-Path -LiteralPath $Against).Path
Info "比对基准：$Against"

$major = if ($Line -eq '26.2') { 25 } else { 21 }
$jdkFile = Join-Path $PSScriptRoot "jdk-$major.path"
$jdk = if (Test-Path -LiteralPath $jdkFile) { (Get-Content -LiteralPath $jdkFile -Raw).Trim() } else { $null }
$jarExe = if ($jdk -and (Test-Path -LiteralPath (Join-Path $jdk 'bin\jar.exe'))) { Join-Path $jdk 'bin\jar.exe' } else { 'jar' }

$work = Join-Path $env:TEMP ("cf-verify-" + [guid]::NewGuid().ToString('N'))
$dirOld = Join-Path $work 'old'
$dirNew = Join-Path $work 'new'
New-Item -ItemType Directory -Force -Path $dirOld, $dirNew | Out-Null

try {
    # 1) 重建到临时目录（不覆盖 dist）
    $rebuilt = Join-Path $work 'rebuilt.jar'
    & (Join-Path $PSScriptRoot 'build.ps1') -Line $Line -OutJar $rebuilt
    if ($LASTEXITCODE -ne 0) { Fail "重建失败" }
    if (-not (Test-Path -LiteralPath $rebuilt)) { Fail "重建没有产出 jar" }

    # 2) 展开两边（jar 的 -C 只对 create/update 有效，解包必须切到目标目录）
    Push-Location $dirOld
    & $jarExe --extract --file $Against 2>&1 | Out-Null
    Pop-Location
    Push-Location $dirNew
    & $jarExe --extract --file $rebuilt 2>&1 | Out-Null
    Pop-Location

    # 3) 逐条目比对
    $filesOld = @(Get-ChildItem -LiteralPath $dirOld -Recurse -File | ForEach-Object { $_.FullName.Substring($dirOld.Length + 1).Replace('\', '/') } | Sort-Object)
    $filesNew = @(Get-ChildItem -LiteralPath $dirNew -Recurse -File | ForEach-Object { $_.FullName.Substring($dirNew.Length + 1).Replace('\', '/') } | Sort-Object)
    if ($filesOld.Count -eq 0 -or $filesNew.Count -eq 0) {
        Fail "解包结果为空（发布件 $($filesOld.Count) 条 / 重建 $($filesNew.Count) 条），无法比对"
    }

    $onlyOld = @($filesOld | Where-Object { $filesNew -notcontains $_ })
    $onlyNew = @($filesNew | Where-Object { $filesOld -notcontains $_ })
    $diff    = @()
    foreach ($f in ($filesOld | Where-Object { $filesNew -contains $_ })) {
        $h1 = (Get-FileHash -LiteralPath (Join-Path $dirOld $f) -Algorithm SHA256).Hash
        $h2 = (Get-FileHash -LiteralPath (Join-Path $dirNew $f) -Algorithm SHA256).Hash
        if ($h1 -ne $h2) { $diff += $f }
    }

    Info "条目数：发布件 $($filesOld.Count) / 重建 $($filesNew.Count)"
    if ($onlyOld.Count) { Write-Host "      仅发布件有：$($onlyOld -join ', ')" -ForegroundColor Yellow }
    if ($onlyNew.Count) { Write-Host "      仅重建有：$($onlyNew -join ', ')" -ForegroundColor Yellow }
    if ($diff.Count)    { Write-Host "      内容不同：$($diff -join ', ')" -ForegroundColor Yellow }

    if ($onlyOld.Count -eq 0 -and $onlyNew.Count -eq 0 -and $diff.Count -eq 0) {
        Ok "重建产物与发布件逐条目逐字节一致（$($filesOld.Count) 个条目）"
        Ok "说明：本条构建线的源码/资源/依赖/JDK 与本工作区完全自洽"
    } else {
        Fail "重建产物与发布件不一致，见上方差异"
    }
} finally {
    Remove-Item -LiteralPath $work -Recurse -Force -ErrorAction SilentlyContinue
}
