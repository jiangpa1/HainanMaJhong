# ============================================================================
#  Local launcher for HainanMaJhong  (ASCII only - PS 5.1 reads .ps1 as ANSI,
#  Chinese text in this file would break parsing. Keep it ASCII.)
#
#  Usage:  cd F:\HainanMaJhong2\HainanMaJhong
#          .\run-local.ps1                  # connect VM 192.168.133.128
#          .\run-local.ps1 -Probe           # reachability check only
#          .\run-local.ps1 -DbHost <host>
#
#  Secrets are read from .env and never echoed / never logged / never committed.
# ============================================================================
param(
    [string]$DbHost    = '192.168.133.128',
    [string]$DbName    = 'hainan_majong',
    [string]$RedisHost = $null,
    [switch]$Probe
)

$ErrorActionPreference = 'Stop'
$ProgressPreference    = 'SilentlyContinue'

$root    = $PSScriptRoot
$repo    = Split-Path $root -Parent
$envFile = Join-Path $root '.env'
$jar     = Join-Path $root 'target\HainanMaJhong2-1.0-SNAPSHOT.jar'
$javaExe = 'C:\Users\ASUS\.jdks\ms-17.0.20.1-1\bin\java.exe'
if (-not $RedisHost) { $RedisHost = $DbHost }

# ---------- 1. preflight ----------
if (-not (Test-Path $javaExe)) { throw "JDK 17 not found: $javaExe  (dir name has the -1 suffix!)" }
if (-not (Test-Path $envFile)) { throw ".env not found: $envFile  (needs DB_PASSWORD / REDIS_PASSWORD)" }

$cfg = @{}
foreach ($line in Get-Content $envFile) {
    if ($line -match '^\s*#' -or $line -match '^\s*$') { continue }
    if ($line -match '^\s*([A-Za-z_][A-Za-z0-9_]*)\s*=\s*(.*)$') {
        $cfg[$Matches[1]] = $Matches[2].Trim().Trim('"').Trim("'")
    }
}
if (-not $cfg.ContainsKey('DB_PASSWORD')) { throw '.env has no DB_PASSWORD' }

# ---------- 2. reachability ----------
function Test-HostPort([string]$h, [int]$p) {
    $c = New-Object System.Net.Sockets.TcpClient
    try {
        $t = $c.ConnectAsync($h, $p)
        if ($t.Wait(2000) -and $c.Connected) { return $true }
        return $false
    } catch { return $false } finally { $c.Dispose() }
}

Write-Host "[1/3] probing $DbHost :3306 and $RedisHost :6379 ..." -ForegroundColor Cyan
$dbOk    = Test-HostPort $DbHost    3306
$redisOk = Test-HostPort $RedisHost 6379
$dbText    = if ($dbOk)    { 'OK' }    else { 'DOWN' }
$redisText = if ($redisOk) { 'OK' }    else { 'DOWN' }
Write-Host "      MySQL -> $dbText"    -ForegroundColor $(if ($dbOk)    { 'Green' } else { 'Red' })
Write-Host "      Redis -> $redisText" -ForegroundColor $(if ($redisOk) { 'Green' } else { 'Red' })
if (-not $dbOk)  { Write-Host "      WARN: MySQL down. Start the VM first (HANDOFF sec.9: the VM is often off)." -ForegroundColor Yellow }
if (-not $redisOk) { Write-Host "      WARN: Redis down. App still starts, but room snapshot / restart-recovery is disabled." -ForegroundColor Yellow }
if ($Probe) { return }

# ---------- 3. inject env + start ----------
# Passwords go into the current process env only. application.yml reads these names.
$env:DB_URL      = "jdbc:mysql://${DbHost}:3306/${DbName}?serverTimezone=Asia/Shanghai&useUnicode=true&characterEncoding=utf-8"
$env:DB_USERNAME = 'root'
$env:DB_PASSWORD = $cfg['DB_PASSWORD']
$env:REDIS_HOST  = $RedisHost
$env:REDIS_PORT  = '6379'
if ($cfg.ContainsKey('REDIS_PASSWORD')) { $env:REDIS_PASSWORD = $cfg['REDIS_PASSWORD'] }

if (-not (Test-Path $jar)) { throw "jar not found: $jar   -- run: mvn -B clean package" }

$logDir = Join-Path $repo 'docs'
if (-not (Test-Path $logDir)) { New-Item -ItemType Directory -Force -Path $logDir | Out-Null }
$log = Join-Path $logDir 'stage0-run.log'

Write-Host "[2/3] starting app with JDK 17 ..." -ForegroundColor Cyan
Write-Host "      DB    = $DbHost/$DbName"    -ForegroundColor DarkGray
Write-Host "      Redis = ${RedisHost}:6379"   -ForegroundColor DarkGray
Write-Host "      log   = $log"               -ForegroundColor DarkGray
Write-Host "[3/3] open http://localhost:8080  (Ctrl+C to stop)" -ForegroundColor Green
Write-Host ""

& $javaExe -jar $jar 2>&1 | Tee-Object -FilePath $log
