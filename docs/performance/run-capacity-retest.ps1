param(
  [Parameter(Mandatory=$true)][string]$VaultPath,
  [ValidateSet('identity','gzip')][string]$Encoding='gzip',
  [ValidateSet('legacy','v2')][string]$RecordsVersion='legacy',
  [string]$Tables='48',
  [ValidateRange(30,600)][int]$Seconds=300,
  [Parameter(Mandatory=$true)][string]$RunName
)
$ErrorActionPreference='Stop'
if ($RunName -notmatch '^[a-zA-Z0-9-]+$') { throw 'Invalid run name' }
$taskRepo = (Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
$taskOut = Join-Path $PSScriptRoot "results-2026-10-11-retest/$RunName"
if (Test-Path -LiteralPath (Join-Path $taskOut 'results.json')) { throw 'Refusing to overwrite an existing run' }
$env:TEST_BASE='http://jiangpahnmj.cn'
$env:TEST_CONNECT_IP='39.107.247.119'
$env:TEST_TABLES=$Tables
$env:TEST_STAGE_SECONDS=[string]$Seconds
$env:TEST_HANDSHAKE_TIMEOUT_MS='20000'
$env:TEST_REUSE_VAULT=(Resolve-Path -LiteralPath $VaultPath).Path
$env:TEST_NO_VAULT='1'
$env:TEST_ACCEPT_ENCODING=$Encoding
$env:TEST_RECORDS_VERSION=$RecordsVersion
$env:TEST_STOP_FILE=Join-Path $PSScriptRoot 'results-2026-10-11-retest/resource-stop.json'
$env:TEST_OUT=$taskOut
$taskNode='C:\Users\ASUS\.cache\codex-runtimes\codex-primary-runtime\dependencies\node\bin\node.exe'
Push-Location $taskRepo
try {
  & $taskNode (Join-Path $PSScriptRoot 'online-capacity-retest.mjs') *> (Join-Path $PSScriptRoot "results-2026-10-11-retest/$RunName.log")
  if ($LASTEXITCODE -ne 0) { throw "Test process failed, exit $LASTEXITCODE" }
} finally { Pop-Location }
