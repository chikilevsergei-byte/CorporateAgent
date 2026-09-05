@echo off
chcp 65001 >nul
REM ============================================================
REM  CorporateAgent v3 - ПОРТАБЕЛЬНЫЙ ЗАПУСК
REM  Запускает приложение из папки портабельного дистрибутива
REM  с использованием встроенной JRE (без установки Java).
REM
REM  Структура портабельной папки (dist\CorporateAgent_portable):
REM    jre\                    - встроенная Java Runtime
REM    lib\                    - библиотеки (sqlite-jdbc, poi, pdfbox)
REM    CorporateAgent.jar      - собранное приложение
REM    config.json             - настройки (API-ключ DeepSeek)
REM    databases\ backups\ output\  - рабочие папки
REM    Start.bat               - этот файл
REM ============================================================

setlocal
cd /d "%~dp0"

REM --- Базовая папка без завершающего обратного слэша ---
set "BASE=%~dp0"
if "%BASE:~-1%"=="\" set "BASE=%BASE:~0,-1%"

REM --- Определяем папку с JRE ---
set JRE_DIR=jre
if not exist "%JRE_DIR%\bin\java.exe" (
    REM Пробуем стандартные пути
    if exist "jre8\bin\java.exe" set "JRE_DIR=jre8"
    if exist "runtime\bin\java.exe" set "JRE_DIR=runtime"
)

if not exist "%JRE_DIR%\bin\java.exe" (
    echo.
    echo [ОШИБКА] Встроенная JRE не найдена в папке "%JRE_DIR%".
    echo.
    echo Пожалуйста, скопируйте папку JRE (например, из
    echo "C:\Program Files\Java\jre1.8.0_471") в папку "%JRE_DIR%"
    echo рядом с этим файлом.
    echo.
    pause
    exit /b 1
)

if not exist "CorporateAgent.jar" (
    echo.
    echo [ОШИБКА] CorporateAgent.jar не найден.
    echo Скопируйте собранный jar в эту папку.
    echo.
    pause
    exit /b 1
)

REM --- Classpath: jar + библиотеки из lib\ ---
set CP=CorporateAgent.jar
if exist lib (
    for %%f in (lib\*.jar) do call set "CP=%%CP%%;%%f"
)

echo Запуск CorporateAgent v3 (портабельная версия)...
echo.
"%JRE_DIR%\bin\java.exe" -Dfile.encoding=UTF-8 -Dcorpagent.base="%BASE%" -cp "%CP%" corpagent.App

if errorlevel 1 (
    echo.
    echo [ОШИБКА] Не удалось запустить приложение.
    pause
)
