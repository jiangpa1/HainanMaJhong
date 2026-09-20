@echo off
rem ===========================================================================
rem  run-local.cmd - wrapper so you can launch without touching the PowerShell
rem  execution policy (.cmd files are not subject to it).
rem
rem  Usage:  run-local.cmd            -> start the app
rem          run-local.cmd -Probe     -> reachability check only
rem ===========================================================================
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0run-local.ps1" %*
