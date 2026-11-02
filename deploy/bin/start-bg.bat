@echo off
rem ------------------------------------------------------------------
rem  xtlog-server launcher (Windows, background).
rem  Log goes to logs\console.log, pid is stored in data\xtlog.pid
rem  Stop it with bin\stop.bat
rem
rem  NOTE: ASCII-only on purpose - cmd.exe parses .bat with the ANSI
rem  codepage and UTF-8 Chinese bytes can break the command line.
rem ------------------------------------------------------------------
setlocal

set "APP_HOME=%~dp0.."
if not exist "%APP_HOME%\xtlog-server.jar" set "APP_HOME=%CD%"

pushd "%APP_HOME%"
set "APP_HOME=%CD%"

if defined JAVA_HOME (
    set "JAVA_EXE=%JAVA_HOME%\bin\javaw.exe"
) else (
    set "JAVA_EXE=javaw.exe"
)

if not defined XTLOG_DATA_DIR set "XTLOG_DATA_DIR=%APP_HOME%\data"

if not exist "%APP_HOME%\logs" mkdir "%APP_HOME%\logs"
if not exist "%XTLOG_DATA_DIR%" mkdir "%XTLOG_DATA_DIR%"

if exist "%XTLOG_DATA_DIR%\xtlog.pid" (
    set /p OLD_PID=<"%XTLOG_DATA_DIR%\xtlog.pid"
    tasklist /FI "PID eq %OLD_PID%" 2>nul | find "%OLD_PID%" >nul
    if not errorlevel 1 (
        echo [xtlog] already running, pid=%OLD_PID%
        popd & endlocal & exit /b 0
    )
)

echo [xtlog] APP_HOME = %APP_HOME%
echo [xtlog] DATA_DIR = %XTLOG_DATA_DIR%
echo [xtlog] starting in background ...

powershell -NoProfile -Command ^
  "$ErrorActionPreference='Stop';" ^
  "$a = [System.Collections.Generic.List[string]]::new();" ^
  "foreach($x in @('-Xms512m','-Xmx2g','-Dfile.encoding=UTF-8','-Dsun.stdout.encoding=UTF-8','-Dsun.stderr.encoding=UTF-8','-Dxtlog.data.dir=%XTLOG_DATA_DIR%')) { $a.Add($x) };" ^
  "if ('%JAVA_OPTS%'.Trim()) { foreach($x in ('%JAVA_OPTS%'.Trim() -split '\s+')) { $a.Add($x) } };" ^
  "$a.Add('-cp'); $a.Add('%APP_HOME%\config;%APP_HOME%\xtlog-server.jar;%APP_HOME%\drivers\*');" ^
  "$a.Add('top.xtcoder.xtlog.XtLogApplication');" ^
  "$p = Start-Process -FilePath '%JAVA_EXE%' -WorkingDirectory '%APP_HOME%' -ArgumentList $a -PassThru -WindowStyle Hidden" ^
  " -RedirectStandardOutput '%APP_HOME%\logs\console.log' -RedirectStandardError '%APP_HOME%\logs\console.err.log';" ^
  "Set-Content -Path '%XTLOG_DATA_DIR%\xtlog.pid' -Value $p.Id -Encoding ascii;" ^
  "Write-Host ('[xtlog] started, pid=' + $p.Id);" ^
  "Write-Host '[xtlog] log file: %APP_HOME%\logs\console.log'"

popd
endlocal
