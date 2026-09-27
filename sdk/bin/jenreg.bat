@echo off
rem The jenreg command as its release archive installs it: the client's launcher jar under lib\, run by a Java 25 or newer
rem runtime from JAVA_HOME or the PATH.
setlocal EnableDelayedExpansion
for %%i in ("%~dp0..") do set "CLI_HOME=%%~fi"

set "JAVA="
if defined JAVA_HOME (
    if exist "%JAVA_HOME%\bin\java.exe" set "JAVA=%JAVA_HOME%\bin\java.exe"
)
if not defined JAVA (
    where java >nul 2>&1
    if errorlevel 1 (
        echo jenreg: no Java runtime found - set JAVA_HOME or add 'java' to PATH ^(Java 25 or newer required^) 1>&2
        exit /b 1
    )
    set "JAVA=java"
)

set "JAVA_VERSION="
for /f "usebackq tokens=3" %%v in (`""!JAVA!" -version 2^>^&1 ^| findstr /i version"`) do (
    if not defined JAVA_VERSION set "JAVA_VERSION=%%~v"
)
set "JAVA_MAJOR="
for /f "tokens=1 delims=." %%m in ("!JAVA_VERSION!") do set "JAVA_MAJOR=%%m"
echo !JAVA_MAJOR!| findstr /r "^[0-9][0-9]*$" >nul
if errorlevel 1 (
    echo jenreg: Java 25 or newer required, but '!JAVA!' reports version '!JAVA_VERSION!' 1>&2
    exit /b 1
)
if !JAVA_MAJOR! LSS 25 (
    echo jenreg: Java 25 or newer required, but '!JAVA!' reports version '!JAVA_VERSION!' 1>&2
    exit /b 1
)

"!JAVA!" %JAVA_OPTS% -jar "%CLI_HOME%\lib\jenreg.jar" %*
exit /b %ERRORLEVEL%
