@echo off
setlocal

set "APP_HOME=%~dp0"
set "GRADLE_VERSION=8.10.2"
if defined LOCALAPPDATA (
  set "GRADLE_CACHE=%LOCALAPPDATA%\tactical-launcher-gradle-cache"
) else (
  set "GRADLE_CACHE=%TEMP%\tactical-launcher-gradle-cache"
)
set "GRADLE_HOME=%GRADLE_CACHE%\gradle-%GRADLE_VERSION%"
set "GRADLE_ZIP=%TEMP%\gradle-%GRADLE_VERSION%-bin.zip"
set "GRADLE_URL=https://services.gradle.org/distributions/gradle-%GRADLE_VERSION%-bin.zip"

if exist "%GRADLE_HOME%\bin\gradle.bat" goto run_gradle

if not exist "%GRADLE_CACHE%\" (
  powershell -NoProfile -ExecutionPolicy Bypass -Command "[System.IO.Directory]::CreateDirectory('%GRADLE_CACHE%') | Out-Null"
  if errorlevel 1 exit /b 1
)

if not exist "%GRADLE_CACHE%\" (
  echo Unable to create Gradle cache directory: "%GRADLE_CACHE%"
  exit /b 1
)

if exist "%GRADLE_ZIP%" (
  powershell -NoProfile -ExecutionPolicy Bypass -Command "if ((Get-Item -LiteralPath '%GRADLE_ZIP%').Length -lt 1000000) { Remove-Item -LiteralPath '%GRADLE_ZIP%' -Force }"
  if errorlevel 1 exit /b 1
)

if not exist "%GRADLE_ZIP%" (
  curl.exe -L "%GRADLE_URL%" -o "%GRADLE_ZIP%"
  if errorlevel 1 exit /b 1
)

if not exist "%GRADLE_ZIP%" (
  echo Gradle distribution was not downloaded: "%GRADLE_ZIP%"
  exit /b 1
)

tar.exe -xf "%GRADLE_ZIP%" -C "%GRADLE_CACHE%"
if errorlevel 1 exit /b 1

:run_gradle
call "%GRADLE_HOME%\bin\gradle.bat" --project-cache-dir "%GRADLE_CACHE%\project-cache" %*
endlocal
