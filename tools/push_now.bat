@echo off
setlocal enabledelayedexpansion
title ScanPdf - push now
cd /d "%~dp0.."

echo ==================================================
echo   ScanPdf  -  push to github.com/chengxiaofeng-prog/scanpdf
echo ==================================================
echo.

rem ---------- 0. locate git: system PATH first, then bundled portable copies ----------
set "GIT=git"
where git >nul 2>nul
if errorlevel 1 (
    set "GIT="
    for %%G in (
        "C:\Program Files\Git\cmd\git.exe"
        "D:\Program Files\WorkBuddy\resources\vendor\PortableGit\cmd\git.exe"
        "%USERPROFILE%\.workbuddy\binaries\PortableGit\versions\1.3.0\cmd\git.exe"
    ) do (
        if not defined GIT if exist %%~G set "GIT=%%~G"
    )
)
if not defined GIT (
    echo [X] git not found. Install "Git for Windows" first:
    echo     https://git-scm.com/download/win
    echo.
    pause
    exit /b 1
)
echo [i] git: !GIT!

rem make git-credential-manager visible when using a portable copy
for %%D in ("!GIT!") do set "GITBIN=%%~dpD"
if exist "!GITBIN!..\mingw64\bin\git-credential-manager.exe" (
    set "PATH=!GITBIN!..\mingw64\bin;%PATH%"
    echo [i] credential manager: portable copy found
)

rem ---------- 0.5 clear inherited proxy env vars ----------
rem a dead global proxy (e.g. a closed accelerator) makes git hang, then fail
set "http_proxy="
set "https_proxy="
set "HTTP_PROXY="
set "HTTPS_PROXY="
set "ALL_PROXY="
set "all_proxy="

rem ---------- 1. proxy auto-detect (this repo only, cleaned up afterwards) ----------
set PROXY_OK=0
for %%P in (26561 7890 7891 7897 10809 10808 1087 1080 8889 20171 33210) do (
    if "!PROXY_OK!"=="0" (
        powershell -NoProfile -Command "try{$c=New-Object Net.Sockets.TcpClient;$c.Connect('127.0.0.1',%%P);$c.Close();exit 0}catch{exit 1}" >nul 2>nul
        if not errorlevel 1 (
            echo [i] local proxy found on port %%P - using it for this repo only
            "!GIT!" config http.proxy http://127.0.0.1:%%P
            "!GIT!" config https.proxy http://127.0.0.1:%%P
            set PROXY_OK=1
        )
    )
)
if "!PROXY_OK!"=="0" echo [i] no local proxy detected - trying a direct connection

rem ---------- 2. remote ----------
"!GIT!" remote remove origin >nul 2>nul
"!GIT!" remote add origin https://github.com/chengxiaofeng-prog/scanpdf.git

rem ---------- 3. is a force push really needed? ----------
rem fast-forward (remote is an ancestor of local) -> normal push, no question asked
set FORCE=
"!GIT!" fetch --quiet --no-tags origin main >nul 2>nul
"!GIT!" merge-base --is-ancestor origin/main HEAD >nul 2>nul
if errorlevel 1 (
    echo.
    echo [warn] the remote has commits that are NOT in your local copy
    echo     - likely files uploaded through the GitHub web page.
    echo     Pushing will REPLACE them with the local project.
    set /p OVERWRITE=Type Y to overwrite, or press Enter to cancel: 
    if /i not "!OVERWRITE!"=="Y" (
        echo.
        echo cancelled - nothing was pushed.
        pause
        exit /b 1
    )
    set FORCE=--force
)

rem ---------- 4. push ----------
echo.
echo [*] pushing branch main ...
echo     a browser window may pop up: sign in to GitHub once, it is remembered.
echo.
"!GIT!" push -u origin main !FORCE!

if errorlevel 1 (
    echo.
    echo [warn] first attempt failed - retrying with certificate revocation check off ...
    echo     (needed on some campus / corporate networks)
    echo.
    "!GIT!" -c http.schannelCheckRevoke=false push -u origin main !FORCE!
)

if errorlevel 1 (
    "!GIT!" config --unset http.proxy >nul 2>nul
    "!GIT!" config --unset https.proxy >nul 2>nul
    echo.
    echo [X] push failed. Check these in order:
    echo.
    echo     1. can your browser open https://github.com ?
    echo        NO  - network issue. Turn on your accelerator, run this again.
    echo        YES - run tools\fix_github_hosts.bat as administrator.
    echo.
    echo     2. the browser sign-in window was cancelled
    echo     3. signed in with the wrong GitHub account
    echo.
    echo Send a screenshot of this window if it still fails.
    echo.
    pause
    exit /b 1
)

rem ---------- 5. done ----------
"!GIT!" config --unset http.proxy >nul 2>nul
"!GIT!" config --unset https.proxy >nul 2>nul

echo.
echo [OK] code is on GitHub:
echo      https://github.com/chengxiaofeng-prog/scanpdf
echo.
echo [*] opening the Actions page - the first build starts by itself.
echo     It takes 5 to 15 minutes. When it is green, download
echo     ScanPdf-debug-apk from "Artifacts" at the bottom of that run.
echo.
start "" "https://github.com/chengxiaofeng-prog/scanpdf/actions"
timeout /t 4 >nul
pause
