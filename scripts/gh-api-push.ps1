<#
  Push this folder to GitHub through the REST API.

  Why not `git push`: on this machine the connection to github.com:443 is reset
  (fetch/push both fail), while api.github.com works. So we build the commit through
  the API: create blobs -> tree -> commit -> update the branch ref.

  Note: an EMPTY repository rejects the Git Data API (409 "Git Repository is empty"),
  so the first push seeds one file (README.md) through the Contents API and then
  commits everything else on top of it.

  Usage (from the repository root):
    pwsh -File scripts/gh-api-push.ps1 -Message "what changed"
    pwsh -File scripts/gh-api-push.ps1 -Repo owner/name -Branch main -Message "..." [-DryRun]

  Encoding note: keep this file ASCII-only. Windows PowerShell 5.1 decodes a .ps1
  without a BOM using the local code page, so non-ASCII text here turns into mojibake
  and the script fails to parse. The other scripts in this folder are UTF-8 with BOM.
#>
param(
   [string]$Repo = 'sd-dt/chunkyfly',
   [string]$Branch = 'main',
   [string]$Message = 'Update',
   [string]$Root = (Split-Path -Parent $PSScriptRoot),
   [string]$Gh = 'C:\Program Files\GitHub CLI\gh.exe',
   [switch]$DryRun
)

$ErrorActionPreference = 'Stop'
$utf8NoBom = New-Object System.Text.UTF8Encoding($false)

if (-not (Test-Path -LiteralPath $Gh)) { throw "gh not found: $Gh (pass -Gh <full path>)" }
if (-not (Test-Path -LiteralPath $Root)) { throw "folder not found: $Root" }

# API helper: write the JSON body to a temp file and pass --input, so non-ASCII text
# is never re-encoded through the console code page.
function Invoke-GhApi {
   param([string]$Method, [string]$Path, $Body)
   $ghArgs = @('api', '--method', $Method, $Path)
   if ($null -ne $Body) {
      $tmp = Join-Path $env:TEMP ("gh-body-" + [guid]::NewGuid().ToString('N') + ".json")
      [System.IO.File]::WriteAllText($tmp, ($Body | ConvertTo-Json -Compress -Depth 12), $utf8NoBom)
      $ghArgs += @('--input', $tmp)
   }
   try {
      $out = & $Gh @ghArgs 2>&1
      if ($LASTEXITCODE -ne 0) { throw ("gh api failed ($Method $Path): " + ($out -join "`n")) }
      return $out
   } finally {
      if ($tmp) { Remove-Item -LiteralPath $tmp -Force -ErrorAction SilentlyContinue }
   }
}

# 1. collect files (skip .git)
$files = @(Get-ChildItem -LiteralPath $Root -Recurse -File -Force |
   Where-Object { $_.FullName -notlike '*\.git\*' } |
   ForEach-Object { [pscustomobject]@{ Full = $_.FullName; Rel = $_.FullName.Substring($Root.Length + 1).Replace('\', '/') } })
Write-Host ("files to push: " + $files.Count)

if ($DryRun) { $files | ForEach-Object { Write-Host ("  " + $_.Rel) }; return }

# 2. does the branch have a commit yet? if not, seed one
$head = $null
try { $head = (Invoke-GhApi 'GET' "repos/$Repo/git/ref/heads/$Branch" | ConvertFrom-Json).object.sha } catch { $head = $null }

if (-not $head) {
   $seed = $files | Where-Object { $_.Rel -eq 'README.md' } | Select-Object -First 1
   if (-not $seed) { $seed = $files[0] }
   Write-Host ("empty repository -> seeding with Contents API: " + $seed.Rel)
   [void](Invoke-GhApi 'PUT' ("repos/$Repo/contents/" + $seed.Rel) @{
      message = $Message
      content = [System.Convert]::ToBase64String([System.IO.File]::ReadAllBytes($seed.Full))
      branch  = $Branch
   })
   $head = (Invoke-GhApi 'GET' "repos/$Repo/git/ref/heads/$Branch" | ConvertFrom-Json).object.sha
}

# 3. one blob per file
$entries = @()
foreach ($f in $files) {
   $sha = (Invoke-GhApi 'POST' "repos/$Repo/git/blobs" @{
      content  = [System.Convert]::ToBase64String([System.IO.File]::ReadAllBytes($f.Full))
      encoding = 'base64'
   } | ConvertFrom-Json).sha
   $entries += @{ path = $f.Rel; mode = '100644'; type = 'blob'; sha = $sha }
   Write-Host ("  blob " + $f.Rel)
}

# 4. tree -> commit -> update ref (skip the commit when nothing actually changed)
$baseTree = (Invoke-GhApi 'GET' "repos/$Repo/git/commits/$head" | ConvertFrom-Json).tree.sha
$treeSha = (Invoke-GhApi 'POST' "repos/$Repo/git/trees" @{ base_tree = $baseTree; tree = $entries } | ConvertFrom-Json).sha

Write-Host ""
if ($treeSha -eq $baseTree) {
   Write-Host "no content change (tree identical) -> no commit created"
   Write-Host ("repo: https://github.com/" + $Repo + "  branch: " + $Branch)
   return
}

$commitSha = (Invoke-GhApi 'POST' "repos/$Repo/git/commits" @{ message = $Message; tree = $treeSha; parents = @($head) } | ConvertFrom-Json).sha
[void](Invoke-GhApi 'PATCH' "repos/$Repo/git/refs/heads/$Branch" @{ sha = $commitSha; force = $false })

Write-Host ("committed: " + $commitSha)
Write-Host ("repo: https://github.com/" + $Repo + "  branch: " + $Branch)
