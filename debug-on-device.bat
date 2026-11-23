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
for /f "usebackq skip=1 tokens=1,2,*" %%a in ("%LIST%") do (
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
if "%CHOICE:~0,1%"=="0" goto :invalid
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
echo [1/5] Building the debug app - the first build takes a while...
call "%~dp0gradlew.bat" :app:assembleDebug -q
if errorlevel 1 (
    echo.
    echo The build failed. Read the messages above.
    goto :end
)

rem Some phones - Oppo, Realme, OnePlus with a second space or a clone profile - have a current user that is not 0. The app has to
rem be installed for that user, and every command after that has to name it, or the phone says the app does not exist.
set "CURUSER=0"
"%ADB%" -s "%DEVICE%" shell am get-current-user >"%TEMP%\caster-user.txt" 2>nul
set /p CURUSER=<"%TEMP%\caster-user.txt"
echo Phone user: %CURUSER%

echo [2/5] Installing it on %DEVICE%...
rem Installed with adb itself, so the chosen device is used exactly as it is listed above, also a phone connected over Wi-Fi.
"%ADB%" -s "%DEVICE%" install -r --user %CURUSER% "%~dp0app\build\outputs\apk\debug\app-debug.apk"
if errorlevel 1 (
    echo Install for user %CURUSER% did not work, trying a plain install...
    "%ADB%" -s "%DEVICE%" install -r "%~dp0app\build\outputs\apk\debug\app-debug.apk"
)
if errorlevel 1 (
    echo.
    echo The install failed. Read the message above. If it says INSTALL_FAILED_UPDATE_INCOMPATIBLE, remove the old Caster from the phone first.
    goto :end
)
"%ADB%" -s "%DEVICE%" shell pm path --user %CURUSER% %PACKAGE% >"%TEMP%\caster-pm.txt" 2>nul
findstr /c:"package:" "%TEMP%\caster-pm.txt" >nul
if errorlevel 1 (
    echo.
    echo The install said Success, but this phone does not list Caster for user %CURUSER%. What the phone reports:
    "%ADB%" -s "%DEVICE%" shell pm list users
    "%ADB%" -s "%DEVICE%" shell pm list packages -f --user %CURUSER% caster
    echo Trying to start it anyway...
)

echo [3/5] Allowing the permissions the app asks for, so no dialogs get in the way...
for %%P in (READ_MEDIA_VIDEO READ_MEDIA_IMAGES READ_MEDIA_AUDIO READ_EXTERNAL_STORAGE POST_NOTIFICATIONS RECORD_AUDIO ACCESS_LOCAL_NETWORK) do (
    "%ADB%" -s "%DEVICE%" shell pm grant --user %CURUSER% %PACKAGE% android.permission.%%P >nul 2>nul
)

echo [4/5] Starting the app...
"%ADB%" -s "%DEVICE%" logcat -c >nul 2>nul
"%ADB%" -s "%DEVICE%" shell am force-stop --user %CURUSER% %PACKAGE% >nul 2>nul
"%ADB%" -s "%DEVICE%" shell am start --user %CURUSER% -a android.intent.action.MAIN -c android.intent.category.LAUNCHER -p %PACKAGE%
if errorlevel 1 "%ADB%" -s "%DEVICE%" shell am start --user %CURUSER% -n %PACKAGE%/.MainActivity
echo.
echo If the phone asks for a security or install check, tap Continue or Allow on the phone now.
ping -n 5 127.0.0.1 >nul
echo What the phone is showing in front right now:
"%ADB%" -s "%DEVICE%" shell dumpsys activity activities | findstr /i "mResumedActivity"

echo.
echo [5/5] Live log of the app: warnings, errors and Caster messages. Ctrl+C to stop.
echo ------------------------------------------------------------------------------------------------
rem Some phones - ColorOS, for one - do not let adb open an app. So the script waits for the app to be running, by itself or because
rem you opened it on the phone, and then follows it.
set "APPPID="
set TRIES=0
:waitpid
call :findpid
if defined APPPID goto :follow
set /a TRIES+=1
if %TRIES%==1 echo Caster is not running yet. If it did not open by itself, open it on the phone now. Waiting up to a minute...
if %TRIES% GEQ 30 goto :nopid
ping -n 3 127.0.0.1 >nul
goto :waitpid

:follow
echo Following the app, process %APPPID%.
"%ADB%" -s "%DEVICE%" logcat --pid=%APPPID% -v time MediaServerService:I LocalMediaServer:I *:W
goto :end

:nopid
echo Caster did not start within a minute, so everything the phone says about it is shown instead.
"%ADB%" -s "%DEVICE%" logcat -v time *:W | findstr /i /c:"mano.algo.caster" /c:"AndroidRuntime" /c:"FATAL" /c:"MediaServerService" /c:"LocalMediaServer"

:end
echo.
pause
endlocal
exit /b

rem Looks for the app's process: first the plain pidof, then ps, because older Android versions know fewer options.
:findpid
set "APPPID="
"%ADB%" -s "%DEVICE%" shell pidof %PACKAGE% >"%TEMP%\caster-pid.txt" 2>nul
set /p APPPID=<"%TEMP%\caster-pid.txt"
if not defined APPPID (
    "%ADB%" -s "%DEVICE%" shell "ps -A | grep %PACKAGE%" >"%TEMP%\caster-ps.txt" 2>nul
    for /f "usebackq tokens=2" %%i in ("%TEMP%\caster-ps.txt") do if not defined APPPID set "APPPID=%%i"
)
if defined APPPID for /f "tokens=1" %%i in ("%APPPID%") do set "APPPID=%%i"
exit /b
