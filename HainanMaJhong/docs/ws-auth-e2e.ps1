# WebSocket auth handshake end-to-end check.
#
# Verifies that /room and /game can no longer be entered with only ?userId=,
# and that a real accessToken is required.
#
# NOTE: this file must stay pure ASCII. Windows PowerShell 5.1 reads .ps1 as
# ANSI/GBK, so any non-ASCII byte here can break parsing on the user machine.

param(
    [int]$Port = 8080,
    [string]$Jar = 'target\HainanMaJHong2-1.0-SNAPSHOT.jar',
    [string]$Username = 'wsprobe',
    [string]$Password = 'wsTest12345'
)

$ErrorActionPreference = 'Stop'
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8

# this script lives in <module>\docs, so the maven module is one level up
$moduleDir = Split-Path -Parent $PSScriptRoot
$logFile   = Join-Path $env:TEMP 'hn-ws-auth-e2e.log'

$script:results = New-Object System.Collections.ArrayList

function Check([string]$name, [bool]$ok, [string]$detail) {
    $mark = if ($ok) { '[PASS]' } else { '[FAIL]' }
    Write-Host ("{0} {1}  {2}" -f $mark, $name, $detail)
    [void]$script:results.Add($ok)
}

# ---------- load env ----------
$envFile = Join-Path $moduleDir '.env'
if (-not (Test-Path $envFile)) { throw "missing .env: $envFile" }
foreach ($line in Get-Content $envFile) {
    if ($line -match '^\s*([A-Za-z_][A-Za-z0-9_]*)\s*=\s*(.*)$') {
        $k = $Matches[1]
        $v = $Matches[2].Trim().Trim('"').Trim("'")
        [Environment]::SetEnvironmentVariable($k, $v, 'Process')
    }
}
$env:DB_URL      = 'jdbc:mysql://192.168.133.128:3306/hainan_majong?useSSL=false&serverTimezone=Asia/Shanghai&allowPublicKeyRetrieval=true&characterEncoding=utf8'
$env:DB_USERNAME = 'root'
$env:REDIS_HOST  = '192.168.133.128'
$env:REDIS_PORT  = '6379'

if (-not $env:JWT_SECRET -or $env:JWT_SECRET.Length -lt 32) {
    throw "JWT_SECRET missing or shorter than 32 chars; fix .env first"
}

$jarPath = Join-Path $moduleDir $Jar
if (-not (Test-Path $jarPath)) { throw "jar not found: $jarPath (run mvn -DskipTests package first)" }

# Use the JDK selected by JAVA_HOME. The `java` on PATH on this machine is a JDK 8,
# which cannot load classes compiled for Java 17 (class file version 61).
$javaExe = 'java'
if ($env:JAVA_HOME) {
    $candidate = Join-Path $env:JAVA_HOME 'bin\java.exe'
    if (Test-Path $candidate) { $javaExe = $candidate }
}
Write-Host "using java: $javaExe"
# `java -version` prints to stderr; under ErrorActionPreference=Stop that would
# be treated as a terminating error, so relax it just for this line.
$prevEap = $ErrorActionPreference
$ErrorActionPreference = 'Continue'
$versionLine = (& $javaExe -version 2>&1 | Select-Object -First 1)
$ErrorActionPreference = $prevEap
Write-Host "           $versionLine"

# ---------- websocket probe helper ----------
function ProbeRoom([string]$query, [int]$timeoutMs = 8000) {
    $ws = New-Object System.Net.WebSockets.ClientWebSocket
    $uri = [Uri]("ws://127.0.0.1:$Port/room" + $query)
    $cts = New-Object System.Threading.CancellationTokenSource
    $cts.CancelAfter($timeoutMs)
    $error_ = ''
    try {
        $ws.ConnectAsync($uri, $cts.Token).Wait($timeoutMs + 2000) | Out-Null
    } catch {
        $error_ = $_.Exception.GetBaseException().Message
    }
    $state = "$($ws.State)"
    try { $ws.Dispose() } catch { }
    try { $cts.Dispose() } catch { }
    return [pscustomobject]@{ State = $state; Error = $error_ }
}

# ---------- start app ----------
Write-Host "starting app on port $Port ..."
$proc = Start-Process -FilePath $javaExe -ArgumentList @('-jar', $jarPath, "--server.port=$Port") `
        -WorkingDirectory $moduleDir -RedirectStandardOutput $logFile -RedirectStandardError "$logFile.err" `
        -PassThru -WindowStyle Hidden

try {
    $ready = $false
    for ($i = 0; $i -lt 90; $i++) {
        Start-Sleep -Seconds 1
        if ($proc.HasExited) { break }
        if ((Test-Path $logFile) -and (Select-String -Path $logFile -Pattern 'Started HainanMahjongApplication' -Quiet -ErrorAction SilentlyContinue)) {
            $ready = $true
            break
        }
    }
    if (-not $ready) {
        Write-Host 'app did not start; last log lines:'
        if (Test-Path $logFile) { Get-Content $logFile -Tail 40 | ForEach-Object { "    $_" } }
        throw 'startup failed'
    }
    Write-Host "app started (pid $($proc.Id))"
    Write-Host ''

    $base = "http://127.0.0.1:$Port"

    # ---------- 1. get a token (login first, register only if the user is new) ----------
    # A failure here must abort loudly: continuing with a null token would make the
    # "valid token accepted" check fail for a reason that has nothing to do with auth.
    $loginBody = @{ username = $Username; password = $Password } | ConvertTo-Json -Compress
    $accessToken = $null
    $loginCode = $null
    $loginMessage = ''
    try {
        $login = Invoke-RestMethod -Uri "$base/api/login" -Method Post -Body $loginBody `
                 -ContentType 'application/json; charset=utf-8' -TimeoutSec 20
        $loginCode = $login.code
        $loginMessage = $login.message
        if ($login.code -eq 200 -and $login.data -and $login.data.accessToken) {
            $accessToken = $login.data.accessToken
        }
    } catch {
        $loginMessage = $_.Exception.Message
    }

    if ($accessToken) {
        Write-Host "logged in as probe user '$Username'"
    } else {
        Write-Host "login not usable (code=$loginCode msg=$loginMessage); trying register"
        $regBody = @{ username = $Username; password = $Password; nickname = 'wsprobe' } | ConvertTo-Json -Compress
        $reg = Invoke-RestMethod -Uri "$base/api/register" -Method Post -Body $regBody `
               -ContentType 'application/json; charset=utf-8' -TimeoutSec 20
        if ($reg.code -ne 200 -or -not $reg.data -or -not $reg.data.accessToken) {
            throw "cannot obtain an accessToken: login code=$loginCode msg=$loginMessage; register code=$($reg.code) msg=$($reg.message)"
        }
        $accessToken = $reg.data.accessToken
        Write-Host "registered probe user '$Username'"
    }
    Write-Host ''

    # ---------- 2. /room handshake probes ----------
    $r1 = ProbeRoom ''
    Check 'room: no credentials rejected' ($r1.State -ne 'Open') "state=$($r1.State) err=$($r1.Error)"

    $r2 = ProbeRoom '?userId=999'
    Check 'room: ?userId=999 rejected (IDOR closed)' ($r2.State -ne 'Open') "state=$($r2.State) err=$($r2.Error)"

    $r3 = ProbeRoom '?token=not-a-jwt'
    Check 'room: invalid token rejected' ($r3.State -ne 'Open') "state=$($r3.State) err=$($r3.Error)"

    $r4 = ProbeRoom ('?token=' + $accessToken)
    Check 'room: valid token accepted' ($r4.State -eq 'Open') "state=$($r4.State) err=$($r4.Error)"

    # ---------- 3. the token resolves to the right identity ----------
    $me = Invoke-RestMethod -Uri "$base/api/user/me" `
          -Headers @{ Authorization = "Bearer $accessToken" } -TimeoutSec 20
    $meName = if ($me.data) { $me.data.username } else { '' }
    Check 'token maps to probe user' (($me.code -eq 200) -and ($meName -eq $Username)) "code=$($me.code) me=$meName"

    # ---------- 4. /game also refuses a bare userId ----------
    $g = New-Object System.Net.WebSockets.ClientWebSocket
    $gcts = New-Object System.Threading.CancellationTokenSource
    $gcts.CancelAfter(8000)
    try {
        $g.ConnectAsync([Uri]("ws://127.0.0.1:$Port/game?userId=999&code=ZZZZ&seat=EAST"), $gcts.Token).Wait(10000) | Out-Null
    } catch { }
    $gState = "$($g.State)"
    try { $g.Dispose() } catch { }
    try { $gcts.Dispose() } catch { }
    Check 'game: ?userId=999 rejected' ($gState -ne 'Open') "state=$gState"

    # ---------- summary ----------
    Write-Host ''
    $passed = @($script:results | Where-Object { $_ }).Count
    $total  = $script:results.Count
    Write-Host "==== $passed / $total checks passed ===="
    if ($passed -ne $total) { exit 1 }
    exit 0
}
finally {
    if ($proc -and -not $proc.HasExited) {
        Write-Host ''
        Write-Host "stopping app (pid $($proc.Id)) ..."
        try { $proc.Kill() } catch { }
        Start-Sleep -Seconds 2
    }
}
