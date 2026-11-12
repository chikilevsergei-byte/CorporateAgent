@echo off
setlocal
cd /d "%~dp0"
"%~dp0jre\bin\java.exe" -Dfile.encoding=UTF-8 -cp "%~dp0launcher.jar;%~dp0dist\CorporateAgent.jar" PortableLauncher "%~dp0." --stop
if errorlevel 1 pause
