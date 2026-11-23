@echo off
setlocal enabledelayedexpansion
cd /d "%~dp0"
title Caster - debug on a device

rem ------------------------------------------------------------------------------------------------
rem  Asks which device to use, then builds the Caster debug app, installs it there, starts it,
rem  and shows its live log. Double-click to run. Ctrl+C stops the log.
rem ------------------------------------------------------------------------------------------------
set "PACKAGE=mano.algo.caster"

rem Find adb: on the PATH first, then the Android Studio default location.
set "ADB=adb"
where adb >nul 2>nul <nul
if errorlevel 1 set "ADB=%LOCALAPPDATA%\Android\Sdk\platform-tools\adb.exe"
if defined ANDROID_HOME if exist "%ANDROID_HOME%\platform-tools\adb.exe" set "ADB=%ANDROID_HOME%\platform-tools\adb.exe"
"%ADB%" version >nul 2>nul <nul
if errorlevel 1 (
    echo adb was not found. Install the Android SDK platform-tools, or add adb to the PATH.
    goto :end
)
"%ADB%" start-server >nul 2>nul <nul

:pick
echo.
echo Devices connected to adb right now:
echo ------------------------------------------------------------------------------------------------
set COUNT=0
set "LIST=%TEMP%\caster-devices.txt"
"%ADB%" devices -l >"%LIST%" 2>nul <nul
for /f "skip=1 tokens=1,2,*" %%a in (%LIST%) do (
    if "%%b"=="device" (
        set /a COUNT+=1
        set "DEV!COUNT!=%%a"
        echo   [!COUNT!]  %%a   %%c
    ) else (
        if not "%%b"=="" echo   [-]  %%a   is %%b and cannot be used yet
    )
)
if %COUNT%==0 echo   none
echo ------------------------------------------------------------------------------------------------
echo   Type the number of the device to use,
echo   C = connect a device by its IP address,  R = look again,  Q = quit
set "CHOICE="
set /p "CHOICE=Your choice: "
if not defined CHOICE goto :pick
if /i "%CHOICE%"=="Q" goto :end
if /i "%CHOICE%"=="R" goto :pick
if /i "%CHOICE%"=="C" goto :connect
for /f "delims=0123456789" %%x in ("%CHOICE%") do goto :invalid
if %CHOICE% LSS 1 goto :invalid
if %CHOICE% GTR %COUNT% goto :invalid
set "DEVICE=!DEV%CHOICE%!"
goto :run

:invalid
echo.
echo "%CHOICE%" is not one of the choices.
goto :pick

:connect
echo.
set "ADDR="
set /p "ADDR=IP address and port, for example 192.168.0.20:5555 - leave empty to go back: "
if defined ADDR "%ADB%" connect %ADDR% <nul
goto :pick

:run
echo.
echo Using %DEVICE%.
echo [1/4] Building and installing the debug app on %DEVICE% (the first build takes a while)...
rem With several devices connected, only the chosen one gets the app.
set "ANDROID_SERIAL=%DEVICE%"
call "%~dp0gradlew.bat" :app:installDebug -q
if errorlevel 1 (
    echo.
    echo The build or install failed. Read the messages above.
    goto :end
)

echo [2/4] Allowing the permissions the app asks for, so no dialogs get in the way...
for %%P in (READ_MEDIA_VIDEO READ_MEDIA_IMAGES READ_MEDIA_AUDIO POST_NOTIFICATIONS RECORD_AUDIO ACCESS_LOCAL_NETWORK NEARBY_WIFI_DEVICES) do (
    "%ADB%" -s %DEVICE% shell pm grant %PACKAGE% android.permission.%%P >nul 2>nul
)

echo [3/4] Starting the app...
"%ADB%" -s %DEVICE% logcat -c
"%ADB%" -s %DEVICE% shell am force-stop %PACKAGE%
"%ADB%" -s %DEVICE% shell am start -n %PACKAGE%/.MainActivity >nul
ping -n 4 127.0.0.1 >nul

echo [4/4] Live log of the app: warnings, errors and Caster messages. Ctrl+C to stop.
echo ------------------------------------------------------------------------------------------------
set "APPPID="
for /f %%i in ('"%ADB%" -s %DEVICE% shell pidof -s %PACKAGE%') do set "APPPID=%%i"
if defined APPPID (
    "%ADB%" -s %DEVICE% logcat --pid=!APPPID! -v time MediaServerService:I LocalMediaServer:I CrashLog:I *:W
) else (
    echo The app is not running - it may have crashed on start. Showing the recent errors:
    "%ADB%" -s %DEVICE% logcat -d -v time *:E
)

:end
echo.
pause
endlocal
