# One-time cleanup: archive historical evidence, remove only reproducible artifacts.
param([switch]$Apply)
$ErrorActionPreference = 'Stop'
$taskRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))
$taskArchive = Join-Path $taskRoot '.local-archive/history-2026-10-12'
$taskManifest = [Collections.Generic.List[object]]::new()
function Resolve-TaskPath([string]$relative) {
    $absolute = [IO.Path]::GetFullPath((Join-Path $taskRoot $relative))
    if (-not $absolute.StartsWith($taskRoot + [IO.Path]::DirectorySeparatorChar,[StringComparison]::OrdinalIgnoreCase)) {
        throw "Path outside intended workspace: $relative"
    }
    return $absolute
}
function Archive-TaskPath([string]$relative) {
    $source = Resolve-TaskPath $relative
    if (-not (Test-Path -LiteralPath $source)) { return }
    $destination = Resolve-TaskPath ('.local-archive/history-2026-10-12/' + $relative)
    if (Test-Path -LiteralPath $destination) { throw "Archive exists: $relative" }
    $taskManifest.Add([pscustomobject]@{action='archive';source=$relative;destination=$destination.Substring($taskRoot.Length+1)})
    if ($Apply) {
        New-Item -ItemType Directory -Force -Path (Split-Path $destination -Parent) | Out-Null
        Move-Item -LiteralPath $source -Destination $destination
    }
}
function Remove-GeneratedPath([string]$relative) {
    $source = Resolve-TaskPath $relative
    if (-not (Test-Path -LiteralPath $source)) { return }
    $taskManifest.Add([pscustomobject]@{action='remove-generated';source=$relative})
    if ($Apply) { Remove-Item -LiteralPath $source -Recurse -Force }
}
foreach ($relative in @('back/HainanMaJhong.jar','mjlib_java','out_compile','output/work','output/outputs','tmp','.claude',
    '更新重启.txt','规则.docx','开发麻将游戏流程.docx','jiangpahnmj.apk','HainanMaJhong/jiangpahnmj.apk','HainanMaJhong/.idea',
    'HainanMaJhong/tools/fix_mojibake.py','HainanMaJhong/tools/restore_encoding.py','HainanMaJhong/game_log.txt','docs/legacy-ref')) {
    Archive-TaskPath $relative
}
foreach ($file in Get-ChildItem -LiteralPath (Resolve-TaskPath 'HainanMaJhong') -File -Filter '*.log') {
    Archive-TaskPath ('HainanMaJhong/' + $file.Name)
}
$keepDocs = @('CODE-READING-GUIDE.md','ROOMMANAGER-READING-GUIDE.md','MULTIPLAYER-READING-GUIDE.md','HTTP-LAYER-ALIGNMENT.md')
foreach ($file in Get-ChildItem -LiteralPath (Resolve-TaskPath 'docs') -File) {
    if ($file.Name -notin $keepDocs) { Archive-TaskPath ('docs/' + $file.Name) }
}
foreach ($file in Get-ChildItem -LiteralPath (Resolve-TaskPath 'HainanMaJhong/docs') -File) {
    if ($file.Name -like 'probe-*.png' -or $file.Name -like 'ui-*.png') { Archive-TaskPath ('HainanMaJhong/docs/' + $file.Name) }
}
foreach ($name in @('build-online-retest-report.py','build-optimization-comparison.py','build-report.mjs','online-load-test.mjs',
    'online-websocket-test.mjs','online-retest-final-audit.mjs','online-retest-monitor.mjs','online-retest-ssh-monitor.mjs',
    'run-capacity-retest.ps1','线上复测说明_2026-10-11.md')) { Archive-TaskPath ('docs/performance/' + $name) }
foreach ($directory in Get-ChildItem -LiteralPath (Resolve-TaskPath 'docs/performance') -Directory -Filter 'results-*') {
    $keep = if ($directory.Name -eq 'results-2026-10-11-retest') { @('charts','online-comparison.csv','online-comparison-data.json') }
            elseif ($directory.Name -eq 'results-2026-10-11-summary') { @('charts','comparison-data.json','gzip-level-comparison.csv','record-size-comparison.csv') }
            else { @() }
    foreach ($item in Get-ChildItem -LiteralPath $directory.FullName -Force) {
        if ($item.Name -notin $keep) { Archive-TaskPath ('docs/performance/' + $directory.Name + '/' + $item.Name) }
    }
    if ($Apply -and (Get-ChildItem -LiteralPath $directory.FullName -Force).Count -eq 0) { Remove-Item -LiteralPath $directory.FullName }
}
# Preserve current runnable jar before removing Maven output.
$taskJar = Resolve-TaskPath 'HainanMaJhong/target/HainanMaJhong2-1.0-SNAPSHOT.jar'
$taskRelease = Resolve-TaskPath 'HainanMaJhong/release/HainanMaJhong2-1.0-SNAPSHOT.jar'
if (Test-Path -LiteralPath $taskJar) {
    $taskManifest.Add([pscustomobject]@{action='keep-release';source='HainanMaJhong/target/HainanMaJhong2-1.0-SNAPSHOT.jar';destination='HainanMaJhong/release/HainanMaJhong2-1.0-SNAPSHOT.jar'})
    if ($Apply) {
        New-Item -ItemType Directory -Force -Path (Split-Path $taskRelease -Parent) | Out-Null
        Copy-Item -LiteralPath $taskJar -Destination $taskRelease
        if ((Get-FileHash -LiteralPath $taskJar).Hash -ne (Get-FileHash -LiteralPath $taskRelease).Hash) { throw 'Release copy mismatch' }
    }
}
if ($Apply -and -not (Test-Path -LiteralPath $taskRelease)) { throw 'Release jar must exist before removing target' }
foreach ($relative in @('HainanMaJhong/target','HainanMaJhong/frontend/node_modules','HainanMaJhong/frontend/__hn_shim__.mjs',
    'docs/performance/__pycache__','HainanMaJhong/ops/__pycache__')) { Remove-GeneratedPath $relative }
if ($Apply) {
    New-Item -ItemType Directory -Force -Path (Resolve-TaskPath 'data/schema-backups') | Out-Null
    foreach ($file in Get-ChildItem -LiteralPath (Resolve-TaskPath 'back') -File -Filter '*.sql') {
        Move-Item -LiteralPath $file.FullName -Destination (Resolve-TaskPath ('data/schema-backups/' + $file.Name))
        $taskManifest.Add([pscustomobject]@{action='keep-sql';source=('back/' + $file.Name);destination=('data/schema-backups/' + $file.Name)})
    }
    if ((Get-ChildItem -LiteralPath (Resolve-TaskPath 'back') -Force).Count -eq 0) { Remove-Item -LiteralPath (Resolve-TaskPath 'back') }
    $taskManifest | ConvertTo-Json -Depth 5 | Set-Content -LiteralPath (Resolve-TaskPath 'data/workspace-cleanup-2026-10-12.json') -Encoding utf8
}
$taskManifest | Group-Object action | Select-Object Name,Count
