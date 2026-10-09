@echo off
rem ==================================================================
rem  xtlog-server  deploy package builder (Windows)
rem
rem  Prereq: web build + mvn package done  ->  target\xtlog-server.jar
rem  Output: dist\xtlog-server-<ver>\      ready-to-run folder
rem          dist\xtlog-server-<ver>.zip   archive to copy to server
rem
rem  Usage: run from the project root:  scripts\build-dist.bat
rem ==================================================================
setlocal

rem NOTE: this file is intentionally ASCII-only. cmd.exe parses .bat with the
rem system ANSI codepage, so UTF-8 Chinese bytes may turn into control chars.

set "ROOT=%CD%"
if not exist "%ROOT%\target\xtlog-server.jar" (
    if exist "%~dp0..\target\xtlog-server.jar" set "ROOT=%~dp0.."
)
set "JAR=%ROOT%\target\xtlog-server.jar"
set "STAGE_ROOT=%ROOT%\dist"

if not exist "%JAR%" (
    echo [ERROR] not found: %JAR%
    echo         run "npm run build" in web\ first, then "mvn clean package"
    endlocal & exit /b 1
)

rem read project version: the first <version> in pom.xml
set "VER="
for /f "tokens=3 delims=<>" %%v in ('findstr /c:"<version>" "%ROOT%\pom.xml"') do (
    if not defined VER set "VER=%%v"
)
if not defined VER set "VER=0.0.1"

set "STAGE=%STAGE_ROOT%\xtlog-server-%VER%"
echo [build] version = %VER%
echo [build] stage   = %STAGE%

for %%d in ("%STAGE%" "%STAGE%\bin" "%STAGE%\config" "%STAGE%\drivers" "%STAGE%\data" "%STAGE%\logs") do (
    if not exist %%d mkdir %%d
)

rem 注意：若之前是用 dist 目录里的 jar 启动的服务，该文件会被 java 进程锁住，
rem copy / 压缩都会失败（PermissionDenied）。先停服务再出包。
copy /Y "%JAR%" "%STAGE%\xtlog-server.jar" >nul
if errorlevel 1 (
    echo [ERROR] copy jar failed - is the running service using "%STAGE%\xtlog-server.jar"?
    echo         stop it first, then retry.
    popd & endlocal & exit /b 1
)
xcopy /Y /E "%ROOT%\deploy\*" "%STAGE%" >nul

echo [build] stage contents:
dir /b "%STAGE%"

set "ZIP=%STAGE_ROOT%\xtlog-server-%VER%.zip"
powershell -NoProfile -Command ^
  "$ErrorActionPreference='Stop';" ^
  "if (Test-Path '%ZIP%') { Remove-Item -LiteralPath '%ZIP%' -Force };" ^
  "Compress-Archive -Path '%STAGE%\*' -DestinationPath '%ZIP%' -CompressionLevel Optimal;" ^
  "$z = Get-Item '%ZIP%';" ^
  "Write-Host ('[build] zip  = ' + $z.FullName);" ^
  "Write-Host ('[build] size = ' + [math]::Round($z.Length/1MB,2) + ' MB')"

echo [build] done.
endlocal
