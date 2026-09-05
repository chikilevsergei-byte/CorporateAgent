@echo off
chcp 65001 >nul
REM ============================================================
REM  CorporateAgent v3 - подготовка ПОРТАБЕЛЬНОГО дистрибутива
REM
REM  Создаёт папку dist\CorporateAgent_portable со структурой:
REM    jre\                    - встроенная JRE (если найдена)
REM    lib\                    - библиотеки из lib\
REM    CorporateAgent.jar      - собранное приложение
REM    config.json             - настройки
REM    databases\ backups\ output\  - рабочие папки
REM    Start.bat               - портабельный запуск
REM
REM  ПЕРЕД ЗАПУСКОМ:
REM   1) Выполните build.bat (соберёт jar)
REM   2) Положите библиотеки в lib\ (sqlite-jdbc, poi, pdfbox)
REM   3) При необходимости укажите путь к JRE ниже
REM ============================================================

setlocal
cd /d "%~dp0"

REM --- Путь к JRE для копирования (измените при необходимости) ---
set JRE_SOURCE=C:\Program Files (x86)\Java\jre1.8.0_471

set PORTABLE=dist\CorporateAgent_portable

echo.
echo === Подготовка портабельного дистрибутива ===
echo.

if not exist "dist\CorporateAgent.jar" (
    echo [ОШИБКА] dist\CorporateAgent.jar не найден.
    echo Сначала запустите build.bat
    pause
    exit /b 1
)

REM --- Создаём структуру ---
if exist "%PORTABLE%" rmdir /s /q "%PORTABLE%"
mkdir "%PORTABLE%"
mkdir "%PORTABLE%\databases"
mkdir "%PORTABLE%\backups"
mkdir "%PORTABLE%\output"
mkdir "%PORTABLE%\lib"

REM --- Копируем jar ---
copy /y "dist\CorporateAgent.jar" "%PORTABLE%\CorporateAgent.jar" >nul
echo [OK] CorporateAgent.jar скопирован

REM --- Копируем библиотеки ---
if exist lib (
    copy /y lib\*.jar "%PORTABLE%\lib\" >nul 2>&1
    echo [OK] Библиотеки скопированы из lib\
) else (
    echo [!] Папка lib\ пуста. Добавьте библиотеки вручную.
)

REM --- Копируем config.json (если есть) ---
if exist "config.json" (
    copy /y "config.json" "%PORTABLE%\config.json" >nul
    echo [OK] config.json скопирован
) else (
    echo [!] config.json не найден - создайте его вручную с API-ключом.
)

REM --- Копируем JRE ---
if exist "%JRE_SOURCE%\bin\java.exe" (
    echo Копирование JRE из %JRE_SOURCE% (может занять время)...
    xcopy "%JRE_SOURCE%" "%PORTABLE%\jre\" /e /i /q /y >nul
    echo [OK] JRE скопирована
) else (
    echo [!] JRE не найдена по пути: %JRE_SOURCE%
    echo     Скопируйте папку JRE вручную в "%PORTABLE%\jre"
)

REM --- Копируем портабельный запуск ---
copy /y "run_portable.bat" "%PORTABLE%\Start.bat" >nul
echo [OK] Start.bat создан

echo.
echo === Готово! ===
echo Портабельный дистрибутив: %PORTABLE%
echo.
echo Для запуска у конечного пользователя без установки Java:
echo   откройте папку и запустите Start.bat
echo.
pause
