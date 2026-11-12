@echo off
setlocal
cd /d "%~dp0"
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0launcher.ps1"
set "CA_EXIT=%errorlevel%"
if not "%CA_EXIT%"=="0" pause
exit /b %CA_EXIT%
