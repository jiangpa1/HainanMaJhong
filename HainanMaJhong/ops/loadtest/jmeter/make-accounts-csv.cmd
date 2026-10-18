@echo off
rem 生成 JMeter 用的账号 CSV（来源：docs\performance\private\accounts.json，仅本机、不入库）
setlocal
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0make-accounts-csv.ps1" %*
if errorlevel 1 pause
endlocal
