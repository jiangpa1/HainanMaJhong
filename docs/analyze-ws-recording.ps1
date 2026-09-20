# ============================================================================
#  analyze-ws-recording.ps1   (ASCII only)
#
#  Purpose: turn the SERVER-SIDE recording (docs/ws-recording.log) into the
#  Stage 1 baseline table. This supersedes analyze-ws-baseline.ps1, which had to
#  fight the DevTools message panel (Ctrl+A only copies the visible rows).
#
#  Recording line format (written by WsRecorder):
#      S->C <TAB> <sessionId> <TAB> <HH:mm:ss.SSS> <TAB> <json>
#      C->S <TAB> <sessionId> <TAB> <HH:mm:ss.SSS> <TAB> <payload>
#
#  Output:
#      docs/baseline-message-types.txt   type -> field variants (S->C only)
#      docs/baseline-fields.txt          every distinct S->C type+fields pair
#      docs/baseline-raw-payloads.txt    first raw payload per type (for eyeballing)
#
#  Usage: powershell -NoProfile -ExecutionPolicy Bypass -File docs\analyze-ws-recording.ps1
# ============================================================================
$ErrorActionPreference = 'Stop'

$docs = $PSScriptRoot
if (-not (Test-Path (Join-Path $docs 'ws-recording.log'))) {
    $docs = Join-Path (Split-Path $PSScriptRoot -Parent) 'docs'
}
$logPath = Join-Path $docs 'ws-recording.log'
if (-not (Test-Path $logPath)) { throw "recording not found: $logPath" }

# --- helpers -----------------------------------------------------------------
# Return the value of the DEPTH-1 "type" field, i.e. the message's own type.
# Needed because nested objects carry their own "type" (melds -> CHI/PENG/...),
# and unlike most messages the `board` payload has its "type" at the FRONT
# ({"discards":..,"melds":{.."type":"PENG"..},"flowers":..,"type":"board"}),
# so "take the last type" is wrong for board. Depth-1 detection is unambiguous.
function Get-MessageType([string]$json) {
    try {
        $o = ConvertFrom-Json -InputObject $json
        if ($null -ne $o.type) { return [string]$o.type }
    } catch { }
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
                if ($j -lt $json.Length -and $json[$j] -eq ':') {
                    if ($key -eq 'type') {
                        $v = $j + 1
                        while ($v -lt $json.Length -and $json[$v] -eq ' ') { $v++ }
                        $m2 = [regex]::Match($json.Substring($v), '^"([A-Za-z_]+)"')
                        if ($m2.Success) { return $m2.Groups[1].Value }
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

function Get-TopLevelKeys([string]$json) {
    try {
        $o = ConvertFrom-Json -InputObject $json
        $names = @($o.PSObject.Properties | ForEach-Object { $_.Name })
        if ($names.Count -gt 0) { return ($names | Sort-Object -Unique) }
    } catch { }
    # fallback: depth-tracking scan
    $keys = New-Object System.Collections.Generic.List[string]
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
                $j = $k + 1
                while ($j -lt $json.Length -and $json[$j] -eq ' ') { $j++ }
                if ($j -lt $json.Length -and $json[$j] -eq ':') {
                    $key = $json.Substring($i + 1, $k - $i - 1)
                    if (-not $keys.Contains($key)) { $keys.Add($key) }
                }
            }
            $i = $k + 1; continue
        }
        if ($ch -eq '{' -or $ch -eq '[') { $depth++ } elseif ($ch -eq '}' -or $ch -eq ']') { $depth-- }
        $i++
    }
    return ($keys | Sort-Object -Unique)
}

# --- parse -------------------------------------------------------------------
$lines = Get-Content -LiteralPath $logPath -Encoding UTF8
$out    = New-Object System.Collections.Generic.List[string]
$in     = New-Object System.Collections.Generic.List[string]
$raw    = New-Object System.Collections.Generic.List[string]
$perType = @{}
$counts  = @{}
$skipped = 0

foreach ($line in $lines) {
    if (-not $line -or $line.Trim().Length -eq 0) { continue }
    # split into at most 4 parts: dir, sid, time, payload(rest with tabs)
    $p = $line -split "`t", 4
    if ($p.Count -lt 4) { $skipped++; continue }
    $dir = $p[0]; $payload = $p[3]
    if (-not $payload.StartsWith('{')) { $skipped++; continue }

    $type = Get-MessageType $payload

    $csv = ((Get-TopLevelKeys $payload) -join ',')
    $row = "{0}`t{1}`t{2}" -f $dir, $type, $csv

    if ($dir -eq 'S->C') {
        $out.Add($row)
        if (-not $perType.ContainsKey($type)) { $perType[$type] = New-Object System.Collections.Generic.List[string]; $counts[$type] = 0 }
        $perType[$type].Add($csv); $counts[$type]++
        if (-not ($raw -match ("^" + [regex]::Escape($type) + "`t"))) { $raw.Add("$type`t$payload") }
    } else {
        $in.Add($row)
    }
}

# --- write -------------------------------------------------------------------
$typeLines = New-Object System.Collections.Generic.List[string]
foreach ($t in ($perType.Keys | Sort-Object)) {
    $variants = @($perType[$t] | Sort-Object -Unique)
    $typeLines.Add(("{0}`tcount={1}`tfields={2}" -f $t, $counts[$t], ($variants -join ' | ')))
}
$typeOut = Join-Path $docs 'baseline-message-types.txt'
$typeLines | Set-Content -LiteralPath $typeOut -Encoding UTF8
($out | Sort-Object -Unique) | Set-Content -LiteralPath (Join-Path $docs 'baseline-fields.txt') -Encoding UTF8
$raw | Set-Content -LiteralPath (Join-Path $docs 'baseline-raw-payloads.txt') -Encoding UTF8

Write-Host ""
Write-Host ("recording : {0}" -f $logPath)
Write-Host ("frames    : S->C {0} / C->S {1} / skipped {2}" -f $out.Count, $in.Count, $skipped)
Write-Host ("types     : {0} (S->C)" -f $perType.Keys.Count) -ForegroundColor Green
Write-Host ""
$typeLines | ForEach-Object { Write-Host $_ }
Write-Host ""
if ($in.Count -gt 0) {
    Write-Host "=== C->S (client commands) ===" -ForegroundColor Cyan
    $in | Sort-Object -Unique | ForEach-Object { Write-Host $_ }
}
Write-Host ""
Write-Host "written: $typeOut"
