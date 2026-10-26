@echo off
setlocal DisableDelayedExpansion
set "RUNTIME=%~1"
if not defined RUNTIME set "RUNTIME=jdk21"
if "%RUNTIME%"=="jdk8" goto collect
if "%RUNTIME%"=="jdk21" goto collect
echo Usage: %~nx0 [jdk8^|jdk21] [--config file^|--help^|--version^|--status] 1>&2
exit /b 2
:collect
set "APP_ARGS="
:next
shift
if "%~1"=="" goto run
set "APP_ARGS=%APP_ARGS% "%~1""
goto next
:run
if not defined APP_ARGS set "APP_ARGS=--config "%~dp0..\config\LoGreedy.properties""
set "JAVA=java"
if defined JAVA_HOME set "JAVA=%JAVA_HOME%\bin\java.exe"
"%JAVA%" %JAVA_OPTS% -jar "%~dp0..\build\distributions\all\LoGreedy-%RUNTIME%.jar" %APP_ARGS%
exit /b %ERRORLEVEL%
