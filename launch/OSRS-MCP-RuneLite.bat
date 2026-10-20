@echo off
setlocal EnableExtensions
cd /d "%~dp0.."

rem Launches RuneLite with the OSRS MCP plugin built in.
rem Extra arguments are passed to the RuneLite client.
rem Set OSRS_MCP_DRY_RUN=1 to print the final command instead of starting the client.

set "JAR=build\libs\osrs-mcp-runelite.jar"

where java >nul 2>nul
if errorlevel 1 (
  echo Java was not found. Install Java 11 or newer from:
  echo   https://adoptium.net/temurin/releases/
  pause
  exit /b 1
)

set "JMAJOR="
for /f "tokens=3" %%v in ('java -version 2^>^&1 ^| findstr /i "version"') do if not defined JMAJOR set "JMAJOR=%%~v"
for /f "tokens=1,2 delims=." %%a in ("%JMAJOR%") do (
  set "JMAJOR=%%a"
  if "%%a"=="1" set "JMAJOR=%%b"
)
for /f "tokens=1 delims=-+_" %%a in ("%JMAJOR%") do set "JMAJOR=%%a"
set /a JNUM=%JMAJOR% 2>nul
if %JNUM% LSS 11 (
  echo Java 11 or newer is required ^(found "%JMAJOR%"^). Install it from:
  echo   https://adoptium.net/temurin/releases/
  pause
  exit /b 1
)

if not "%OSRS_MCP_DRY_RUN%"=="1" if exist ".git" (
  where git >nul 2>nul
  if not errorlevel 1 (
    git pull --ff-only >nul 2>nul
    if errorlevel 1 (echo Note: could not update the plugin source ^(git pull skipped^).) else (echo Plugin source is up to date.)
  )
)

echo Building ^(this also picks up the latest RuneLite^)...
call "%~dp0..\gradlew.bat" -q shadowJar
if errorlevel 1 (
  echo.
  echo Build failed. Check your internet connection and that Java 11+ is installed,
  echo then try again. If it keeps failing, re-download the project from GitHub.
  pause
  exit /b 1
)

if not exist "%JAR%" (
  echo Expected %JAR% was not produced.
  pause
  exit /b 1
)

set "JRUN=javaw"
where javaw >nul 2>nul
if errorlevel 1 set "JRUN=java"

if "%OSRS_MCP_DRY_RUN%"=="1" (
  echo DRY RUN: start "" %JRUN% -jar %JAR% %*
  exit /b 0
)

start "" %JRUN% -jar "%JAR%" %*
exit /b 0
