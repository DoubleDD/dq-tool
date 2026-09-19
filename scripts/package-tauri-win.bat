@echo off
rem Packaging script for the tauri module (Tauri 2 desktop shell) on Windows:
rem builds the frontend + server fat jar, embeds a full JRE (runtime layer, rarely
rem changes) and the business layer (jar + web build) as a versioned directory
rem resources\versions\<v>\ with a versions\current pointer file, and produces an
rem NSIS installer (at runtime the Rust sidecar launches the embedded
rem jre\bin\java.exe -jar from the current version dir, see tauri/src-tauri/src/).
rem Prerequisites: JDK 25+, Node 24+, pnpm 11+, Rust (cargo).
rem
rem NOTE: keep every comment in this .bat ASCII-only. cmd parses .bat files as GBK on
rem Chinese Windows, and UTF-8 Chinese comments can corrupt parsing (GBK trail bytes
rem include 0x7C "|", 0x5E "^" etc. - a misaligned pairing may split a line into a
rem pipeline). Details: tauri\AGENTS.md
setlocal
cd /d %~dp0\..

rem Environment fallback (same as scripts\package-win.bat):
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

rem Package version for the versions\<v> layout, read from the VERSION file (single
rem source of truth; strip the leading "0." prefix: 0.2.0.14 -> 2.0.14).
rem NOTE: never use %VAR:0.=% to strip the prefix - that syntax replaces EVERY
rem "0." substring (0.2.0.3 -> 2.3, hit in v2.0.3 CI); use the ~0,2/~2 substring form
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
rem Business layer ships as a versioned directory: versions\<v>\dq-tool.jar +
rem versions\<v>\static\ (web build) + versions\current pointer file. The Rust shell
rem launches the jar and serves the frontend from the dir the pointer names, keeps
rem the previous version for rollback, and applies business updates in place
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

rem Smoke test: the embedded jre starts (full verification is double-clicking the installed app)
"%RES%\jre\bin\java.exe" -version || exit /b 1

rem Updater signing private key: load from the in-repo file when not set (single line, no password)
if "%TAURI_SIGNING_PRIVATE_KEY%"=="" set /p TAURI_SIGNING_PRIVATE_KEY=<scripts\updater-private.key
rem Fail fast when the key is missing: without it the tauri CLI cannot sign the
rem updater artifact, which would otherwise surface as a confusing rename error
rem in a later CI step (v1.6 tag build hit exactly this)
if not defined TAURI_SIGNING_PRIVATE_KEY (
  echo ERROR: TAURI_SIGNING_PRIVATE_KEY not set and scripts\updater-private.key could not be read
  exit /b 1
)

pushd tauri
rem tauri dependencies are managed by pnpm now (single lock source: tauri/pnpm-lock.yaml)
if not exist node_modules (call pnpm install --frozen-lockfile || (popd & exit /b 1))
rem npm pass-through needs "npm run <script> -- <args>", otherwise --bundles is treated as a cargo flag
rem (npm run works fine against a pnpm-installed node_modules; only installs moved to pnpm)
rem The signing key has no password: tauri CLI uses TAURI_SIGNING_PRIVATE_KEY_PASSWORD when present,
rem else falls back to an empty password when CI is truthy, else prompts (fatal in headless runs).
rem cmd cannot define empty env vars (set VAR= deletes the var), so set CI=true as the fallback.
if defined TAURI_SIGNING_PRIVATE_KEY_PASSWORD (
  call npm run tauri -- build --bundles nsis || (popd & exit /b 1)
) else (
  set CI=true
  call npm run tauri -- build --bundles nsis || (popd & exit /b 1)
)
popd

rem Verify the updater signature was actually produced: tauri CLI 2.11+ no longer
rem creates a .nsis.zip for the v2 updater (self-contained installer; signs setup.exe
rem directly), so the signature file *-setup.exe.sig is the proof signing happened
set EXESIG=
for /f "delims=" %%f in ('dir /b tauri\src-tauri\target\release\bundle\nsis\*-setup.exe.sig 2^>nul') do set EXESIG=%%f
if not defined EXESIG (
  echo ERROR: updater signature ^(*-setup.exe.sig^) missing after build; updater signing was skipped
  exit /b 1
)

echo Artifacts: tauri\src-tauri\target\release\bundle\nsis\
endlocal
