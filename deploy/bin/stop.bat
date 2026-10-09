@echo off
rem ------------------------------------------------------------------
rem  xtlog-server stopper (Windows): kills the pid in data\xtlog.pid
rem  NOTE: ASCII-only on purpose, see start.bat
rem ------------------------------------------------------------------
setlocal

set "APP_HOME=%~dp0.."
if not exist "%APP_HOME%\xtlog-server.jar" set "APP_HOME=%CD%"

pushd "%APP_HOME%"
set "APP_HOME=%CD%"

if not defined XTLOG_DATA_DIR set "XTLOG_DATA_DIR=%APP_HOME%\data"

if not exist "%XTLOG_DATA_DIR%\xtlog.pid" (
    echo [xtlog] pid file not found: %XTLOG_DATA_DIR%\xtlog.pid
    popd & endlocal & exit /b 0
)

set /p PID=<"%XTLOG_DATA_DIR%\xtlog.pid"
if "%PID%"=="" (
    echo [xtlog] pid file is empty
    popd & endlocal & exit /b 0
)

taskkill /PID %PID% /T /F
del /Q "%XTLOG_DATA_DIR%\xtlog.pid"
echo [xtlog] stopped, pid=%PID%

popd
endlocal
