[CmdletBinding()]
param(
    [string]$MonitoringRoot = 'F:\MahjongMonitoring',
    [string]$IdentityFile = "$env:USERPROFILE\.ssh\mahjong_ops",
    [string]$SshHost = 'root@39.107.247.119',
    [switch]$ValidateOnly,
    [switch]$NoBrowser
)

# Windows PowerShell 5.1 and PowerShell 7. Services keep running after this script exits.
$ErrorActionPreference = 'Stop'
$MonitoringRoot = [System.IO.Path]::GetFullPath($MonitoringRoot).TrimEnd('\', '/')
$prometheusHome = Join-Path $MonitoringRoot 'prometheus'
$grafanaHome = Join-Path $MonitoringRoot 'grafana'
$prometheusExe = Join-Path $prometheusHome 'prometheus.exe'
$grafanaExe = Join-Path $grafanaHome 'bin\grafana.exe'
$grafanaArguments = @('server', "--homepath=$grafanaHome", 'cfg:server.http_addr=127.0.0.1')
if (!(Test-Path -LiteralPath $grafanaExe -PathType Leaf)) {
    $grafanaExe = Join-Path $grafanaHome 'bin\grafana-server.exe'
    $grafanaArguments = @("--homepath=$grafanaHome", 'cfg:server.http_addr=127.0.0.1')
}

function Assert-File([string]$Path) {
    if (!(Test-Path -LiteralPath $Path -PathType Leaf)) { throw "Required file missing: $Path" }
}

function Assert-X64Executable([string]$Path) {
    Assert-File $Path
    $stream = [System.IO.File]::OpenRead($Path)
    try {
        $reader = [System.IO.BinaryReader]::new($stream)
        if ($reader.ReadUInt16() -ne 0x5A4D) { throw "Not a Windows executable: $Path" }
        $stream.Position = 60
        $offset = $reader.ReadInt32()
        if ($offset -lt 0 -or $offset + 6 -gt $stream.Length) { throw "Invalid executable header: $Path" }
        $stream.Position = $offset
        if ($reader.ReadUInt32() -ne 0x4550) { throw "Invalid Windows executable: $Path" }
        $machine = $reader.ReadUInt16()
        if ($machine -ne 0x8664) {
            throw "Download the Windows AMD64 (64 Bit) package. Binary machine 0x$('{0:X4}' -f $machine): $Path"
        }
    } finally { $stream.Dispose() }
}

function Get-Endpoint([string]$Url) {
    try { return (Invoke-WebRequest -Uri $Url -UseBasicParsing -TimeoutSec 3).Content }
    catch { return $null }
}

function Test-ServiceEndpoint([string]$Kind, [string]$Url) {
    $body = Get-Endpoint $Url
    if (!$body) { return $false }
    if ($Kind -eq 'ssh') { return $body -match '(?m)^mahjong_' }
    if ($Kind -eq 'prometheus') { return $body -match 'Prometheus Server is Ready' }
    try { return ($body | ConvertFrom-Json).database -eq 'ok' } catch { return $false }
}

function Test-Listening([int]$Port) {
    $client = [System.Net.Sockets.TcpClient]::new()
    try {
        $pending = $client.BeginConnect('127.0.0.1', $Port, $null, $null)
        if (!$pending.AsyncWaitHandle.WaitOne(500)) { return $false }
        $client.EndConnect($pending)
        return $true
    } catch { return $false } finally { $client.Dispose() }
}

# Start-Process joins arguments into a Windows command line; quote paths containing spaces.
function Quote-NativeArgument([string]$Value) {
    if ($Value -notmatch '[\s"]') { return $Value }
    $escaped = [regex]::Replace($Value, '(\\*)"', '$1$1\"')
    $escaped = [regex]::Replace($escaped, '(\\+)$', '$1$1')
    return '"' + $escaped + '"'
}

function Ensure-Service {
    param([string]$Name, [string]$Kind, [int]$Port, [string]$Url,
          [string]$Executable, [string[]]$NativeArguments, [string]$WorkingDirectory, [int]$TimeoutSeconds)
    if (Test-ServiceEndpoint $Kind $Url) {
        Write-Host "[OK] $Name is already running; reusing it." -ForegroundColor Green
        return
    }
    if (Test-Listening $Port) {
        throw "Port $Port is occupied, but $Name is not healthy. Check its existing process; no process was stopped."
    }
    $stamp = Get-Date -Format 'yyyyMMdd-HHmmss-fff'
    $stdout = Join-Path $script:logHome "$Kind-$stamp.out.log"
    $stderr = Join-Path $script:logHome "$Kind-$stamp.err.log"
    $argumentLine = ($NativeArguments | ForEach-Object { Quote-NativeArgument $_ }) -join ' '
    Write-Host "Starting $Name ..."
    $process = Start-Process -FilePath $Executable -ArgumentList $argumentLine -WorkingDirectory $WorkingDirectory `
        -WindowStyle Hidden -RedirectStandardOutput $stdout -RedirectStandardError $stderr -PassThru
    $watch = [System.Diagnostics.Stopwatch]::StartNew()
    try {
        while ($watch.Elapsed.TotalSeconds -lt $TimeoutSeconds) {
            $process.Refresh()
            if ($process.HasExited) { throw "$Name exited (code $($process.ExitCode)). Logs: $stdout and $stderr" }
            if (Test-ServiceEndpoint $Kind $Url) {
                Write-Host "[OK] $Name ready (PID $($process.Id))." -ForegroundColor Green
                return
            }
            Start-Sleep -Milliseconds 700
        }
        throw "$Name did not become ready. Logs: $stdout and $stderr"
    } catch {
        # Only stop the failed process created by this invocation.
        $process.Refresh()
        if (!$process.HasExited) { Stop-Process -Id $process.Id -ErrorAction SilentlyContinue }
        throw
    }
}

try {
    if (![Environment]::Is64BitOperatingSystem) { throw 'This launcher requires 64-bit Windows.' }
    $sshExe = (Get-Command ssh.exe -ErrorAction Stop).Source
    Assert-File $IdentityFile
    Assert-X64Executable $prometheusExe
    Assert-X64Executable $grafanaExe
    Assert-File (Join-Path $prometheusHome 'prometheus.yml')
    Assert-File (Join-Path $prometheusHome 'capacity-alerts.yml')
    Assert-File (Join-Path $grafanaHome 'conf\defaults.ini')
    if ($ValidateOnly) {
        Write-Host '[OK] Paths, private key and executable architectures validated.' -ForegroundColor Green
        exit 0
    }

    $script:logHome = Join-Path $MonitoringRoot 'launcher-logs'
    New-Item -ItemType Directory -Path $script:logHome -Force | Out-Null
    Ensure-Service -Name 'SSH metrics tunnel' -Kind ssh -Port 9091 `
        -Url 'http://127.0.0.1:9091/actuator/prometheus' -Executable $sshExe -WorkingDirectory $MonitoringRoot `
        -TimeoutSeconds 20 -NativeArguments @('-i', $IdentityFile, '-N', '-L', '127.0.0.1:9091:127.0.0.1:9091',
            '-o', 'BatchMode=yes', '-o', 'ExitOnForwardFailure=yes', '-o', 'ConnectTimeout=10',
            '-o', 'ServerAliveInterval=30', '-o', 'ServerAliveCountMax=3', $SshHost)
    Ensure-Service -Name Prometheus -Kind prometheus -Port 9090 -Url 'http://127.0.0.1:9090/-/ready' `
        -Executable $prometheusExe -WorkingDirectory $prometheusHome -TimeoutSeconds 30 `
        -NativeArguments @('--config.file=prometheus.yml', '--web.listen-address=127.0.0.1:9090',
            '--storage.tsdb.path=./data', '--storage.tsdb.retention.time=7d')
    Ensure-Service -Name Grafana -Kind grafana -Port 3000 -Url 'http://127.0.0.1:3000/api/health' `
        -Executable $grafanaExe -WorkingDirectory $grafanaHome -TimeoutSeconds 90 -NativeArguments $grafanaArguments

    $dashboardUrl = 'http://127.0.0.1:3000/d/hainan-capacity'
    Write-Host "Ready: $dashboardUrl" -ForegroundColor Green
    Write-Host "Services run in the background. Logs: $script:logHome"
    Write-Host 'After Windows restarts, run this launcher again. Import the dashboard once if not already imported.'
    if (!$NoBrowser) { Start-Process $dashboardUrl }
    exit 0
} catch {
    Write-Host "[ERROR] $($_.Exception.Message)" -ForegroundColor Red
    if ($_.Exception.Message -match 'SSH') {
        Write-Host 'For SSH authentication errors, unlock the private key in ssh-agent or test the SSH login manually.'
    }
    exit 1
}
