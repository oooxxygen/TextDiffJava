@echo off
rem ============================================================
rem  TextDiffJava side-by-side upgrade (Windows)
rem  Extract the NEW portable package to its own directory, then
rem  migrate user data from the OLD install. The OLD directory is
rem  kept untouched as the rollback point. Smoke-start at the end.
rem
rem  Usage (either):
rem    1) from inside the NEW package:  upgrade\upgrade.bat <OLD_DIR>
rem    2) from inside the OLD install:  upgrade.bat <NEW_PORTABLE.zip>
rem    Optional last arg: --no-start   (skip smoke start)
rem
rem  Migrated items: config.ini store results results_split configs
rem  (data written under OLD\bin as working dir is auto-detected)
rem
rem  NOTE: this file MUST stay pure ASCII. cmd batch parsing with
rem  non-ASCII bytes is unreliable even under chcp 65001 (observed:
rem  lines silently skipped / fragments executed as commands).
rem ============================================================
setlocal EnableExtensions EnableDelayedExpansion

set "SCRIPT_DIR=%~dp0"
if "%~1"=="" goto usage
set "NOSTART="
echo %* | findstr /I /C:"--no-start" >nul && set "NOSTART=1"

rem ---------- resolve NEW install and OLD install ----------
set "ARG1=%~f1"
set "OLD=%~f2"
set "NEW="
set "EXT=%~x1"

set "CAND=%SCRIPT_DIR%.."
if /I "%EXT%"==".zip" goto fromzip
if exist "%ARG1%\bin\" (
    rem dir arg = OLD dir when the script ships inside the NEW package,
    rem otherwise both dirs must be given explicitly (arg1=NEW, arg2=OLD)
    if exist "%CAND%\bin\" (
        set "NEW=%CAND%"
        set "OLD=%ARG1%"
    ) else if not "%OLD%"=="" (
        set "NEW=%ARG1%"
    ) else (
        echo [ERROR] script not running from inside an install package and only one dir given;
        echo         cannot tell NEW from OLD. Use the package-bundled upgrade\upgrade.bat ^<OLD_DIR^>,
        echo         pass the .zip instead, or provide both dirs.
        goto fail
    )
) else (
    echo [ERROR] arg is neither a .zip nor an install dir ^(missing bin^): %ARG1%
    goto fail
)
goto havemounts

:fromzip
set "ZIP=%ARG1%"
if not exist "%ZIP%" (
    echo [ERROR] zip not found: %ZIP%
    goto fail
)
if "%OLD%"=="" set "OLD=%CD%"
set "WORK=%OLD%.new-tmp"
echo [1/5] Extract %ZIP% -^> %WORK%
rmdir /s /q "%WORK%" 2>nul
mkdir "%WORK%" 2>nul
tar -xf "%ZIP%" -C "%WORK%" 2>nul
if errorlevel 1 (
    powershell -NoProfile -Command "Expand-Archive -LiteralPath '%ZIP%' -DestinationPath '%WORK%' -Force"
    if errorlevel 1 (
        echo [ERROR] extract failed ^(tar and PowerShell both unavailable, or broken zip^)
        goto fail
    )
)
for /f "delims=" %%d in ('dir /b /ad "%WORK%"') do set "NEW=%WORK%\%%d"
if "%NEW%"=="" set "NEW=%WORK%"
if not exist "%NEW%\bin\" (
    echo [ERROR] extracted dir has no bin ^(not an install image^): %NEW%
    goto fail
)

:havemounts
if not exist "%OLD%\" (
    echo [ERROR] OLD dir not found: %OLD%
    goto fail
)

rem ---------- service must be stopped ----------
echo [2/5] Checking that no service is running on 8080 ...
curl.exe -s -m 3 http://127.0.0.1:8080/api/health >"%TEMP%\td_health.txt" 2>nul
if not errorlevel 1 (
    echo [ABORT] Service is still running. Stop the old service first. Response:
    type "%TEMP%\td_health.txt"
    echo.
    goto fail
)

rem ---------- locate user data (root first, else OLD\bin) ----------
set "SRC=%OLD%"
if not exist "%OLD%\config.ini" if exist "%OLD%\bin\config.ini" set "SRC=%OLD%\bin"
echo [3/5] OLD install : %OLD%
echo        data source : %SRC%
echo        NEW install : %NEW%

set "HASDATA="
if exist "%SRC%\config.ini" set "HASDATA=1"
for %%D in (store results results_split configs) do (
    if exist "%SRC%\%%D\" set "HASDATA=1"
)
if not defined HASDATA echo [WARN] no user data found ^(config.ini/store/results/configs^); dir switch only.

rem ---------- migrate (copy; OLD dir stays untouched as rollback) ----------
echo [4/5] Migrating data (copy; OLD dir kept as rollback point) ...
if exist "%SRC%\config.ini" (
    if exist "%NEW%\config.ini" echo        note: NEW already has config.ini, overriding with OLD data
    copy /y "%SRC%\config.ini" "%NEW%\config.ini" >nul 2>&1
    if errorlevel 1 (
        echo [ERROR] copy failed: config.ini
        goto fail
    )
    echo        migrated: config.ini
)
for %%D in (store results results_split configs) do (
    if exist "%SRC%\%%D\" (
        robocopy "%SRC%\%%D" "%NEW%\%%D" /E /NFL /NDL /NJH /NJS /NP >nul
        if errorlevel 8 (
            echo [ERROR] copy failed: %%D
            goto fail
        )
        echo        migrated: %%D\
    )
)

if defined NOSTART (
    echo [5/5] Skipping smoke start ^(as requested^).
    goto done
)

rem ---------- smoke start ----------
echo [5/5] Smoke start, waiting up to 90s for readiness ...
pushd "%NEW%"
start "TextDiffJava-new" /MIN cmd /c "bin\TextDiffJava.bat > upgrade-start.log 2>&1"
popd
set /a TRIES=0
:waitloop
ping -n 3 127.0.0.1 >nul
curl.exe -s -m 3 http://127.0.0.1:8080/api/health >"%TEMP%\td_health.txt" 2>nul
findstr /C:"ok" "%TEMP%\td_health.txt" >nul && goto started
set /a TRIES+=1
if %TRIES% lss 45 goto waitloop
echo [WARN] not ready within 90s. Check %NEW%\upgrade-start.log;
echo        to roll back: stop this service, delete the NEW dir, start OLD\bin\TextDiffJava.bat
goto done

:started
echo        service is ready:
type "%TEMP%\td_health.txt"
echo.

:done
echo ============================================================
echo  Upgrade finished. OLD dir kept untouched: %OLD%
echo    rollback = stop this service, then run bin\TextDiffJava.bat
echo    inside the OLD dir. Remove OLD and temp dirs after a few
echo    days of trouble-free running.
echo ============================================================
exit /b 0

:usage
echo Usage: upgrade.bat ^<NEW_PORTABLE.zip or OLD_DIR^> [OLD_DIR] [--no-start]
echo   way 1 (recommended, script ships in the NEW package):
echo         extract the new package, then run  upgrade\upgrade.bat ^<OLD_DIR^>
echo   way 2 (from inside the OLD install): upgrade.bat ^<NEW_PORTABLE.zip^>
goto fail

:fail
echo Upgrade aborted ^(nothing harmed; OLD dir untouched^).
exit /b 1
