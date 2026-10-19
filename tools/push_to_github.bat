@echo off
setlocal enabledelayedexpansion
title ScanPdf - push to GitHub
cd /d "%~dp0.."

echo ==================================================
echo   ScanPdf  -  push code to GitHub
echo ==================================================
echo.

where git >nul 2>nul
if errorlevel 1 (
    echo [X] git not found. Install "Git for Windows" first:
    echo     https://git-scm.com/download/win
    echo.
    pause
    exit /b 1
)

rem ---- 1. local repository (already prepared by the agent) ----
if not exist ".git" (
    echo [*] no local repository yet, creating one ...
    git init -b main
    git add -A
    git commit -m "ScanPdf: document scanner"
)
echo [i] Local repository is ready:
git --no-pager log --oneline
echo.

rem ---- 2. target repository ----
echo Your GitHub user name, press Enter to use [chengxiaofeng-prog]
set /p GHUSER=
if "%GHUSER%"=="" set GHUSER=chengxiaofeng-prog

echo Repository name, press Enter to use [scanpdf]
set /p GHREPO=
if "%GHREPO%"=="" set GHREPO=scanpdf

echo.
echo [i] Target: https://github.com/%GHUSER%/%GHREPO%.git
echo.
echo IMPORTANT: create this EMPTY repository on github.com FIRST:
echo    https://github.com/new
echo    name = %GHREPO%   ,  do NOT tick "Add a README file"
echo.
echo Press any key to open the "new repository" page in your browser...
pause >nul
start "" "https://github.com/new?name=%GHREPO%"
echo.
echo After you have created it, press any key to continue...
pause >nul

git remote remove origin >nul 2>nul
git remote add origin https://github.com/%GHUSER%/%GHREPO%.git

rem ---- 3. proxy auto-detect (accelerators: Watt Toolkit / Clash / v2ray) ----
set PROXY_OK=0
for %%P in (26561 7890 7891 10809 10808 1080 8889) do (
    if "!PROXY_OK!"=="0" (
        powershell -NoProfile -Command "try{$c=New-Object Net.Sockets.TcpClient;$c.Connect('127.0.0.1',%%P);$c.Close();exit 0}catch{exit 1}" >nul 2>nul
        if not errorlevel 1 (
            echo [i] found a local proxy on port %%P, using it for this repo only ...
            git config http.proxy http://127.0.0.1:%%P
            git config https.proxy http://127.0.0.1:%%P
            set PROXY_OK=1
        )
    )
)
if "!PROXY_OK!"=="0" (
    echo [i] no local proxy detected, pushing directly.
)

rem ---- 4. push ----
echo.
echo [*] pushing ... a browser window may pop up to sign in to GitHub.
echo     That is Git Credential Manager: sign in once, it is remembered.
echo.
git push -u origin main

if errorlevel 1 (
    rem clean proxy settings so they do not break other git usage later
    git config --unset http.proxy >nul 2>nul
    git config --unset https.proxy >nul 2>nul
    echo.
    echo [X] Push failed. Most likely one of these:
    echo.
    echo     1. github.com unreachable from this network
    echo        - Test in your browser first. If the browser works but git
    echo          does not, your accelerator only proxies the browser.
    echo        - Run tools\fix_github_hosts.bat as administrator,
    echo          or set the proxy manually:
    echo            git config http.proxy http://127.0.0.1:PORT
    echo.
    echo     2. the empty repository does not exist on github.com yet
    echo        - Create it at https://github.com/new   name: %GHREPO%
    echo.
    echo     3. sign-in cancelled, or wrong user name
    echo        - Check: https://github.com/%GHUSER%
    echo.
    pause
    exit /b 1
)

rem proxy only needed for the push itself
git config --unset http.proxy >nul 2>nul
git config --unset https.proxy >nul 2>nul

echo.
echo [OK] Code is on GitHub:
echo      https://github.com/%GHUSER%/%GHREPO%
echo.
echo [*] Opening the Actions page - the first build starts automatically.
echo     It takes 5-15 minutes. Then download the APK from
echo     "Artifacts" -^> ScanPdf-debug-apk at the bottom of that run.
echo.
start "" "https://github.com/%GHUSER%/%GHREPO%/actions"
pause
