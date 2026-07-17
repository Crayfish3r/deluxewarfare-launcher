@echo off
setlocal EnableExtensions
cd /d "%~dp0\.."

if not exist "build-output" mkdir "build-output"
set "LOG_FILE=%CD%\build-output\run-dev.log"

echo Running launcher from source... > "%LOG_FILE%"
echo Working directory: %CD% >> "%LOG_FILE%"

if not exist "build.gradle" (
  echo ERROR: build.gradle was not found in "%CD%".
  pause
  exit /b 1
)

call gradlew.bat run --stacktrace >> "%LOG_FILE%" 2>&1
if errorlevel 1 (
  echo.
  echo Run failed. Log:
  echo %LOG_FILE%
  powershell -NoProfile -ExecutionPolicy Bypass -Command "if (Test-Path -LiteralPath '%LOG_FILE%') { Get-Content -LiteralPath '%LOG_FILE%' -Tail 80 }"
  pause
  exit /b 1
)

endlocal
