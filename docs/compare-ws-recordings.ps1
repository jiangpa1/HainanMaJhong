# ============================================================================
#  compare-ws-recordings.ps1   (ASCII only)
#
#  Stage 1 acceptance check: prove the package refactor changed ZERO wire fields.
#  Compares two WsRecorder logs (S->C frames only) by:
#     type -> set of top-level field-name lists
#
#  Usage: powershell -NoProfile -ExecutionPolicy Bypass -File docs\compare-ws-recordings.ps1 `
#             -Baseline docs\ws-recording.log -Candidate docs\stage1-recording.log
# ============================================================================
param(
    [string]$Baseline  = (Join-Path $PSScriptRoot 'ws-recording.log'),
    [string]$Candidate = (Join-Path $PSScriptRoot 'stage1-recording.log')
)

$ErrorActionPreference = 'Stop'

function Get-MessageType([string]$json) {
    try { $o = ConvertFrom-Json -InputObject $json; if ($null -ne $o.type) { return [string]$o.type } } catch { }
    $depth = 0; $inStr = $false; $esc = $false; $i = 0
    while ($i -lt $json.Length) {
        $ch = $json[$i]
        if ($inStr) {
            if ($esc) { $esc = $false } elseif ($ch -eq '\') { $esc = $true } elseif ($ch -eq '"') { $inStr = $false }
            $i++; continue
        }
        if ($ch -eq '"') {
            $k = $i + 1; $e = $false
            while ($k -lt $json.Length) {
                $c = $json[$k]
                if ($e) { $e = $false } elseif ($c -eq '\') { $e = $true } elseif ($c -eq '"') { break }
                $k++
            }
            if ($depth -eq 1) {
                $key = $json.Substring($i + 1, $k - $i - 1)
                $j = $k + 1
                while ($j -lt $json.Length -and $json[$j] -eq ' ') { $j++ }
                if ($j -lt $json.Length -and $json[$j] -eq ':' -and $key -eq 'type') {
                    $v = $j + 1
                    while ($v -lt $json.Length -and $json[$v] -eq ' ') { $v++ }
                    if ($v -lt $json.Length -and $json[$v] -eq '"') {
                        $end = $json.IndexOf('"', $v + 1)
                        if ($end -gt $v) { return $json.Substring($v + 1, $end - $v - 1) }
                    }
                }
            }
            $i = $k + 1; continue
        }
        if ($ch -eq '{' -or $ch -eq '[') { $depth++ } elseif ($ch -eq '}' -or $ch -eq ']') { $depth-- }
        $i++
    }
    return '(no-type)'
}

function Get-Fields([string]$json) {
    try { $o = ConvertFrom-Json -InputObject $json; return (@($o.PSObject.Properties | ForEach-Object { $_.Name }) | Sort-Object -Unique) -join ',' } catch { }
    return '(unparsed)'
}

function Scan-Recording([string]$path) {
    $map = @{}
    $count = 0
    foreach ($line in [System.IO.File]::ReadAllLines($path, [System.Text.Encoding]::UTF8)) {
        if (-not $line -or $line.Trim().Length -eq 0) { continue }
        $p = $line -split "`t", 4
        if ($p.Count -lt 4) { continue }
        if ($p[0] -ne 'S->C') { continue }
        $payload = $p[3]
        if (-not $payload.StartsWith('{')) { continue }
        $t = Get-MessageType $payload
        $f = Get-Fields $payload
        if (-not $map.ContainsKey($t)) { $map[$t] = New-Object System.Collections.Generic.List[string] }
        $map[$t].Add($f)
        $count++
    }
    return @{ Map = $map; Frames = $count }
}

Write-Host "baseline : $Baseline"
Write-Host "candidate: $Candidate"
Write-Host ""

$b = Scan-Recording $Baseline
$c = Scan-Recording $Candidate
Write-Host ("frames  : baseline S->C {0} / candidate S->C {1}" -f $b.Frames, $c.Frames)
Write-Host ("types   : baseline {0} / candidate {1}" -f $b.Map.Keys.Count, $c.Map.Keys.Count)
Write-Host ""

$diffs = 0
$missing = @($b.Map.Keys | Where-Object { -not $c.Map.ContainsKey($_) })
$added   = @($c.Map.Keys | Where-Object { -not $b.Map.ContainsKey($_) })
$common  = @($b.Map.Keys | Where-Object { $c.Map.ContainsKey($_) } | Sort-Object)

Write-Host "=== per-type field-set comparison ===" -ForegroundColor Cyan
foreach ($t in $common) {
    $bf = @($b.Map[$t] | Sort-Object -Unique)
    $cf = @($c.Map[$t] | Sort-Object -Unique)
    $onlyB = @($bf | Where-Object { $cf -notcontains $_ })
    $onlyC = @($cf | Where-Object { $bf -notcontains $_ })
    if ($onlyB.Count -eq 0 -and $onlyC.Count -eq 0) {
        Write-Host ("  OK       {0,-12} n={1,-4} fields={2}" -f $t, $c.Map[$t].Count, ($cf -join ' | '))
    } else {
        $diffs++
        Write-Host ("  DIFF     {0}" -f $t) -ForegroundColor Red
        if ($onlyB.Count -gt 0) { Write-Host ("             only in baseline : " + ($onlyB -join ' ; ')) -ForegroundColor Red }
        if ($onlyC.Count -gt 0) { Write-Host ("             only in candidate: " + ($onlyC -join ' ; ')) -ForegroundColor Red }
    }
}

Write-Host ""
if ($missing.Count -gt 0) {
    $diffs++
    Write-Host ("MISSING IN CANDIDATE: " + ($missing -join ', ')) -ForegroundColor Red
} else { Write-Host "no type lost" -ForegroundColor Green }
if ($added.Count -gt 0) { Write-Host ("NEW IN CANDIDATE   : " + ($added -join ', ')) -ForegroundColor Yellow }

Write-Host ""
if ($diffs -eq 0) {
    Write-Host "RESULT: PASS - 0 field differences, 0 types lost" -ForegroundColor Green
} else {
    Write-Host ("RESULT: FAIL - {0} problem(s)" -f $diffs) -ForegroundColor Red
}
