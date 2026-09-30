<#
  One-command GitHub sync: build -> verify -> mirror sources into this repo -> push via API -> attach the jar to a Release.

  Layout this script expects:
    <workspace>\            the working tree (sources, dist, docs)
      versions\26.2\        active line
      versions\1.21.11\     archived line
      scripts\              build.ps1 / install.ps1 / verify-rebuild.ps1 / fix-encoding.ps1
      docs\BUILD-LOG.md
      tools\route-coverage-sim.py
      ghrepo\               <-- this repository copy (the thing that gets pushed)

  Usage:
    powershell -File ghrepo\scripts\gh-sync.ps1 -Message "what changed"
    powershell -File ghrepo\scripts\gh-sync.ps1 -Message "..." -SkipBuild          # push docs/scripts only
    powershell -File ghrepo\scripts\gh-sync.ps1 -Message "..." -NoRelease         # no GitHub Release step

  Why the API instead of `git push`: on this machine github.com:443 is reset (git fetch/push fail),
  while api.github.com works, so commits are built through the REST API (see gh-api-push.ps1).

  Encoding note: keep this file ASCII-only. Windows PowerShell 5.1 decodes a .ps1 without a BOM using
  the local code page, so non-ASCII text here would turn into mojibake and break parsing.
#>
param(
   [Parameter(Mandatory = $true)][string]$Message,
   [string]$Repo = 'sd-dt/chunkyfly-Azusa',
   [string]$Gh = 'C:\Program Files\GitHub CLI\gh.exe',
   [switch]$SkipBuild,
   [switch]$SkipVerify,
   [switch]$NoRelease
)

$ErrorActionPreference = 'Stop'
$RepoDir = Split-Path -Parent $PSScriptRoot          # ...\ghrepo
$Ws = Split-Path -Parent $RepoDir                    # ...\<workspace>
$Md = Join-Path $Ws 'versions\26.2\resources\fabric.mod.json'   # 读工作区的版本号（镜像是在第 4 步才做的）

function Step($text) { Write-Host ""; Write-Host ("=== " + $text) }

# ---------------------------------------------------------------- 1. build
if (-not $SkipBuild) {
   Step 'build 26.2'
   & powershell.exe -NoProfile -ExecutionPolicy Bypass -File (Join-Path $Ws 'scripts\build.ps1') 2>&1 |
      Where-Object { $_ -notmatch 'Cannot create type|OutputEncoding|CategoryInfo|FullyQualifiedErrorId|^\+|^At line' } |
      ForEach-Object { Write-Host ("  " + $_) }
   if ($LASTEXITCODE -ne 0) { throw "build failed (exit $LASTEXITCODE)" }
}

# ---------------------------------------------------------------- 2. verify
if (-not $SkipVerify) {
   Step 'verify rebuild (entry by entry)'
   & powershell.exe -NoProfile -ExecutionPolicy Bypass -File (Join-Path $Ws 'scripts\verify-rebuild.ps1') 2>&1 |
      Where-Object { $_ -notmatch 'Cannot create type|OutputEncoding|CategoryInfo|FullyQualifiedErrorId|^\+|^At line' } |
      ForEach-Object { Write-Host ("  " + $_) }
   if ($LASTEXITCODE -ne 0) { throw "verify-rebuild failed (exit $LASTEXITCODE)" }
}

# ---------------------------------------------------------------- 3. version + jar
$version = (Get-Content -LiteralPath $Md -Raw | Select-String -Pattern '"version"\s*:\s*"([^"]+)"').Matches[0].Groups[1].Value
$jar = Join-Path $Ws ("versions\26.2\dist\chunkyfly-" + $version + ".jar")
$tag = 'v' + ($version -replace '\+.*$', '')
Write-Host ""
Write-Host ("version = " + $version + "   tag = " + $tag)
Write-Host ("jar     = " + $jar + "  exists=" + (Test-Path -LiteralPath $jar))

# ---------------------------------------------------------------- 4. mirror sources into the repo
Step 'mirror workspace -> repo'
$pairs = @(
   @{ From = 'versions\26.2\src'; To = 'versions\26.2\src' },
   @{ From = 'versions\26.2\resources'; To = 'versions\26.2\resources' },
   @{ From = 'versions\1.21.11\src'; To = 'versions\1.21.11\src' },
   @{ From = 'versions\1.21.11\resources'; To = 'versions\1.21.11\resources' },
   @{ From = 'docs\BUILD-LOG.md'; To = 'docs\BUILD-LOG.md' },
   @{ From = 'tools\route-coverage-sim.py'; To = 'docs\route-coverage-sim.py' }
)
foreach ($p in $pairs) {
   $src = Join-Path $Ws $p.From
   $dst = Join-Path $RepoDir $p.To
   if (-not (Test-Path -LiteralPath $src)) { Write-Host ("  skip (missing): " + $p.From); continue }
   if (Test-Path -LiteralPath $src -PathType Container) {
      New-Item -ItemType Directory -Force -Path $dst | Out-Null
      Copy-Item -Path (Join-Path $src '*') -Destination $dst -Recurse -Force
   } else {
      New-Item -ItemType Directory -Force -Path (Split-Path -Parent $dst) | Out-Null
      Copy-Item -LiteralPath $src -Destination $dst -Force
   }
   Write-Host ("  " + $p.From)
}
foreach ($f in @('build.ps1', 'install.ps1', 'verify-rebuild.ps1', 'fix-encoding.ps1')) {
   $src = Join-Path $Ws ("scripts\" + $f)
   if (Test-Path -LiteralPath $src) {
      Copy-Item -LiteralPath $src -Destination (Join-Path $RepoDir 'scripts') -Force
      Write-Host ("  scripts\" + $f)
   }
}

# ---------------------------------------------------------------- 5. push through the API
Step 'push through the GitHub API'
& powershell.exe -NoProfile -ExecutionPolicy Bypass -File (Join-Path $PSScriptRoot 'gh-api-push.ps1') -Repo $Repo -Gh $Gh -Message $Message 2>&1 |
   ForEach-Object { Write-Host ("  " + $_) }
if ($LASTEXITCODE -ne 0) { throw "gh-api-push failed (exit $LASTEXITCODE)" }

# ---------------------------------------------------------------- 6. release
if (-not $NoRelease -and (Test-Path -LiteralPath $jar)) {
   Step ("release " + $tag)
   $notes = Join-Path $RepoDir ("docs\RELEASE-NOTES-" + $tag + ".md")
   # 「release 不存在」时 gh 会往 stderr 写一行，PS 5.1 在 ErrorActionPreference=Stop 下会把它当致命错误 —— 临时放开
   $eap = $ErrorActionPreference
   $ErrorActionPreference = 'Continue'
   & $Gh release view $tag --repo $Repo *> $null
   $releaseExists = ($LASTEXITCODE -eq 0)
   $ErrorActionPreference = $eap

   if ($releaseExists) {
      Write-Host "  release exists -> replacing the asset"
      & $Gh release upload $tag $jar --clobber --repo $Repo
   } else {
      $releaseArgs = @('release', 'create', $tag, '--repo', $Repo, '--title', ("ChunkyFly " + ($version -replace '\+.*$', '') + " (MC 26.2)"))
      if (Test-Path -LiteralPath $notes) { $releaseArgs += @('--notes-file', $notes) } else { $releaseArgs += @('--notes', $Message) }
      $releaseArgs += $jar
      & $Gh @releaseArgs
   }
   if ($LASTEXITCODE -ne 0) { throw "release step failed (exit $LASTEXITCODE)" }
   & $Gh release view $tag --repo $Repo --json url,assets | ForEach-Object { Write-Host ("  " + $_) }
}

Write-Host ""
Write-Host ("done -> https://github.com/" + $Repo)
