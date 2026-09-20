# ============================================================================
#  analyze-ws-baseline.ps1   (ASCII only -- PS 5.1 reads .ps1 as ANSI/GBK.)
#
#  Purpose: turn the DevTools-copied WebSocket frames into the Stage 1 baseline:
#      message type  <TAB>  top-level field names (sorted)
#
#  Input : docs/baseline-room.txt, docs/baseline-game.txt
#          (DevTools copy format: <json> <TAB> <length> <TAB> <time>)
#  Output: docs/baseline-message-types.txt   type -> field variants (with counts)
#          docs/baseline-fields.txt          one line per frame, grouped by type
#
#  Usage : powershell -NoProfile -ExecutionPolicy Bypass -File docs\analyze-ws-baseline.ps1
# ============================================================================
$ErrorActionPreference = 'Stop'

$docs = $PSScriptRoot
if (-not (Test-Path (Join-Path $docs 'baseline-game.txt'))) {
    $docs = Join-Path (Split-Path $PSScriptRoot -Parent) 'docs'
}

$inputs = @(
    @{ Name = 'room'; Path = (Join-Path $docs 'baseline-room.txt') },
    @{ Name = 'game'; Path = (Join-Path $docs 'baseline-game.txt') }
)

# --- parse one JSON object's TOP-LEVEL key names -----------------------------
# Primary: real JSON parse. Fallback: depth-tracking bracket scan.
function Get-TopLevelKeys([string]$json) {
    try {
        $o = ConvertFrom-Json -InputObject $json
        $names = @($o.PSObject.Properties | ForEach-Object { $_.Name })
        if ($names.Count -gt 0) { return ($names | Sort-Object -Unique) }
    } catch {
        # fall through to the manual scan
    }
    $keys = New-Object System.Collections.Generic.List[string]
    $depth = 0; $inStr = $false; $esc = $false; $i = 0
    while ($i -lt $json.Length) {
        $ch = $json[$i]
        if ($inStr) {
            if ($esc) { $esc = $false }
            elseif ($ch -eq '\') { $esc = $true }
            elseif ($ch -eq '"') { $inStr = $false }
            $i++; continue
        }
        if ($ch -eq '"') {
            # find the end of this string
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
        if ($ch -eq '{' -or $ch -eq '[') { $depth++ }
        elseif ($ch -eq '}' -or $ch -eq ']') { $depth-- }
        $i++
    }
    return ($keys | Sort-Object -Unique)
}

# --- read frames -------------------------------------------------------------
$perType      = @{}    # type -> List[string] (field-name csv, one per frame)
$perTypeCount = @{}
$perMessage   = New-Object System.Collections.Generic.List[string]

foreach ($src in $inputs) {
    if (-not (Test-Path $src.Path)) { Write-Host ("SKIP (not found): " + $src.Path) -ForegroundColor Yellow; continue }
    $n = 0
    foreach ($line in (Get-Content -LiteralPath $src.Path)) {
        $t = $line.Trim()
        if (-not $t.StartsWith('{')) { continue }
        $tab  = $t.IndexOf("`t")
        $json = if ($tab -gt 0) { $t.Substring(0, $tab) } else { $t }
        if (-not $json.EndsWith('}')) { continue }

        $type = '(no-type)'
        # Take the LAST "type":"xxx" -- Java puts the message type at the END of every
        # payload, while nested objects may carry their own "type" (e.g. meld -> "type":"CHI").
        $ms = [regex]::Matches($json, '"type"\s*:\s*"([A-Za-z_]+)"')
        if ($ms.Count -gt 0) { $type = $ms[$ms.Count - 1].Groups[1].Value }

        $csv = ((Get-TopLevelKeys $json) -join ',')
        if (-not $perType.ContainsKey($type)) {
            $perType[$type] = New-Object System.Collections.Generic.List[string]
            $perTypeCount[$type] = 0
        }
        $perType[$type].Add($csv)
        $perTypeCount[$type]++
        $perMessage.Add(("{0}`t{1}`t{2}" -f $src.Name, $type, $csv))
        $n++
    }
    Write-Host ("read {0}: {1} frames" -f $src.Name, $n) -ForegroundColor Cyan
}

# --- write ---------------------------------------------------------------
$typeLines = New-Object System.Collections.Generic.List[string]
foreach ($type in ($perType.Keys | Sort-Object)) {
    $variants = @($perType[$type] | Sort-Object -Unique)
    $typeLines.Add(("{0}`tcount={1}`tfields={2}" -f $type, $perTypeCount[$type], ($variants -join ' | ')))
}
$typeOut = Join-Path $docs 'baseline-message-types.txt'
$typeLines | Set-Content -LiteralPath $typeOut -Encoding UTF8

$fieldOut = Join-Path $docs 'baseline-fields.txt'
$perMessage | Sort-Object | Set-Content -LiteralPath $fieldOut -Encoding UTF8

Write-Host ""
Write-Host ("=== baseline-message-types.txt ({0} types) ===" -f $perType.Keys.Count) -ForegroundColor Green
$typeLines | ForEach-Object { Write-Host $_ }
Write-Host ""
Write-Host ("written: $typeOut")
Write-Host ("written: $fieldOut")
