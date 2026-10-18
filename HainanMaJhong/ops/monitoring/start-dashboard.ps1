[CmdletBinding()]
param(
    [string]$MonitoringRoot = 'F:\MahjongMonitoring',
    [string]$IdentityFile = "$env:USERPROFILE\.ssh\mahjong_ops",
    [string]$SshHost = 'root@39.107.247.119',
    [int]$GrafanaPort = 3000,
    [switch]$NoBrowser,
    [switch]$ValidateOnly
)

# 与 start-monitoring.ps1 的区别：
#   Prometheus + Alertmanager 已经常驻在云服务器（systemd 自启、会发邮件），
#   所以本机【只做两件事】：① 把 9090/9093/9091 隧道到云端；② 启动 Grafana 看板。
#   刻意不启动本地 Prometheus / Alertmanager —— 否则会抢 9090 端口、并且重复发告警邮件。

$ErrorActionPreference = 'Stop'
$MonitoringRoot = [System.IO.Path]::GetFullPath($MonitoringRoot).TrimEnd('\', '/')
$grafanaHome = Join-Path $MonitoringRoot 'grafana'
$grafanaExe = Join-Path $grafanaHome 'bin\grafana.exe'
$grafanaArgs = @('server', "--homepath=$grafanaHome", 'cfg:server.http_addr=127.0.0.1')
if (!(Test-Path -LiteralPath $grafanaExe -PathType Leaf)) {
    $grafanaExe = Join-Path $grafanaHome 'bin\grafana-server.exe'
    $grafanaArgs = @("--homepath=$grafanaHome", 'cfg:server.http_addr=127.0.0.1')
}
$logHome = Join-Path $MonitoringRoot 'launcher-logs'
$stamp = Get-Date -Format 'yyyyMMdd-HHmmss-fff'
function Assert-File([string]$Path) { if (!(Test-Path -LiteralPath $Path -PathType Leaf)) { throw "缺少文件：$Path" } }
function Test-Listening([int]$Port) {
    $c = [System.Net.Sockets.TcpClient]::new()
    try { $p = $c.BeginConnect('127.0.0.1', $Port, $null, $null); if (!$p.AsyncWaitHandle.WaitOne(700)) { return $false }; $c.EndConnect($p); return $true }
    catch { return $false } finally { $c.Dispose() }
}
function Get-Json([string]$Url) { try { return (Invoke-WebRequest -Uri $Url -UseBasicParsing -TimeoutSec 5).Content | ConvertFrom-Json } catch { return $null } }

Assert-File $grafanaExe
Assert-File $IdentityFile
if (!(Test-Path $logHome)) { New-Item -ItemType Directory -Force -Path $logHome | Out-Null }

$want = @(9090, 9093, 9091)                     # 9090/9093 = Prometheus/Alertmanager；9091 = 应用管理端口（压测工具 metricsUrl 依赖）
$missing = @($want | Where-Object { -not (Test-Listening $_) })
Write-Host "[信息] 需要转发的端口：$($want -join ', ')；当前未就绪：$(if ($missing.Count) { $missing -join ', ' } else { '无' })"

if ($missing.Count -gt 0 -and !$ValidateOnly) {
    $forwards = ($missing | ForEach-Object { "-L 127.0.0.1:$_`:127.0.0.1:$_" }) -join ' '
    $nativeArgs = "-i `"$IdentityFile`" -N $forwards -o BatchMode=yes -o ExitOnForwardFailure=yes -o ServerAliveInterval=30 -o ServerAliveCountMax=3 $SshHost"
    Start-Process -FilePath 'ssh.exe' -ArgumentList $nativeArgs -WindowStyle Hidden `
        -RedirectStandardOutput (Join-Path $logHome "ssh-$stamp.out.log") -RedirectStandardError (Join-Path $logHome "ssh-$stamp.err.log") | Out-Null
    $deadline = (Get-Date).AddSeconds(20)
    while ((Get-Date) -lt $deadline -and @($missing | Where-Object { -not (Test-Listening $_) }).Count -gt 0) { Start-Sleep -Milliseconds 500 }
    $still = @($missing | Where-Object { -not (Test-Listening $_) })
    if ($still.Count -gt 0) { throw "隧道建立失败（端口 $($still -join ', ')），见 $logHome\ssh-$stamp.err.log" }
    Write-Host "[OK] SSH 隧道已建立：$($missing -join ', ')"
} elseif ($ValidateOnly -and $missing.Count -gt 0) {
    Write-Host "[校验] 将建立隧道：$($missing -join ', ')"
} else {
    Write-Host '[OK] 隧道端口已全部就绪，无需新建'
}

# 校验 9090 后面确实是云端 Prometheus（本机那个若在跑，规则文件路径是相对/Windows 路径）
$rules = Get-Json 'http://127.0.0.1:9090/api/v1/rules'
if ($null -eq $rules) { throw '9090 不可用：Prometheus 查询失败（隧道没起来或云端未运行）' }
$file = $rules.data.groups[0].file
if ($file -notlike '/etc/prometheus/*') { Write-Warning "9090 后面不是云端实例（规则文件=$file）—— 请确认本机 Prometheus 没在跑" } else { Write-Host "[OK] 9090 已指向云端 Prometheus（规则 $($rules.data.groups[0].rules.Count) 条，文件 $file）" }
$targets = Get-Json 'http://127.0.0.1:9090/api/v1/targets?state=active'
if ($targets) { foreach ($t in $targets.data.activeTargets) { Write-Host ("  [目标] {0} → {1}" -f $t.labels.job, $t.health) } }

if (-not (Test-Listening $GrafanaPort)) {
    if ($ValidateOnly) { Write-Host "[校验] 将启动 Grafana：$grafanaExe" }
    else {
        # stdout 必须一起重定向：Grafana 的日志走 stdout，否则会把调用方控制台刷爆。
        Start-Process -FilePath $grafanaExe -ArgumentList $grafanaArgs -WorkingDirectory $grafanaHome -WindowStyle Hidden `
            -RedirectStandardOutput (Join-Path $logHome "grafana-$stamp.out.log") `
            -RedirectStandardError  (Join-Path $logHome "grafana-$stamp.err.log") | Out-Null
        $deadline = (Get-Date).AddSeconds(90)
        while ((Get-Date) -lt $deadline) {
            if (Test-Listening $GrafanaPort) { $h = Get-Json "http://127.0.0.1:$GrafanaPort/api/health"; if ($h -and $h.database -eq 'ok') { Write-Host "[OK] Grafana 就绪（v$($h.version)）"; break } }
            Start-Sleep -Seconds 2
        }
        if (-not (Test-Listening $GrafanaPort)) { throw "Grafana 启动失败，见 $logHome\grafana-$stamp.err.log" }
    }
} else { Write-Host "[OK] Grafana 已在 $GrafanaPort 端口运行" }

$url = "http://127.0.0.1:$GrafanaPort/d/hainan-capacity"
Write-Host ''
Write-Host "看板地址：$url" -ForegroundColor Green
Write-Host '首次使用请先导入面板：Dashboards → Import → 上传 HainanMaJHong\ops\monitoring\capacity-dashboard.json → 选择 Prometheus → Import（44 个面板）'
Write-Host "日志目录：$logHome"
if (!$NoBrowser -and !$ValidateOnly) { Start-Process $url }