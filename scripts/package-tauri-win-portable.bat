@echo off
rem Portable (no-install) packaging script for the tauri module on Windows:
rem builds the frontend + server fat jar, embeds a full JRE (runtime layer) and the
rem business layer as a versioned directory resources\versions\<v>\ with a
rem versions\current pointer file, compiles with --no-bundle (plain release exe, no
rem NSIS installer), then stages exe + resources + PORTABLE.txt marker + empty data\
rem into a folder and zips it. At runtime the Rust sidecar detects PORTABLE.txt and
rem uses <exe>\data as the data dir; the full installer auto-updater stays disabled,
rem business-layer updates (swap files under resources\versions) still apply
rem (see is_portable() in tauri/src-tauri/src/).
rem Prerequisites: JDK 25+, Node 24+, pnpm 11+, Rust (cargo), PowerShell (for the zip).
rem
rem NOTE: keep every comment in this .bat ASCII-only. cmd parses .bat files as GBK on
rem Chinese Windows, and UTF-8 Chinese comments can corrupt parsing (GBK trail bytes
rem include 0x7C "|", 0x5E "^" etc. - a misaligned pairing may split a line into a
rem pipeline). Details: tauri\AGENTS.md
setlocal
cd /d %~dp0\..

rem Environment fallback (same as scripts\package-tauri-win.bat):
rem JAVA_HOME/TMP/USERPROFILE may be lost when invoked via MSYS2 make; a missing TMP
rem makes the Java tmpdir fall back to C:\Windows
if "%TMP%"=="" set "TMP=%~dp0..\build\tmp"
if "%TEMP%"=="" set "TEMP=%TMP%"
if not exist "%TMP%" mkdir "%TMP%"
if "%JAVA_HOME%"=="" for /d %%d in ("%USERPROFILE%\.jdks\jdk-25*" "C:\Program Files\Eclipse Adoptium\jdk-25*" "C:\Program Files\Java\jdk-25*" "C:\Program Files\Microsoft\jdk-25*" "C:\Program Files\Zulu\zulu-25*" "C:\Program Files\Amazon Corretto\jdk25*") do set "JAVA_HOME=%%d"
if "%JAVA_HOME%"=="" for /d %%u in (C:\Users\*) do for /d %%d in ("%%u\.jdks\jdk-25*") do set "JAVA_HOME=%%d"

if /i not "%~1"=="--skip-build" (
  pushd web && call npm run build && popd || exit /b 1
  call gradlew.bat :server:shadowJar || exit /b 1
)

rem Pick the newest dq-tool-*.fat jar (excluding the plain archive jar)
set JAR=
for /f "delims=" %%f in ('dir /b /o-d server\build\libs\dq-tool-*.jar ^| findstr /v plain') do (
  if not defined JAR set JAR=server\build\libs\%%f
)
if not defined JAR (
  echo server fat jar not found, run gradlew.bat :server:shadowJar first
  exit /b 1
)

rem Package version for the versions\<v> layout and the zip name, read from the
rem VERSION file (single source of truth; strip the leading "0." prefix:
rem 0.2.0.14 -> 2.0.14). Never use %VAR:0.=% - it replaces EVERY "0." substring
set RAW_VERSION=
for /f "delims=" %%a in (VERSION) do set RAW_VERSION=%%a
if not defined RAW_VERSION (
  echo ERROR: VERSION file missing or empty
  exit /b 1
)
set APP_VERSION=%RAW_VERSION%
if "%RAW_VERSION:~0,2%"=="0." set APP_VERSION=%RAW_VERSION:~2%

set RES=tauri\src-tauri\resources
rem Keep PLACEHOLDER.txt: the bundle.resources glob in tauri.conf.json requires at
rem least one non-hidden file under resources\
if exist "%RES%\backend" rmdir /s /q "%RES%\backend"
if exist "%RES%\versions" rmdir /s /q "%RES%\versions"
if exist "%RES%\jre" rmdir /s /q "%RES%\jre"
rem Business layer ships as a versioned directory (same layout as the NSIS build):
rem versions\<v>\dq-tool.jar + versions\<v>\static\ (web build) + versions\current
rem pointer file. The web build is NOT duplicated to resources\static anymore:
rem start-browser.bat probes the versions dir for both the jar and the frontend
mkdir "%RES%\versions\%APP_VERSION%"
copy "%JAR%" "%RES%\versions\%APP_VERSION%\dq-tool.jar" >nul
xcopy "web\dist" "%RES%\versions\%APP_VERSION%\static\" /E /I /Q >nul || exit /b 1
> "%RES%\versions\current" echo %APP_VERSION%
> "%RES%\versions\%APP_VERSION%\manifest.json" echo {"version": "%APP_VERSION%"}

rem Embed a full JRE instead of a jlink-trimmed one (same reason as scripts\package-win.bat:
rem JDBC drivers load classes reflectively and jdeps cannot cover them; the DM driver
rem needs EUC-KR from jdk.charsets). Only dev tools (bin launchers + jmods) are removed;
rem copy+trim does not rely on jmods (some JDK distributions ship none, breaking jlink)
if "%JAVA_HOME%"=="" (
  echo JAVA_HOME must point to a full JDK 25+
  exit /b 1
)
xcopy "%JAVA_HOME%" "%RES%\jre\" /E /I /Q >nul || exit /b 1
if exist "%RES%\jre\jmods" rmdir /s /q "%RES%\jre\jmods"
for %%t in (javac javadoc javap jar jarsigner serialver jconsole jdb jdeprscan jdeps jfr jhsdb jimage jinfo jlink jmap jmod jpackage jps jrunscript jshell jstack jstat jstatd jwebserver jcmd jnativescan) do if exist "%RES%\jre\bin\%%t.exe" del /q "%RES%\jre\bin\%%t.exe"

rem Smoke test: the embedded jre starts (full verification is double-clicking the exe)
"%RES%\jre\bin\java.exe" -version || exit /b 1

rem Updater signing private key: load from the in-repo file when not set (single line,
rem no password). --no-bundle produces no updater artifacts, but the tauri CLI refuses
rem to build when a pubkey is configured and no private key is available, so load it
rem anyway as a safeguard
if "%TAURI_SIGNING_PRIVATE_KEY%"=="" set /p TAURI_SIGNING_PRIVATE_KEY=<scripts\updater-private.key

pushd tauri
rem tauri dependencies are managed by pnpm now (single lock source: tauri/pnpm-lock.yaml)
if not exist node_modules (call pnpm install --frozen-lockfile || (popd & exit /b 1))
rem --no-bundle: compile the release exe only, skip NSIS/MSI installer generation
rem (npm pass-through needs "npm run <script> -- <args>", otherwise the flag is
rem treated as a cargo flag; npm run works fine against a pnpm-installed node_modules)
call npm run tauri -- build --no-bundle || (popd & exit /b 1)
popd

rem Zip name uses the APP_VERSION already read from the VERSION file above
set STAGE=tauri\src-tauri\target\release\portable
set ZIP=tauri\src-tauri\target\release\dq-tool_%APP_VERSION%_windows-portable.zip
if exist "%STAGE%" rmdir /s /q "%STAGE%"
mkdir "%STAGE%\dq-tool\data"
rem --no-bundle keeps the cargo binary name (dq-tool-tauri.exe): only the bundle step
rem renames it to productName (dq-tool). Copy whichever exists and always ship it as
rem dq-tool.exe so the portable folder matches the installed layout and PORTABLE.txt
set EXE_SRC=tauri\src-tauri\target\release\dq-tool-tauri.exe
if not exist "%EXE_SRC%" set EXE_SRC=tauri\src-tauri\target\release\dq-tool.exe
copy "%EXE_SRC%" "%STAGE%\dq-tool\dq-tool.exe" >nul || exit /b 1
xcopy "%RES%" "%STAGE%\dq-tool\resources\" /E /I /Q >nul || exit /b 1

rem Browser mode without the Tauri shell: start-browser.bat launches the versioned
rem jar with -Ddq.web.static-dir pointing at the static\ inside the same version dir
copy "scripts\start-browser.bat" "%STAGE%\dq-tool\" >nul || exit /b 1

rem PORTABLE.txt is the marker is_portable() checks: when present next to the exe the
rem data dir is <exe>\data and the full installer auto-updater is disabled (business
rem layer updates still apply - they just swap files under resources\versions).
rem Keep the content ASCII
echo This file marks the portable (no-install) edition of dq-tool.> "%STAGE%\dq-tool\PORTABLE.txt"
echo Data is stored in the data folder next to dq-tool.exe; the full installer auto-updater is disabled (business-layer updates still apply).>> "%STAGE%\dq-tool\PORTABLE.txt"
echo Do not delete this file, otherwise the app falls back to %%USERPROFILE%%\.dq-tool\data.>> "%STAGE%\dq-tool\PORTABLE.txt"

if exist "%ZIP%" del /q "%ZIP%"
powershell -NoProfile -Command "Compress-Archive -Path '%STAGE%\dq-tool' -DestinationPath '%ZIP%' -CompressionLevel Optimal" || exit /b 1

echo Portable package: %ZIP%
echo Layout: dq-tool\dq-tool.exe + resources\ + data\ + PORTABLE.txt + start-browser.bat (unzip and run;
echo   dq-tool.exe = desktop app, start-browser.bat = same backend in a browser window)
endlocal
