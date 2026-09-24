
#
# Run this WHILE the app is stuck (do not restart first):
#     powershell -ExecutionPolicy Bypass -File docs\thread-dump.ps1
#
# Why a thread dump: four hypotheses were already disproved by tests (room/session
# deadlock, wrong reqId, engine not advancing, dead send channel). A dump shows
# exactly which line each thread is parked on - no more guessing.
#
# NOTE: keep this file pure ASCII. Windows PowerShell 5.1 reads .ps1 as ANSI/GBK.


param(
    [string]$TargetPid = '',
    [int]$WaitSeconds = 3,
    [string]$OutFile = ''
)

$ErrorActionPreference = 'Stop'

if (-not $OutFile) {
    $OutFile = Join-Path $env:TEMP 'hn-thread-dump.txt'
}

function Find-AppPid {
    # Locate the java process running the HainanMaJHong2 jar
    $procs = Get-CimInstance Win32_Process -Filter "Name = 'java.exe'" -ErrorAction SilentlyContinue
    foreach ($p in $procs) {
        $cl = $p.CommandLine
        if ($cl -and ($cl -like '*HainanMaJHong2*' -or $cl -like '*HainanMahjongApplication*')) {
            return $p.ProcessId
        }
    }
    return $null
}

if (-not $TargetPid) {
    $found = Find-AppPid
    if (-not $found) {
        Write-Host 'ERROR: no running java.exe with HainanMaJHong2 in its command line.'
        Write-Host '       Start the app first, or pass -TargetPid <pid> explicitly.'
        Write-Host ''
        Write-Host 'All java processes:'
        Get-CimInstance Win32_Process -Filter "Name = 'java.exe'" -ErrorAction SilentlyContinue |
            ForEach-Object { Write-Host ("  pid=" + $_.ProcessId + "  " + ($_.CommandLine -replace '\s+', ' ').Substring(0, [Math]::Min(150, $_.CommandLine.Length))) }
        exit 1
    }
    $TargetPid = $found
}

Write-Host ("Attaching to pid " + $TargetPid + " ...")

# Prefer jstack from JAVA_HOME, else search the usual JDK locations
$jstack = $null
if ($env:JAVA_HOME) {
    $cand = Join-Path $env:JAVA_HOME 'bin\jstack.exe'
    if (Test-Path $cand) { $jstack = $cand }
}
if (-not $jstack) {
    $cand = Get-ChildItem "$env:USERPROFILE\.jdks" -Directory -ErrorAction SilentlyContinue |
        ForEach-Object { Join-Path $_.FullName 'bin\jstack.exe' } |
        Where-Object { Test-Path $_ } |
        Select-Object -First 1
    if ($cand) { $jstack = $cand }
}

if (-not $jstack) {
    # Fall back to jcmd (also under the JDK bin)
    $cand = Get-ChildItem "$env:USERPROFILE\.jdks" -Directory -ErrorAction SilentlyContinue |
        ForEach-Object { Join-Path $_.FullName 'bin\jcmd.exe' } |
        Where-Object { Test-Path $_ } |
        Select-Object -First 1
    if ($cand) {
        Write-Host ("Using jcmd: " + $cand)
        & $cand $TargetPid Thread.print > $OutFile 2>&1
    } else {
        Write-Host 'ERROR: neither jstack.exe nor jcmd.exe found under ~/.jdks'
        exit 1
    }
} else {
    Write-Host ("Using jstack: " + $jstack)
    # -l also prints lock ownership, which matters when hunting a deadlock
    & $jstack -l $TargetPid > $OutFile 2>&1
}

if (-not (Test-Path $OutFile) -or (Get-Item $OutFile).Length -eq 0) {
    Write-Host 'ERROR: thread dump is empty. Try running the shell as Administrator.'
    exit 1
}

Write-Host ("Dump written: " + $OutFile + "  (" + (Get-Item $OutFile).Length + " bytes)")
Write-Host ''

$lines = Get-Content $OutFile

# 1) Deadlocks the JVM detected itself
$dead = Select-String -Path $OutFile -Pattern 'Found one Java-level deadlock|Found \d+ deadlock'
Write-Host '=== 1. Deadlocks detected by the JVM ==='
if ($dead) {
    $dead | ForEach-Object { Write-Host ('  ' + $_.Line) }
} else {
    Write-Host '  (none) - JVM did not detect a deadlock'
}
Write-Host ''

# 2) Where the game engine thread is parked
Write-Host '=== 2. Game engine thread (room-*) ==='
Write-Host '  Looking for: room-engine-* / room-bind-* / mahjong-timer'
$idx = 0
while ($idx -lt $lines.Count) {
    if ($lines[$idx] -match '"(room-engine-|room-bind-|mahjong-timer)') {
        for ($j = $idx; $j -lt [Math]::Min($idx + 22, $lines.Count); $j++) {
            Write-Host ('  ' + $lines[$j])
            if ($j -gt $idx -and $lines[$j] -eq '') { break }
        }
        Write-Host ''
    }
    $idx++
}

# 3) Where the WebSocket handling threads are parked
Write-Host '=== 3. WebSocket handling threads (http-nio-*-exec-*) ==='
$idx = 0
$shown = 0
while ($idx -lt $lines.Count -and $shown -lt 3) {
    if ($lines[$idx] -match '"http-nio-\d+-exec-') {
        for ($j = $idx; $j -lt [Math]::Min($idx + 22, $lines.Count); $j++) {
            Write-Host ('  ' + $lines[$j])
            if ($j -gt $idx -and $lines[$j] -eq '') { break }
        }
        Write-Host ''
        $shown++
    }
    $idx++
}
if ($shown -eq 0) { Write-Host '  (no http-nio exec thread found)' }

Write-Host ''
Write-Host '=== 4. Most frequent parked lines in our code ==='
Write-Host '  (the line that appears most often is the likely blocking point)'
Select-String -Path $OutFile -Pattern 'hainanMahjong\.' |
    ForEach-Object { ($_.Line -replace '^\s+at ', '').Trim() } |
    Group-Object |
    Sort-Object Count -Descending |
    Select-Object -First 15 |
    ForEach-Object { Write-Host ('  ' + $_.Count + 'x  ' + $_.Name) }

Write-Host ''
Write-Host ('Full dump: ' + $OutFile)
Write-Host 'Send the "=== 2." and "=== 3." sections (and section 1 if it found anything).'
