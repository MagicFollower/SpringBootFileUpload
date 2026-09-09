@echo off
chcp 65001 >nul 2>&1
setlocal EnableDelayedExpansion

REM Port Occupancy Query and Kill Tool v2.1
REM Windows 10 compatible, all inline (no subroutines)

title Port Query Tool

echo.
echo ================================================================
echo             Port Occupancy Query and Kill Tool
echo ================================================================
echo.

set /p "PORT=Please enter port number: "
set "PORT=%PORT: =%"

echo !PORT!| findstr /r "^[0-9][0-9]*$" >nul
if errorlevel 1 (
    echo [ERROR] Invalid input. Port must be a number.
    goto :EXIT
)
if !PORT! LSS 1 (
    echo [ERROR] Port must be greater than 0.
    goto :EXIT
)
if !PORT! GTR 65535 (
    echo [ERROR] Port must not exceed 65535.
    goto :EXIT
)

echo.
echo ----------------------------------------------------------------
echo   Querying port !PORT! ...
echo ----------------------------------------------------------------
echo.

REM ======== Query port via netstat ========
set "FOUND=0"
set "CONN_COUNT=0"
set "UNIQUE_PIDS= "

for /f "tokens=1,2,3,4,5" %%a in ('netstat -ano ^| findstr "LISTENING ESTABLISHED" ^| findstr ":!PORT! "') do (
    set "FOUND=1"
    set /a "CONN_COUNT+=1"
    set "C_PROTO[!CONN_COUNT!]=%%a"
    set "C_LADDR[!CONN_COUNT!]=%%b"
    set "C_RADDR[!CONN_COUNT!]=%%c"
    set "C_STATE[!CONN_COUNT!]=%%d"
    set "C_PID[!CONN_COUNT!]=%%e"
    echo !UNIQUE_PIDS!| findstr /c:" %%e " >nul
    if errorlevel 1 set "UNIQUE_PIDS=!UNIQUE_PIDS!%%e "
)

if "!FOUND!"=="0" (
    echo [INFO] Port !PORT! is not in use.
    goto :EXIT
)

echo ================================================================
echo   Port !PORT! - Detailed Information
echo ================================================================
echo.

REM ======== [1] Network Connection Table ========
echo [1] Network Connections
echo ----------------------------------------------------------------
echo   Protocol        Local Address                     Remote Address                    State           PID
echo   ----------      ----------------------------      ----------------------------      ------------    --------
for /L %%i in (1,1,!CONN_COUNT!) do (
    set "TMP=!C_PROTO[%%i]!                                                                                                                            "
    echo   !TMP:~0,16!  !C_LADDR[%%i]!                              !C_RADDR[%%i]!                              !C_STATE[%%i]!         !C_PID[%%i]!
)
echo.

REM ======== [2] Process Details ========
echo [2] Process Details
echo ----------------------------------------------------------------

set "PID_INDEX=0"
for %%p in (!UNIQUE_PIDS!) do (
    set /a "PID_INDEX+=1"
    echo.
    echo   ---- Process !PID_INDEX! - PID: %%p ----
    echo.

    REM wmic via more to strip BOM and CR
    set "PROC_NAME="
    for /f "delims=" %%w in ('wmic process where "ProcessId=%%p" get Name /value 2^>nul ^| more ^| findstr "="') do set "%%w"
    set "PROC_NAME=!Name!"
    set "Name="

    set "PROC_PATH="
    for /f "delims=" %%w in ('wmic process where "ProcessId=%%p" get ExecutablePath /value 2^>nul ^| more ^| findstr "="') do set "%%w"
    set "PROC_PATH=!ExecutablePath!"
    set "ExecutablePath="

    set "PROC_PPID="
    for /f "delims=" %%w in ('wmic process where "ProcessId=%%p" get ParentProcessId /value 2^>nul ^| more ^| findstr "="') do set "%%w"
    set "PROC_PPID=!ParentProcessId!"
    set "ParentProcessId="

    set "PROC_DATE="
    for /f "delims=" %%w in ('wmic process where "ProcessId=%%p" get CreationDate /value 2^>nul ^| more ^| findstr "="') do set "%%w"
    set "PROC_DATE=!CreationDate!"
    set "CreationDate="

    set "PROC_MEM="
    for /f "delims=" %%w in ('wmic process where "ProcessId=%%p" get WorkingSetSize /value 2^>nul ^| more ^| findstr "="') do set "%%w"
    set "PROC_MEM=!WorkingSetSize!"
    set "WorkingSetSize="

    set "CommandLine="
    for /f "delims=" %%w in ('wmic process where "ProcessId=%%p" get CommandLine /value 2^>nul ^| more ^| findstr "="') do set "%%w"
    set "PROC_CMD=!CommandLine!"
    set "CommandLine="

    REM Format date
    set "FORMATTED_DATE=N/A"
    if defined PROC_DATE if "!PROC_DATE!" NEQ "" (
        set "FORMATTED_DATE=!PROC_DATE:~0,4!-!PROC_DATE:~4,2!-!PROC_DATE:~6,2! !PROC_DATE:~8,2!:!PROC_DATE:~10,2!:!PROC_DATE:~12,2!"
    )

    REM Format memory
    set "MEM_DISPLAY=N/A"
    if defined PROC_MEM if "!PROC_MEM!" NEQ "" (
        set /a "MEM_MB=!PROC_MEM! / 1048576"
        set "MEM_DISPLAY=!MEM_MB! MB"
    )

    REM Process user via wmic GetOwner
    set "PROC_USER=N/A"
    for /f "delims=" %%w in ('wmic process where "ProcessId=%%p" call getowner 2^>nul ^| more ^| findstr "User"') do (
        set "OWNER_TMP=%%w"
        for /f "tokens=2 delims==" %%x in ("!OWNER_TMP!") do set "PROC_USER=%%x"
        set "PROC_USER=!PROC_USER:~1!"
        set "PROC_USER=!PROC_USER:~0,-1!"
        set "PROC_USER=!PROC_USER:~1!"
        set "PROC_USER=!PROC_USER:~0,-1!"
    )
    set "OWNER_TMP="

    REM Process status
    set "PROC_STATUS=Unknown"
    if defined PROC_NAME if "!PROC_NAME!" NEQ "" set "PROC_STATUS=Running"

    REM Output with inline padding for labels (right-aligned to 18 chars)
    set "TMP=                  Process Name"
    echo   !TMP:~-18!: !PROC_NAME!
    set "TMP=                  Process PID"
    echo   !TMP:~-18!: %%p
    set "TMP=                  Parent PID"
    echo   !TMP:~-18!: !PROC_PPID!
    set "TMP=                  User"
    echo   !TMP:~-18!: !PROC_USER!
    set "TMP=                  Status"
    echo   !TMP:~-18!: !PROC_STATUS!
    set "TMP=                  Start Time"
    echo   !TMP:~-18!: !FORMATTED_DATE!
    set "TMP=                  Memory"
    echo   !TMP:~-18!: !MEM_DISPLAY!
    set "TMP=                  Executable Path"
    echo   !TMP:~-18!: !PROC_PATH!
    set "TMP=                  Command Line"
    echo   !TMP:~-18!: !PROC_CMD!
)

echo.
echo ----------------------------------------------------------------
echo.

REM ======== [3] Associated Windows Services ========
echo [3] Associated Windows Services
echo ----------------------------------------------------------------
set "SVC_FOUND=0"
for %%p in (!UNIQUE_PIDS!) do (
    for /f "skip=1 delims=" %%s in ('wmic service where "ProcessId=%%p" get Name^,DisplayName /format:csv 2^>nul ^| more ^| findstr /v "^$"') do (
        echo   PID %%p - Service: %%s
        set "SVC_FOUND=1"
    )
)
if "!SVC_FOUND!"=="0" echo   No associated Windows services found.
echo.

echo ================================================================
echo   Query complete.
echo ================================================================
echo.

echo Please choose an action:
echo   [1] Kill the process occupying this port
echo   [2] Exit
echo.
set /p "CHOICE=Enter option (1/2): "

if "!CHOICE!"=="1" goto :KILL_PROCESS
if "!CHOICE!"=="2" goto :EXIT
echo [ERROR] Invalid option. Exiting.
goto :EXIT

:KILL_PROCESS
echo.
echo ----------------------------------------------------------------
echo   WARNING: The following process^(s^) will be terminated
echo ----------------------------------------------------------------
echo.

for %%p in (!UNIQUE_PIDS!) do (
    set "K_NAME="
    for /f "delims=" %%w in ('wmic process where "ProcessId=%%p" get Name /value 2^>nul ^| more ^| findstr "="') do set "%%w"
    set "K_NAME=!Name!"
    set "Name="
    echo   PID: %%p    Process: !K_NAME!
)

echo.
set /p "CONFIRM=Confirm kill? (Y/N): "

if /i "!CONFIRM!" NEQ "Y" (
    echo [INFO] Operation cancelled.
    goto :EXIT
)

echo.
echo Killing process...
echo.

set "KILL_OK=1"
for %%p in (!UNIQUE_PIDS!) do (
    taskkill /PID %%p /F /T >nul 2>&1
    if errorlevel 1 (
        echo   [FAIL] Cannot kill PID: %%p. Admin rights may be required.
        set "KILL_OK=0"
    ) else (
        echo   [OK]   Killed PID: %%p
    )
)

echo.
if "!KILL_OK!"=="1" (
    echo [DONE] Port !PORT! has been released.
) else (
    echo [TIP] Some processes could not be killed. Try running as Administrator.
)

goto :EXIT

:EXIT
echo.
pause
exit /b 0
