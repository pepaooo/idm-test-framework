@echo off
rem idmtest launcher: uses IDM_TEST_JAVA_HOME when set, otherwise the bundled runtime.
setlocal EnableExtensions DisableDelayedExpansion
for %%I in ("%~dp0..") do set "HOME_DIR=%%~fI"

set "JAVA_DIR=%HOME_DIR%\runtime"
rem Quotes are removed while the value is expanded, so a quoted IDM_TEST_JAVA_HOME works too.
if defined IDM_TEST_JAVA_HOME set "JAVA_DIR=%IDM_TEST_JAVA_HOME:"=%"
set "RELEASE=%JAVA_DIR%\release"
if not exist "%RELEASE%" goto notjava
if not exist "%JAVA_DIR%\bin\java.exe" goto notjava

rem JAVA_VERSION="25.0.3" or "26-ea": the major number is the second token.
set "VERSION="
for /f "tokens=2 delims==.-" %%V in ('findstr /b "JAVA_VERSION=" "%RELEASE%"') do set "VERSION=%%V"
if not defined VERSION goto unknown
set "VERSION=%VERSION:"=%"
if not defined VERSION goto unknown
echo %VERSION%| findstr /r /x "[0-9][0-9]*" >nul || goto unknown
if %VERSION% LSS 25 goto oldjava

set "JAR="
for %%J in ("%HOME_DIR%\lib\idm-test-runner-*.jar") do if not defined JAR set "JAR=%%~fJ"
if not defined JAR goto nojar
"%JAVA_DIR%\bin\java" "-Dloader.path=%HOME_DIR%\lib\drivers" -jar "%JAR%" %*
exit /b %ERRORLEVEL%

rem The messages expand paths with !...! so characters such as & in a path are printed, not run.
:notjava
if not defined IDM_TEST_JAVA_HOME goto broken
setlocal EnableDelayedExpansion
>&2 echo(IDM_TEST_JAVA_HOME points to !JAVA_DIR!, which is not a Java installation; idmtest needs Java 25 or newer
exit /b 2

:unknown
set "VERSION=unknown"
:oldjava
if not defined IDM_TEST_JAVA_HOME goto broken
setlocal EnableDelayedExpansion
>&2 echo(IDM_TEST_JAVA_HOME points to Java !VERSION! (!JAVA_DIR!); idmtest needs Java 25 or newer
exit /b 2

rem A bundled runtime that fails a check is a broken install, not the user's choice of Java.
:broken
setlocal EnableDelayedExpansion
>&2 echo(the bundled Java runtime in !JAVA_DIR! is missing or broken; reinstall idmtest or set IDM_TEST_JAVA_HOME to a Java 25+
exit /b 2

:nojar
setlocal EnableDelayedExpansion
>&2 echo(idmtest is not installed correctly: lib/idm-test-runner-*.jar not found in !HOME_DIR!
exit /b 2
