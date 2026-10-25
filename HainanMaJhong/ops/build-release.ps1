param(
    [string]$JavaHome = $env:JAVA_HOME,
    [string]$Maven = 'mvn',
    [string]$Pnpm = 'pnpm'
)
$ErrorActionPreference = 'Stop'
$project = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$target = Join-Path $project 'target'
if ($JavaHome) {
    $env:JAVA_HOME = (Resolve-Path -LiteralPath $JavaHome).Path
    if (!(Test-Path -LiteralPath (Join-Path $env:JAVA_HOME 'bin/javac.exe'))) { throw 'JavaHome must contain a complete JDK' }
    $env:PATH = (Join-Path $env:JAVA_HOME 'bin') + [IO.Path]::PathSeparator + $env:PATH
}
function Invoke-Checked([string]$Program, [string[]]$Arguments) {
    & $Program @Arguments
    if ($LASTEXITCODE -ne 0) { throw "$Program failed: exit $LASTEXITCODE" }
}
# Maven clean deletes target recursively: require its resolved location to be this project.
if (Test-Path -LiteralPath $target) {
    $actual = (Resolve-Path -LiteralPath $target).Path
    if ($actual -ne $target -or (Get-Item -LiteralPath $target).Attributes -band [IO.FileAttributes]::ReparsePoint) {
        throw 'Refusing to clean a redirected target directory'
    }
}
Push-Location $project
try {
    $javaVersion = (& java --version 2>&1 | Out-String)
    if ($LASTEXITCODE -ne 0 -or $javaVersion -notmatch '(?m)^(?:openjdk|java)\s+(\d+)') { throw 'JDK 21 is required' }
    if ([int]$Matches[1] -lt 21) { throw 'JDK 21 or newer is required' }
    Invoke-Checked 'java' @('-version')
    Invoke-Checked $Maven @('--version')
    Push-Location (Join-Path $project 'frontend')
    try { Invoke-Checked $Pnpm @('build') } finally { Pop-Location }
    # Compile tests but do not execute them. No patched classes or old target contents.
    Invoke-Checked $Maven @('clean', 'package', '-DskipTests')
    $jar = Join-Path $target 'HainanMaJhong2-1.0-SNAPSHOT.jar'
    Add-Type -AssemblyName System.IO.Compression.FileSystem
    $zip = [IO.Compression.ZipFile]::OpenRead($jar)
    try {
        $entries = @($zip.Entries | ForEach-Object FullName)
        $classes = @(Get-ChildItem (Join-Path $target 'classes') -Recurse -File -Filter '*.class')
        foreach ($file in $classes) {
            $relative = $file.FullName.Substring((Join-Path $target 'classes').Length + 1).Replace('\', '/')
            if ($entries -notcontains ('BOOT-INF/classes/' + $relative)) { throw "Missing compiled class: $relative" }
        }
        if ($entries -notcontains 'BOOT-INF/classes/static/index.html') { throw 'Missing frontend entry' }
        if ($entries -notcontains 'BOOT-INF/classes/hainanMahjong/service/impl/GamePushServiceImpl$Encoded.class') {
            throw 'Missing broadcast nested class'
        }
    } finally { $zip.Dispose() }
    $sourceRoot = (Resolve-Path (Join-Path $project '..')).Path
    $sources = @(Get-ChildItem (Join-Path $project 'src/main'), (Join-Path $project 'frontend/src'), (Join-Path $project 'frontend/public'), (Join-Path $project 'frontend/tools') -Recurse -File)
    $sources += Get-Item (Join-Path $project 'pom.xml'), (Join-Path $project 'frontend/index.html'), (Join-Path $project 'frontend/package.json'), (Join-Path $project 'frontend/pnpm-lock.yaml'), (Join-Path $project 'frontend/vite.config.js'), $PSCommandPath
    $manifest = @($sources | Sort-Object FullName | ForEach-Object {
        @{ path = $_.FullName.Substring($sourceRoot.Length + 1).Replace('\', '/'); sha256 = (Get-FileHash -LiteralPath $_.FullName -Algorithm SHA256).Hash.ToLowerInvariant() }
    })
    $manifest | ConvertTo-Json -Depth 4 | Set-Content -Encoding utf8 (Join-Path $target 'source-manifest.json')
    $report = [ordered]@{
        createdAt = [DateTimeOffset]::Now.ToString('o')
        jar = [IO.Path]::GetFileName($jar)
        sha256 = (Get-FileHash -LiteralPath $jar -Algorithm SHA256).Hash.ToLowerInvariant()
        sourceManifestSha256 = (Get-FileHash -LiteralPath (Join-Path $target 'source-manifest.json') -Algorithm SHA256).Hash.ToLowerInvariant()
        compiledClasses = $classes.Count; testsExecuted = $false
        nodeVersion = (& node --version); pnpmVersion = (& $Pnpm --version)
        gitCommit = (& git rev-parse HEAD); workingTreeDirty = [bool](& git status --porcelain)
        validation = 'frontend build; Maven clean package -DskipTests; compiled-class and frontend-entry archive checks'
    }
    $report | ConvertTo-Json | Set-Content -Encoding utf8 (Join-Path $target 'build-report.json')
    $report | ConvertTo-Json
} finally { Pop-Location }
