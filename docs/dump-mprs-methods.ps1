# Dump the real method inventory of MultiPlayerRoomService from compiled classes.
#
# Why: this class has 60+ methods and no unit tests, so a refactor cannot be
# verified by eye. javap reads the compiled output, which is the authoritative
# answer to "which methods does the code actually have".
#
# Usage:
#   powershell -ExecutionPolicy Bypass -File F:\HainanMaJhong2\docs\dump-mprs-methods.ps1 -OutPath F:\HainanMaJhong2\docs\mprs-methods-before.txt
#
# Gotchas learned the hard way (keep them):
#   - This file must stay pure ASCII: PowerShell 5.1 reads .ps1 as GBK.
#   - The parameter must NOT be named $Out: PowerShell variable names are
#     case-insensitive, so a local "$out" silently overwrites the parameter.
#   - [System.IO.File] resolves relative paths against the PROCESS working dir,
#     so always pass an absolute path.
#   - Do NOT delete code blocks by "find start line, then first line equal to
#     '    }'". A one-line enum/class ends on its own line, so that heuristic
#     over-deletes into the NEXT class. Anchor on the following declaration
#     (e.g. the next "public static class") or brace-count instead.
#   - When dropping an import, count its usages AFTER removing the import line;
#     the import line itself matches "\bName\b" and makes an unused import look
#     like it still has 1 usage.

param(
    [string]$ClassesDir = 'F:\HainanMaJhong2\HainanMaJhong\target\classes',
    [string]$Class      = 'hainanMahjong.service.MultiPlayerRoomService',
    # 嵌套类用这个前缀去找（重构后类名会变，所以做成参数）
    [string]$NestedGlob = 'MultiPlayerRoomService$*.class',
    # 额外扫描的编译产物子目录（相对 $ClassesDir），分号分隔。
    # 拆分出去的模块类要一起统计，否则方法清单会"看起来少了"。
    [string]$AlsoDirs   = '',
    [string]$OutPath    = ''
)
$ErrorActionPreference = 'Stop'
$javaHome = 'C:\Users\ASUS\.jdks\ms-17.0.20.1-1'
$javap    = Join-Path $javaHome 'bin\javap.exe'

if (-not (Test-Path $javap)) { throw "javap not found: $javap" }
if (-not (Test-Path $ClassesDir)) { throw "classes dir not found: $ClassesDir (run 'mvn compile' first, but NOT 'mvn clean')" }

# main class plus every nested class
$targets = @($Class)
Get-ChildItem $ClassesDir -Recurse -Filter $NestedGlob -ErrorAction SilentlyContinue |
    ForEach-Object {
        $rel = $_.FullName.Substring($ClassesDir.Length).TrimStart('\','/')
        $targets += ($rel -replace '\\','.' -replace '\.class$','')
    }

# plus every class under the extra directories (split-out modules)
Write-Host "DEBUG AlsoDirs=[$AlsoDirs]"
if ($AlsoDirs) {
    foreach ($dir in ($AlsoDirs -split ';')) {
        $d = $dir.Trim()
        if (-not $d) { continue }
        $full = Join-Path $ClassesDir $d
        if (-not (Test-Path $full)) { continue }
        Get-ChildItem $full -Recurse -Filter '*.class' -ErrorAction SilentlyContinue |
            ForEach-Object {
                $rel = $_.FullName.Substring($ClassesDir.Length).TrimStart('\','/')
                $targets += ($rel -replace '\\','.' -replace '\.class$','')
            }
    }
}

$report = New-Object System.Collections.Generic.List[string]
$report.Add("# MultiPlayerRoomService method inventory (javap ground truth)")
$report.Add("# generated: $(Get-Date -Format 'yyyy-MM-dd HH:mm:ss')")
$report.Add("# source:    $ClassesDir")
$report.Add("")

$declarations = New-Object System.Collections.Generic.List[string]

foreach ($target in ($targets | Sort-Object -Unique)) {
    $report.Add("## $target")
    $javapText = & $javap -p -classpath $ClassesDir $target 2>&1
    $decls = @($javapText | Where-Object { $_ -match '\(.*\)\s*;\s*$' })
    foreach ($d in $decls) {
        $one = ($d.Trim() -replace '\s+', ' ')
        $declarations.Add($one)
        $report.Add("  " + $one)
    }
    $report.Add("")
}

# flat sorted name set, for diffing before/after a refactor
$nameSet = New-Object System.Collections.Generic.List[string]
foreach ($d in $declarations) {
    $beforeParen = ($d -split '\(')[0]
    $name = ($beforeParen.Trim() -split '\s+')[-1]
    if ($name) { $nameSet.Add($name) }
}
$uniqueNames = @($nameSet | Sort-Object -Unique)

$report.Add("## flat name set (sorted, unique)")
foreach ($n in $uniqueNames) { $report.Add("  " + $n) }
$report.Add("")
$report.Add("total: $($uniqueNames.Count) methods/constructors")
$report.Add("classes scanned: $(@($targets | Sort-Object -Unique).Count)")

if ($OutPath) {
    $utf8NoBom = New-Object System.Text.UTF8Encoding($false)
    [System.IO.File]::WriteAllLines($OutPath, [string[]]$report.ToArray(), $utf8NoBom)
    Write-Host "written: $OutPath"
    Write-Host "methods: $($uniqueNames.Count)"
} else {
    foreach ($line in $report) { Write-Host $line }
}
