@echo off
rem idmtest launcher: uses IDM_TEST_JAVA_HOME when set, otherwise the bundled runtime.
setlocal EnableExtensions DisableDelayedExpansion
for %%I in ("%~dp0..") do set "HOME_DIR=%%~fI"

set "JAVA_DIR=%HOME_DIR%\runtime"
if defined IDM_TEST_JAVA_HOME set "JAVA_DIR=%IDM_TEST_JAVA_HOME%"
set "RELEASE=%JAVA_DIR%\release"
if not exist "%RELEASE%" goto notjava
if not exist "%JAVA_DIR%\bin\java.exe" goto notjava

set "VERSION="
for /f "tokens=2 delims==." %%V in ('findstr /b "JAVA_VERSION=" "%RELEASE%"') do set "VERSION=%%V"
if not defined VERSION goto unknown
set "VERSION=%VERSION:"=%"
if not defined VERSION goto unknown
echo %VERSION%| findstr /r /x "[0-9][0-9]*" >nul || goto unknown
if %VERSION% LSS 25 goto oldjava

set "JAR="
for %%J in ("%HOME_DIR%\lib\idm-test-runner-*.jar") do if not defined JAR set "JAR=%%~fJ"
"%JAVA_DIR%\bin\java" "-Dloader.path=%HOME_DIR%\lib\drivers" -jar "%JAR%" %*
exit /b %ERRORLEVEL%

:notjava
>&2 echo IDM_TEST_JAVA_HOME points to %JAVA_DIR%, which is not a Java installation; idmtest needs Java 25 or newer
exit /b 2

:unknown
set "VERSION=unknown"
:oldjava
>&2 echo IDM_TEST_JAVA_HOME points to Java %VERSION% (%JAVA_DIR%); idmtest needs Java 25 or newer
exit /b 2
