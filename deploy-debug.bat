@echo off
rem Builds the debug build and installs it on a specific device via
rem adb/gradlew, without Android Studio (gradlew already knows how to both
rem build and install; Android Studio was never involved at all).
rem
rem The serial below is the Pixel 8 Pro connected over USB at the time this
rem script was written. ANDROID_SERIAL is an env var understood by BOTH adb
rem AND the Android Gradle Plugin inside gradlew installDebug -- that's how
rem they both pick the right device if several are connected at once (a
rem second phone, an emulator), without a manual "adb -s <serial> install".
rem
rem If the Pixel's serial changes (reconnected as a different USB device, a
rem different phone) or you need to target another device -- no need to
rem edit this file, just pass the serial as the first argument:
rem   deploy-debug.bat <serial>
rem List of connected devices and their serials: adb devices -l
rem
rem ASCII only, no non-Latin bytes -- cmd.exe's default console codepage
rem (not UTF-8) mangles Cyrillic in "echo" output, and "chcp 65001" plus
rem non-ASCII bytes in a .bat is a known cmd.exe parser bug that cuts
rem multi-byte UTF-8 sequences mid-character. Keep every message here in
rem English.

set "DEFAULT_SERIAL=46281FDJG0015B"
set "ANDROID_SERIAL=%~1"
if "%ANDROID_SERIAL%"=="" set "ANDROID_SERIAL=%DEFAULT_SERIAL%"

rem Run from the script's own folder (Android project root, where
rem gradlew.bat lives) -- so the script works from any cwd.
cd /d "%~dp0"

rem adb here is ONLY for a quick "device online" check -- the actual
rem build/install is done by gradlew (installDebug), which finds adb itself
rem via local.properties (sdk.dir). Look for adb in standard locations so
rem the check still works even if it's not on PATH.
set "ADB=adb"
if defined ANDROID_HOME if exist "%ANDROID_HOME%\platform-tools\adb.exe" set "ADB=%ANDROID_HOME%\platform-tools\adb.exe"
if defined ANDROID_SDK_ROOT if exist "%ANDROID_SDK_ROOT%\platform-tools\adb.exe" set "ADB=%ANDROID_SDK_ROOT%\platform-tools\adb.exe"
if exist "%LOCALAPPDATA%\Android\Sdk\platform-tools\adb.exe" set "ADB=%LOCALAPPDATA%\Android\Sdk\platform-tools\adb.exe"

echo Checking device %ANDROID_SERIAL% ...
"%ADB%" -s %ANDROID_SERIAL% get-state >nul 2>&1
if errorlevel 1 (
    echo.
    echo Device %ANDROID_SERIAL% not found by adb ^(not connected, not
    echo authorized, or its serial changed^). Connected devices:
    "%ADB%" devices -l
    exit /b 1
)

echo Building debug and installing on %ANDROID_SERIAL% ...
rem Explicit path, not a bare "gradlew.bat" -- in some environments (e.g.
rem running from Git Bash via ./deploy-debug.bat), "call" won't resolve a
rem bare filename from cwd for a NESTED call, even though "cd" above did
rem work correctly.
call "%~dp0gradlew.bat" installDebug
if errorlevel 1 (
    echo.
    echo Build or install failed, see the log above.
    exit /b 1
)

echo.
echo Done: app-debug.apk installed on %ANDROID_SERIAL%.
@pause
