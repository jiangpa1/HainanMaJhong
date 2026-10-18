# ASCII-only PowerShell. Builds the JMeter account CSV from the private account fixture.
# Usage: make-accounts-csv.ps1 [-Count 320]
param(
    [string]$Source = 'F:\HainanMaJHong2\docs\performance\private\accounts.json',
    [string]$Out    = 'F:\HainanMaJHong2\docs\performance\private\jmeter-accounts.csv',
    [int]$Count = 320
)
$ErrorActionPreference = 'Stop'
if (!(Test-Path -LiteralPath $Source)) { throw "missing accounts fixture: $Source" }
# NOTE: ConvertFrom-Json returns the whole JSON array as ONE object in PowerShell 5.1,
# so index the result directly instead of wrapping it in @() before slicing.
$accounts = [IO.File]::ReadAllText($Source, [Text.Encoding]::UTF8) | ConvertFrom-Json
if ($accounts.Length -lt $Count) { throw "fixture has $($accounts.Length) accounts, need $Count" }
function Quote([string]$v) { if ($v -match '[",\r\n]') { return '"' + $v.Replace('"', '""') + '"' } return $v }
$sb = New-Object System.Text.StringBuilder
[void]$sb.AppendLine('username,password,index')
for ($i = 0; $i -lt $Count; $i++) {
    $a = $accounts[$i]
    [void]$sb.AppendLine("$(Quote $a.username),$(Quote $a.password),$($i + 1)")
}
[IO.File]::WriteAllText($Out, $sb.ToString(), (New-Object Text.UTF8Encoding($false)))
Write-Host "accounts csv written: $Out ($Count rows + header)"
