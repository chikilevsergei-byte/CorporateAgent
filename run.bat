@echo off
chcp 65001 >nul
REM ============================================================
REM  CorporateAgent v3 - запуск (режим разработчика)
REM  Требует установленную Java (java в PATH) и собранный jar
REM  (запустите build.bat перед первым запуском).
REM ============================================================

setlocal
cd /d "%~dp0"

REM --- Базовая папка без завершающего обратного слэша ---
set "BASE=%~dp0"
if "%BASE:~-1%"=="\" set "BASE=%BASE:~0,-1%"

if not exist "dist\CorporateAgent.jar" (
    echo.
    echo Jar не найден. Сначала запустите build.bat
    echo.
    pause
    exit /b 1
)

REM --- Classpath: jar + все библиотеки из lib\ ---
set CP=dist\CorporateAgent.jar
if exist lib (
    for %%f in (lib\*.jar) do call set "CP=%%CP%%;%%f"
)

echo Запуск CorporateAgent v3...
echo.
REM Используем 64-битный JDK 1.8.0_504 (полный путь), а НЕ java из PATH,
REM который указывает на 32-битный JRE 1.8.0_471 Client VM с проблемами
REM отрисовки текста в Swing на Windows 10.
REM -Dsun.java2d.d3d=false отключает Direct3D-конвейер (проблема невидимого текста).
set "JAVA=C:\Users\sergey.chikilev\jdk8_extract\jdk8u504-b01\bin\java.exe"
if not exist "%JAVA%" (
    echo.
    echo [ОШИБКА] Не найден 64-битный JDK по пути:
    echo %JAVA%
    echo Укажите правильный путь к java.exe в run.bat.
    pause
    exit /b 1
)
"%JAVA%" -Dsun.java2d.d3d=false -Dfile.encoding=UTF-8 -Dcorpagent.base="%BASE%" -cp "%CP%" corpagent.App

if errorlevel 1 (
    echo.
    echo [ОШИБКА] Не удалось запустить приложение.
    echo Убедитесь, что Java установлена и доступна в PATH.
    pause
)
