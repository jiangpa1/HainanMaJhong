@echo off
setlocal
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0start-monitoring.ps1" %*
if errorlevel 1 (
  echo.
  echo Monitoring startup failed. Read the error above.
  pause
)
endlocal
