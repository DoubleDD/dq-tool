@echo off
rem Browser-mode launcher for a dq-tool package folder (Windows).
rem Works with both shipped layouts, detected by probing (no config needed):
rem   Tauri portable : resources\backend\dq-tool.jar + resources\jre + PORTABLE.txt
rem   jpackage image : app\dq-tool-<version>.jar + runtime\ + app\static
rem Double-click to start the fat jar next to the shipped exe and use the UI in a
rem browser. Mirrors the matching edition: headless=false, so the app opens the
rem browser window by itself, installs the tray icon, and shifts off an occupied
rem port starting at 10000.
rem Data dir follows the detected edition's convention, same as its exe:
rem   PORTABLE.txt next to this file -> <folder>\data   (portable edition)
rem   anything else                   -> %USERPROFILE%\.dq-tool\data (installed)
rem The delivered fat jar is pure API and serves no pages by itself: the web
rem build ships on disk (resources\static for portable, app\static for jpackage).
rem All paths are absolute off %~dp0 on purpose: no cd/pushd, so this also works
rem when double-clicked off a UNC share or a mapped Mac home folder.
rem Written with plain sequential commands and goto labels only - no
rem parenthesized if-blocks (fragile parser on GBK code pages) and no non-ASCII
rem bytes. Progress goes to start-browser.log next to this file, so a closed
rem console stays diagnosable.
setlocal
title dq-tool browser mode
set "BASE=%~dp0"
set "BATLOG=%BASE%start-browser.log"
> "%BATLOG%" echo [%DATE% %TIME%] start-browser.bat started
call :log base=%BASE%

rem Java: bundled JRE of either layout first, then anything on PATH
set "JAVA=%BASE%resources\jre\bin\java.exe"
if not exist "%JAVA%" set "JAVA=%BASE%resources\runtime\bin\java.exe"
if not exist "%JAVA%" set "JAVA=%BASE%runtime\bin\java.exe"
if not exist "%JAVA%" goto TRY_JAVA_PATH
goto JAVA_DONE
:TRY_JAVA_PATH
call :log no bundled jre, probing PATH
where java.exe >nul 2>nul
if errorlevel 1 goto NO_JAVA
set "JAVA=java.exe"
:JAVA_DONE
call :log java=%JAVA%

rem Fat jar (+ layout detection): portable path, DQ_SERVER_JAR override,
rem then the jpackage app-image with its versioned jar under app\
set "JAR=%BASE%resources\backend\dq-tool.jar"
set "LAYOUT=portable"
if defined DQ_SERVER_JAR set "JAR=%DQ_SERVER_JAR%"
if defined DQ_SERVER_JAR set "LAYOUT=override"
if not exist "%JAR%" goto TRY_JPACKAGE_JAR
goto JAR_DONE
:TRY_JPACKAGE_JAR
set "LAYOUT=jpackage"
set "JAR="
for %%f in ("%BASE%app\dq-tool-*.jar") do if not defined JAR set "JAR=%%~f"
if not defined JAR goto NO_JAR
if not exist "%JAR%" goto NO_JAR
:JAR_DONE
call :log layout=%LAYOUT% jar=%JAR%

rem Frontend assets on disk (index.html marks a usable web build)
set "STATIC="
if exist "%BASE%static\index.html" set "STATIC=%BASE%static"
if not defined STATIC if exist "%BASE%resources\static\index.html" set "STATIC=%BASE%resources\static"
if not defined STATIC if exist "%BASE%app\static\index.html" set "STATIC=%BASE%app\static"
if not defined STATIC goto NO_STATIC
call :log static=%STATIC%

rem Data dir: mirror the detected edition's exe (PORTABLE.txt marks the edition
rem that keeps data next to the app; everything else uses the per-user dir)
set "DATA=%BASE%data"
if "%LAYOUT%"=="portable" if not exist "%BASE%PORTABLE.txt" set "DATA=%USERPROFILE%\.dq-tool\data"
if "%LAYOUT%"=="jpackage" set "DATA=%USERPROFILE%\.dq-tool\data"
for %%d in ("%DATA%") do set "LOGS=%%~dpdlogs"
call :log data=%DATA%

echo [dq-tool] Starting dq-tool in browser mode (%LAYOUT% layout)...
echo [dq-tool]   data dir : %DATA%
echo [dq-tool]   logs     : %LOGS%
echo [dq-tool] A browser window opens automatically. Keep this window open while using dq-tool;
echo [dq-tool] close the browser window, use the tray menu "Quit", or close this window to stop.
echo.
call :log launching java

"%JAVA%" -Djava.awt.headless=false "-Ddq.data-dir=%DATA%" "-Ddq.web.static-dir=%STATIC%" -Xmx1g -jar "%JAR%"
call :log java exited, errorlevel=%ERRORLEVEL%

echo.
echo [dq-tool] Server stopped.
pause
exit /b 0

:NO_JAVA
echo [dq-tool] No bundled JRE found and java.exe is not on PATH.
echo [dq-tool] Use a package with a bundled runtime, or install JDK 25+.
call :log error: no java found
pause
exit /b 1

:NO_JAR
echo [dq-tool] dq-tool.jar not found (looked in resources\backend and app\).
call :log error: jar not found
pause
exit /b 1

:NO_STATIC
echo [dq-tool] Frontend assets not found: no static\index.html next to this file.
echo [dq-tool] The package should ship them (resources\static or app\static).
call :log error: frontend assets not found
pause
exit /b 1

rem Append one phase-log line; called as: call :log some text
:log
echo [%DATE% %TIME%] %* >> "%BATLOG%"
exit /b 0
