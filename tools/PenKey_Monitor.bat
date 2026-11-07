@echo off
setlocal enabledelayedexpansion
rem ==========================================================================
rem  PenKey Monitor
rem  Keeps the pen-key daemon alive on the device.
rem
rem  What it does (every 20 s):
rem    - daemon process gone AND screen on  -> reactivate
rem    - daemon alive but older than 5 min  -> force restart
rem    - screen off                         -> skip
rem
rem  Requirements: adb in PATH (or ADB env var), USB debugging authorized.
rem ==========================================================================

if "%ADB%"=="" set ADB=adb
set SCRIPT=%~dp0penkeyd.sh
set REMOTE=/data/local/tmp/penkeyd.sh
set DEVICE=
set LASTFORCE=0

title PenKey Monitor - keep pen key alive
echo ==========================================
echo   PenKey Monitor
echo   Close this window to stop monitoring.
echo ==========================================
echo.

:detect
"%ADB%" get-state >nul 2>&1
if errorlevel 1 (
  echo [%TIME%] waiting for a device...
  timeout /t 10 >nul
  goto detect
)
for /f "delims=" %%d in ('"%ADB%" devices ^| findstr /R /C:"device$"') do (
  for /f "tokens=1" %%e in ("%%d") do if not defined DEVICE set DEVICE=%%e
)
if not defined DEVICE (
  echo [%TIME%] no authorized device found, retry...
  timeout /t 10 >nul
  goto detect
)
echo [%TIME%] device: %DEVICE%
echo.

:loop
"%ADB%" get-state >nul 2>&1
if errorlevel 1 (
  set DEVICE=
  timeout /t 10 >nul
  goto detect
)

"%ADB%" shell "ps -A 2>/dev/null | grep -c getevent" > "%TEMP%\pk_n.txt" 2>nul
set N=0
set /p N=<"%TEMP%\pk_n.txt"

"%ADB%" shell "dumpsys power | grep -c mWakefulness=Awake" > "%TEMP%\pk_a.txt" 2>nul
set AWAKE=0
set /p AWAKE=<"%TEMP%\pk_a.txt"

set /a NOWMIN=%TIME:~3,2% * 60 + %TIME:~6,2%
set /a SINCE=NOWMIN - LASTFORCE
if %SINCE% LSS 0 set /a SINCE=SINCE + 1440

if "%AWAKE%" GEQ "1" (
  if "%N%"=="0" (
    echo [%TIME%] daemon DEAD, screen on -^> reactivate
    call :activate
  ) else (
    if %SINCE% GEQ 5 (
      echo [%TIME%] force restart ^(5 min^)
      call :activate
    )
  )
) else (
  echo [%TIME%] screen off, skip ^(daemon=%N%^)
)

timeout /t 20 >nul
goto loop

:activate
"%ADB%" push "%SCRIPT%" %REMOTE% >nul 2>&1
"%ADB%" shell chmod 755 %REMOTE% >nul 2>&1
"%ADB%" shell setsid sh %REMOTE% >nul 2>&1
timeout /t 3 >nul
set /a LASTFORCE=%TIME:~3,2% * 60 + %TIME:~6,2%
"%ADB%" shell "ps -A 2>/dev/null | grep -c getevent" > "%TEMP%\pk_c.txt" 2>nul
set C=0
set /p C=<"%TEMP%\pk_c.txt"
echo [%TIME%] done, daemon instances = !C!
goto :eof
