@echo off
rem ------------------------------------------------------------------
rem  xtlog-server launcher (Windows, foreground). Ctrl+C to stop.
rem  For background mode use bin\start-bg.bat
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
    set "JAVA_EXE=%JAVA_HOME%\bin\java.exe"
) else (
    set "JAVA_EXE=java"
)

rem data dir: default <install>/data, override with env XTLOG_DATA_DIR
if not defined XTLOG_DATA_DIR set "XTLOG_DATA_DIR=%APP_HOME%\data"

if not exist "%APP_HOME%\logs" mkdir "%APP_HOME%\logs"
if not exist "%XTLOG_DATA_DIR%" mkdir "%XTLOG_DATA_DIR%"

rem extra JVM flags can be appended via env JAVA_OPTS, e.g. set JAVA_OPTS=-Xmx2g
set "JVM_OPTS=-Xms256m -Xmx512m -Dfile.encoding=UTF-8 -Dsun.stdout.encoding=UTF-8 -Dsun.stderr.encoding=UTF-8 -Dxtlog.data.dir=%XTLOG_DATA_DIR%"

echo [xtlog] APP_HOME = %APP_HOME%
echo [xtlog] DATA_DIR = %XTLOG_DATA_DIR%
echo [xtlog] JAVA_EXE = %JAVA_EXE%
echo [xtlog] starting ... Ctrl+C to stop

"%JAVA_EXE%" %JVM_OPTS% %JAVA_OPTS% -cp "%APP_HOME%\config;%APP_HOME%\xtlog-server.jar;%APP_HOME%\drivers\*" top.xtcoder.xtlog.XtLogApplication

popd
endlocal
