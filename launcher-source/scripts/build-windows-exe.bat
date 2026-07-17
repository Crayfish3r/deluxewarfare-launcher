@echo off
setlocal EnableExtensions EnableDelayedExpansion

cd /d "%~dp0\.."

if not exist "build-output" mkdir "build-output"
set "LOG_FILE=%CD%\build-output\build.log"

echo ================================================== > "%LOG_FILE%"
echo DeluxeWarfareLauncher Windows EXE build >> "%LOG_FILE%"
echo Started: %DATE% %TIME% >> "%LOG_FILE%"
echo Working directory: %CD% >> "%LOG_FILE%"
echo ================================================== >> "%LOG_FILE%"
echo.

if not exist "build.gradle" (
  echo ERROR: build.gradle was not found in "%CD%".
  echo ERROR: build.gradle was not found in "%CD%". >> "%LOG_FILE%"
  goto fail
)

where java >nul 2>nul
if errorlevel 1 (
  echo ERROR: Java JDK 17 or newer is required.
  echo ERROR: Java JDK 17 or newer is required. >> "%LOG_FILE%"
  echo Install JDK 17 or JDK 21, then run this script again.
  goto fail
)

for /f "delims=" %%F in ('where java') do (
  if not defined JAVA_EXE set "JAVA_EXE=%%F"
)

if not defined JAVA_EXE (
  echo ERROR: Unable to resolve java.exe path.
  echo ERROR: Unable to resolve java.exe path. >> "%LOG_FILE%"
  goto fail
)

where jpackage >nul 2>nul
if errorlevel 1 (
  echo ERROR: jpackage was not found.
  echo ERROR: jpackage was not found. >> "%LOG_FILE%"
  echo Install a full JDK 17 or newer, not only JRE.
  echo Usually this means JAVA_HOME/PATH points to JRE instead of JDK.
  goto fail
)

for /f "delims=" %%F in ('where jpackage') do (
  if not defined JPACKAGE_EXE set "JPACKAGE_EXE=%%F"
)

for %%F in ("%JPACKAGE_EXE%") do (
  if exist "%%~dpFjava.exe" set "JAVA_EXE=%%~dpFjava.exe"
)

echo Java:
java -version 2>&1
echo. >> "%LOG_FILE%"
echo Java: >> "%LOG_FILE%"
java -version >> "%LOG_FILE%" 2>&1

echo.
echo jpackage:
jpackage --version
echo. >> "%LOG_FILE%"
echo jpackage: >> "%LOG_FILE%"
jpackage --version >> "%LOG_FILE%" 2>&1

echo.
echo Building Java distribution...
echo Building Java distribution... >> "%LOG_FILE%"
call gradlew.bat clean installDist --stacktrace >> "%LOG_FILE%" 2>&1
if errorlevel 1 (
  echo ERROR: Gradle build failed.
  goto fail
)

set "APP_LIB_DIR=build\install\TacticalLauncher\lib"
set "MAIN_JAR="

for %%F in ("%APP_LIB_DIR%\TacticalLauncher-*.jar") do (
  set "JAR_NAME=%%~nxF"
  echo !JAR_NAME! | findstr /I /C:"-sources" /C:"-javadoc" >nul
  if errorlevel 1 (
    set "MAIN_JAR=%%~nxF"
  )
)

if not defined MAIN_JAR (
  echo ERROR: Main jar was not found in "%APP_LIB_DIR%".
  echo ERROR: Main jar was not found in "%APP_LIB_DIR%". >> "%LOG_FILE%"
  goto fail
)

echo Main jar: %MAIN_JAR%
echo Main jar: %MAIN_JAR% >> "%LOG_FILE%"

if exist "build-output\DeluxeWarfareLauncher" (
  rmdir /s /q "build-output\DeluxeWarfareLauncher" >> "%LOG_FILE%" 2>&1
)

echo.
echo Creating Windows app image...
echo Creating Windows app image... >> "%LOG_FILE%"
jpackage ^
  --type app-image ^
  --name DeluxeWarfareLauncher ^
  --input "%APP_LIB_DIR%" ^
  --main-jar "%MAIN_JAR%" ^
  --main-class "com.makar.launcher.Main" ^
  --icon "assets\DW_launcher_icon.ico" ^
  --dest "build-output" ^
  --java-options "-Xmx512m" >> "%LOG_FILE%" 2>&1

if errorlevel 1 (
  echo ERROR: jpackage failed.
  goto fail
)

echo.
echo Adding Java executable for Minecraft...
echo Adding Java executable for Minecraft... >> "%LOG_FILE%"
copy /y "%JAVA_EXE%" "build-output\DeluxeWarfareLauncher\runtime\bin\java.exe" >> "%LOG_FILE%" 2>&1
if errorlevel 1 (
  echo ERROR: Unable to add runtime\bin\java.exe.
  goto fail
)

for %%F in ("%JAVA_EXE%") do (
  if exist "%%~dpFjavaw.exe" (
    copy /y "%%~dpFjavaw.exe" "build-output\DeluxeWarfareLauncher\runtime\bin\javaw.exe" >> "%LOG_FILE%" 2>&1
  )
)

echo Embedded Java:
"build-output\DeluxeWarfareLauncher\runtime\bin\java.exe" -version
echo. >> "%LOG_FILE%"
echo Embedded Java: >> "%LOG_FILE%"
"build-output\DeluxeWarfareLauncher\runtime\bin\java.exe" -version >> "%LOG_FILE%" 2>&1
if errorlevel 1 (
  echo ERROR: Embedded runtime\bin\java.exe could not be started.
  goto fail
)

powershell -NoProfile -ExecutionPolicy Bypass -Command "$release = [IO.File]::ReadAllText('build-output\DeluxeWarfareLauncher\runtime\release'); $match = [regex]::Match($release, 'JAVA_VERSION=.(?<major>[0-9]+)'); if (-not $match.Success -or [int]$match.Groups['major'].Value -lt 17) { exit 1 }"
if errorlevel 1 (
  echo ERROR: Embedded runtime\bin\java.exe must be Java 17 or newer.
  echo ERROR: Embedded runtime\bin\java.exe must be Java 17 or newer. >> "%LOG_FILE%"
  goto fail
)

echo.
echo DONE.
echo EXE:
echo %CD%\build-output\DeluxeWarfareLauncher\DeluxeWarfareLauncher.exe
echo.
echo Full log:
echo %LOG_FILE%
echo.
pause
exit /b 0

:fail
echo.
echo Build failed.
echo Full log:
echo %LOG_FILE%
echo.
echo Last log lines:
powershell -NoProfile -ExecutionPolicy Bypass -Command "if (Test-Path -LiteralPath '%LOG_FILE%') { Get-Content -LiteralPath '%LOG_FILE%' -Tail 80 }"
echo.
pause
exit /b 1
