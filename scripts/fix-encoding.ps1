<#
  ChunkyFly 脚本编码修复：把 scripts\*.ps1 统一成 UTF-8「**单个 BOM** + CRLF」

  为什么需要：
  1) Windows PowerShell 5.1 读 .ps1 时，没有 BOM 就按系统 ANSI（简体中文 = GBK）解码，
     脚本里的中文会变乱码、字符串引号被"吃掉"，直接报语法错误：
         Unexpected token '}' in expression or statement.
         The string is missing the terminator: ".
  2) 反过来，**重复的 BOM**（EF BB BF EF BB BF）会让文件开头的 <# 注释块失效，
     整段帮助注释被当成代码 → 报"一元运算符"-"后面缺少表达式"这类怪错误。

  很多编辑器（以及某些自动化改写）会加/去 BOM，所以出现上面任意一种报错时先跑一遍本脚本。

  用法：
    powershell -File scripts/fix-encoding.ps1
#>
$ErrorActionPreference = 'Stop'
$utf8Bom = New-Object System.Text.UTF8Encoding($true)
$fixed = 0

Get-ChildItem -LiteralPath $PSScriptRoot -Filter '*.ps1' -File | ForEach-Object {
    $original = [System.IO.File]::ReadAllBytes($_.FullName)
    $bytes = $original
    $bomCount = 0
    while ($bytes.Length -ge 3 -and $bytes[0] -eq 0xEF -and $bytes[1] -eq 0xBB -and $bytes[2] -eq 0xBF) {
        $bytes = $bytes[3..($bytes.Length - 1)]
        $bomCount++
    }

    $text = [System.Text.Encoding]::UTF8.GetString($bytes)
    $text = $text.Replace("`r`n", "`n").Replace("`r", "`n")
    # 注意：Encoding.GetBytes() 不会带 BOM，必须自己拼 GetPreamble()
    $out = $utf8Bom.GetPreamble() + $utf8Bom.GetBytes($text.Replace("`n", "`r`n"))

    $same = $original.Length -eq $out.Length
    if ($same) {
        for ($i = 0; $i -lt $out.Length; $i++) {
            if ($original[$i] -ne $out[$i]) { $same = $false; break }
        }
    }

    if (-not $same) {
        [System.IO.File]::WriteAllBytes($_.FullName, $out)
        Write-Host ("[ OK ] 已修复：" + $_.Name + "（原有 BOM 数 " + $bomCount + " -> 1，行尾 CRLF）") -ForegroundColor Green
        $fixed++
    } else {
        Write-Host ("[ -- ] 正常：" + $_.Name)
    }
}

Write-Host "完成，修复 $fixed 个文件。" -ForegroundColor Cyan
