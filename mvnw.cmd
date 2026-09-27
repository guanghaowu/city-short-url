@REM ---------------------------------------------------------------------------
@REM Apache Maven Wrapper (portable, simplified) for Windows
@REM Downloads Maven on first run into %USERPROFILE%\.m2\wrapper\dists, then delegates.
@REM Usage: mvnw.cmd clean compile
@REM ---------------------------------------------------------------------------
@echo off
setlocal enabledelayedexpansion

set BASE_DIR=%~dp0
if "%BASE_DIR:~-1%"=="\" set BASE_DIR=%BASE_DIR:~0,-1%

set PROPS=%BASE_DIR%\.mvn\wrapper\maven-wrapper.properties
if not exist "%PROPS%" (
  echo ERROR: not found: %PROPS%
  exit /b 1
)

@REM 读取 distributionUrl
set DIST_URL=
for /f "usebackq tokens=1,* delims==" %%A in ("%PROPS%") do (
  if "%%A"=="distributionUrl" set DIST_URL=%%B
)
if "%DIST_URL%"=="" (
  echo ERROR: distributionUrl missing in %PROPS%
  exit /b 1
)

@REM 从 URL 解析文件名：apache-maven-3.9.9-bin.zip
for %%F in ("%DIST_URL%") do set DIST_ZIP=%%~nxF
set DIST_NAME=%DIST_ZIP:-bin.zip=%
set DIST_NAME=%DIST_NAME:.zip=%

if "%MAVEN_USER_HOME%"=="" set MAVEN_USER_HOME=%USERPROFILE%\.m2
set DISTS_DIR=%MAVEN_USER_HOME%\wrapper\dists
set MAVEN_HOME=%DISTS_DIR%\%DIST_NAME%

if not exist "%MAVEN_HOME%\bin\mvn.cmd" (
  echo First run: downloading %DIST_URL%
  powershell -NoProfile -Command "$d='%DISTS_DIR%'; New-Item -ItemType Directory -Force -Path $d ^| Out-Null; $z=Join-Path $d '%DIST_ZIP%'; [Net.ServicePointManager]::SecurityProtocol=[Net.SecurityProtocolType]::Tls12; (New-Object Net.WebClient).DownloadFile('%DIST_URL%', $z); Add-Type -AssemblyName System.IO.Compression.FileSystem; [IO.Compression.ZipFile]::ExtractToDirectory($z, $d); Remove-Item $z"
  if errorlevel 1 (
    echo ERROR: download or extract failed
    exit /b 1
  )
)

call "%MAVEN_HOME%\bin\mvn.cmd" %*
