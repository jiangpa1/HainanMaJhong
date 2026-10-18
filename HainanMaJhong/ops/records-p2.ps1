param(
    [Parameter(Mandatory=$true)]
    [ValidateSet('inspect','prepare','backfill','verify','promote','revoke')]
    [string]$Operation,
    [long]$AfterId = 0,
    [ValidateRange(1,500)][int]$BatchSize = 100,
    [long]$UserId = 0,
    [long]$SessionId = 0,
    [string]$JarPath = '',
    [string]$JavaPath = $(if ($env:JAVA_HOME) { Join-Path $env:JAVA_HOME 'bin/java.exe' } else { 'java' })
)
$ErrorActionPreference = 'Stop'
if (-not $JarPath) {
    foreach ($candidate in @('../target/HainanMaJhong2-1.0-SNAPSHOT.jar','../release/HainanMaJhong2-1.0-SNAPSHOT.jar','../HainanMaJhong2-1.0-SNAPSHOT.jar')) {
        $candidatePath = Join-Path $PSScriptRoot $candidate
        if (Test-Path -LiteralPath $candidatePath -PathType Leaf) { $JarPath = $candidatePath; break }
    }
}
foreach ($name in @('RECORDS_P2_JDBC_URL','RECORDS_P2_DB_USER','RECORDS_P2_DB_PASSWORD')) {
    if ($null -eq [Environment]::GetEnvironmentVariable($name)) {
        throw "Set $name in the environment; credentials must not be command-line arguments."
    }
}
if ($AfterId -lt 0) { throw 'AfterId must be nonnegative.' }
if (-not (Test-Path -LiteralPath $JarPath -PathType Leaf)) { throw "Jar not found: $JarPath" }
$taskArguments = @($Operation)
if ($Operation -in @('backfill','verify')) { $taskArguments += @("$AfterId", "$BatchSize") }
if ($Operation -eq 'inspect' -and $UserId -gt 0 -and $SessionId -gt 0) { $taskArguments += @("$UserId", "$SessionId") }
& $JavaPath '-Dloader.main=hainanMahjong.ops.RecordsP2Migration' -cp $JarPath 'org.springframework.boot.loader.PropertiesLauncher' @taskArguments
if ($LASTEXITCODE -ne 0) { throw "P2 $Operation failed (exit $LASTEXITCODE); the read switch must remain off." }
