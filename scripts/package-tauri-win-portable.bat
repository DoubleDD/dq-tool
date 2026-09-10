@echo off
rem Portable (no-install) packaging script for the tauri module on Windows:
rem builds the frontend + server fat jar, embeds a full JRE together with the jar
rem as resources next to the exe, compiles with --no-bundle (plain release exe, no
rem NSIS installer), then stages exe + resources + PORTABLE.txt marker + empty data\
rem into a folder and zips it. At runtime the Rust sidecar detects PORTABLE.txt and
rem uses <exe>\data as the data dir with the auto-updater disabled
rem (see is_portable() in tauri/src-tauri/src/main.rs).
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

set RES=tauri\src-tauri\resources
rem Keep PLACEHOLDER.txt: the bundle.resources glob in tauri.conf.json requires at
rem least one non-hidden file under resources\
if exist "%RES%\backend" rmdir /s /q "%RES%\backend"
if exist "%RES%\jre" rmdir /s /q "%RES%\jre"
mkdir "%RES%\backend"
copy "%JAR%" "%RES%\backend\dq-tool.jar" >nul

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

rem Version for the zip name, read from tauri.conf.json (kept in sync by bump-version.sh)
set VERSION=
for /f "delims=" %%v in ('powershell -NoProfile -Command "(Get-Content tauri\src-tauri\tauri.conf.json -Raw ^| ConvertFrom-Json).version"') do set VERSION=%%v
if not defined VERSION (
  echo ERROR: could not read version from tauri\src-tauri\tauri.conf.json
  exit /b 1
)

set STAGE=tauri\src-tauri\target\release\portable
set ZIP=tauri\src-tauri\target\release\dq-tool_%VERSION%_windows-portable.zip
if exist "%STAGE%" rmdir /s /q "%STAGE%"
mkdir "%STAGE%\dq-tool\data"
copy "tauri\src-tauri\target\release\dq-tool.exe" "%STAGE%\dq-tool\" >nul || exit /b 1
xcopy "%RES%" "%STAGE%\dq-tool\resources\" /E /I /Q >nul || exit /b 1

rem PORTABLE.txt is the marker is_portable() checks: when present next to the exe the
rem data dir is <exe>\data and the auto-updater is disabled. Keep the content ASCII
echo This file marks the portable (no-install) edition of dq-tool.> "%STAGE%\dq-tool\PORTABLE.txt"
echo Data is stored in the data folder next to dq-tool.exe and auto-update is disabled.>> "%STAGE%\dq-tool\PORTABLE.txt"
echo Do not delete this file, otherwise the app falls back to %%USERPROFILE%%\.dq-tool\data.>> "%STAGE%\dq-tool\PORTABLE.txt"

if exist "%ZIP%" del /q "%ZIP%"
powershell -NoProfile -Command "Compress-Archive -Path '%STAGE%\dq-tool' -DestinationPath '%ZIP%' -CompressionLevel Optimal" || exit /b 1

echo Portable package: %ZIP%
echo Layout: dq-tool\dq-tool.exe + resources\ + data\ + PORTABLE.txt (unzip and run)
endlocal
