@echo off
chcp 65001 >nul
REM ============================================================
REM  CorporateAgent v3 - build script
REM  Compiles Java sources into dist\classes and builds
REM  executable jar dist\CorporateAgent.jar
REM
REM  REQUIREMENTS:
REM   - JDK installed (javac) - auto-detected via JAVA_HOME,
REM     PATH or known install directories
REM   - Libraries in lib\ folder (sqlite-jdbc.jar, poi*.jar,
REM     pdfbox*.jar etc.)
REM ============================================================

setlocal
cd /d "%~dp0"

set SRC_DIR=src
set OUT_DIR=dist\classes
set JAR_NAME=dist\CorporateAgent.jar
set MAIN_CLASS=corpagent.App

echo.
echo === CorporateAgent v3: build ===
echo.

REM ============================================================
REM  Auto-detect JDK (javac)
REM  Search order:
REM    1. JAVA_HOME (if set and contains bin\javac.exe)
REM    2. javac in PATH
REM    3. Known JDK install directories
REM ============================================================
set "JAVAC="
set "JAR="

REM --- 1. JAVA_HOME ---
if not defined JAVA_HOME goto :no_java_home
if not exist "%JAVA_HOME%\bin\javac.exe" goto :no_java_home
set "JAVAC=%JAVA_HOME%\bin\javac.exe"
set "JAR=%JAVA_HOME%\bin\jar.exe"
goto :jdk_found
:no_java_home

REM --- 2. javac in PATH ---
where javac >nul 2>nul
if errorlevel 1 goto :no_javac_path
set "JAVAC=javac"
set "JAR=jar"
goto :jdk_found
:no_javac_path

REM --- 3. Known JDK install directories ---
if exist "C:\Program Files\Java\jdk8_extract\jdk8u504-b01\bin\javac.exe" goto :use_pf1
if exist "%USERPROFILE%\jdk8_extract\jdk8u504-b01\bin\javac.exe" goto :use_up
if exist "C:\Program Files\Java\jdk-8\bin\javac.exe" goto :use_pf2
if exist "C:\Program Files\Java\jdk1.8.0_471\bin\javac.exe" goto :use_pf3
if exist "C:\Program Files\Eclipse Adoptium\jdk-8\bin\javac.exe" goto :use_pf4
if exist "C:\Program Files\Microsoft\jdk-8\bin\javac.exe" goto :use_pf5
goto :jdk_found

:use_pf1
set "JAVAC=C:\Program Files\Java\jdk8_extract\jdk8u504-b01\bin\javac.exe"
set "JAR=C:\Program Files\Java\jdk8_extract\jdk8u504-b01\bin\jar.exe"
goto :jdk_found

:use_up
set "JAVAC=%USERPROFILE%\jdk8_extract\jdk8u504-b01\bin\javac.exe"
set "JAR=%USERPROFILE%\jdk8_extract\jdk8u504-b01\bin\jar.exe"
goto :jdk_found

:use_pf2
set "JAVAC=C:\Program Files\Java\jdk-8\bin\javac.exe"
set "JAR=C:\Program Files\Java\jdk-8\bin\jar.exe"
goto :jdk_found

:use_pf3
set "JAVAC=C:\Program Files\Java\jdk1.8.0_471\bin\javac.exe"
set "JAR=C:\Program Files\Java\jdk1.8.0_471\bin\jar.exe"
goto :jdk_found

:use_pf4
set "JAVAC=C:\Program Files\Eclipse Adoptium\jdk-8\bin\javac.exe"
set "JAR=C:\Program Files\Eclipse Adoptium\jdk-8\bin\jar.exe"
goto :jdk_found

:use_pf5
set "JAVAC=C:\Program Files\Microsoft\jdk-8\bin\javac.exe"
set "JAR=C:\Program Files\Microsoft\jdk-8\bin\jar.exe"
goto :jdk_found

:jdk_found

REM --- Check that JDK was found ---
if defined JAVAC goto :jdk_ok
echo.
echo [ERROR] Java compiler (javac.exe) not found.
echo.
echo A JDK (not just a JRE) is required to build.
echo Install a JDK (e.g. OpenJDK Temurin 8 or newer) and
echo set the JAVA_HOME environment variable, or add the
echo JDK bin folder to PATH.
echo.
pause
exit /b 1
:jdk_ok

echo Using JDK: %JAVAC%
echo.

REM --- Build classpath from all jars in lib\ ---
set CP=.
if exist lib (
    for %%f in (lib\*.jar) do call set "CP=%%CP%%;%%f"
)

echo Classpath: %CP%
echo.

REM --- Remove old classes folder ---
if exist "%OUT_DIR%" rmdir /s /q "%OUT_DIR%"
mkdir "%OUT_DIR%"

REM --- Compile ---
echo Compiling sources...
"%JAVAC%" -encoding UTF-8 -cp "%CP%" -d "%OUT_DIR%" ^
    %SRC_DIR%\corpagent\util\*.java ^
    %SRC_DIR%\corpagent\data\*.java ^
    %SRC_DIR%\corpagent\db\*.java ^
    %SRC_DIR%\corpagent\llm\*.java ^
    %SRC_DIR%\corpagent\files\*.java ^
    %SRC_DIR%\corpagent\tools\*.java ^
    %SRC_DIR%\corpagent\gui\*.java ^
    %SRC_DIR%\corpagent\*.java ^
    %SRC_DIR%\corpagent\App.java

if errorlevel 1 (
    echo.
    echo [ERROR] Compilation failed.
    pause
    exit /b 1
)

echo Compilation successful.
echo.

REM ============================================================
REM  Copy preset resources (skills/tools/databases JSON) into the
REM  classes folder so they end up on the classpath root of the jar
REM  at /presets/... (PresetLoader reads via getResourceAsStream).
REM ============================================================
if exist "%SRC_DIR%\corpagent\data\presets" (
    if not exist "%OUT_DIR%\presets" mkdir "%OUT_DIR%\presets"
    echo Copying preset resources into jar classpath root...
    xcopy /e /i /y "%SRC_DIR%\corpagent\data\presets" "%OUT_DIR%\presets" >nul
    echo Preset resources copied to %OUT_DIR%\presets
) else (
    echo [WARN] Preset folder not found: %SRC_DIR%\corpagent\data\presets
)

REM --- Create manifest ---
echo Main-Class: %MAIN_CLASS%> dist\MANIFEST.MF
echo.>> dist\MANIFEST.MF

REM --- Build jar ---
echo Building jar...
cd /d "%OUT_DIR%"
"%JAR%" cfm "..\CorporateAgent.jar" "..\MANIFEST.MF" .
cd /d "%~dp0"

if errorlevel 1 (
    echo.
    echo [ERROR] Failed to build jar.
    pause
    exit /b 1
)

echo.
echo === Done! ===
echo Jar created: %JAR_NAME%
echo.
echo To run use run.bat (needs Java) or
echo run_portable.bat (portable version with JRE).
echo.
pause
