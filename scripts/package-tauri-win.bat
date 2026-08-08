@echo off
rem tauri 模块(Tauri 2 桌面壳)Windows 打包脚本:构建前端 + server fat jar,
rem 完整 JRE 与 jar 一起作为 Tauri bundle resources 打进安装包,
rem 产出 NSIS 安装程序(运行时由 Rust 侧车拉起 内嵌 jre\bin\java.exe -jar,见 tauri/src-tauri/src/main.rs)
rem 前置要求: JDK 25+、Node 24+、Rust(cargo)
setlocal
cd /d %~dp0\..

rem 环境尧底(与 scripts\package-win.bat 相同,原因见其注释):
rem 经 MSYS2 的 make 调用时 JAVA_HOME/TMP/USERPROFILE 会丢;TMP 缺失会让 Java tmpdir 回落 C:\Windows
if "%TMP%"=="" set "TMP=%~dp0..\build\tmp"
if "%TEMP%"=="" set "TEMP=%TMP%"
if not exist "%TMP%" mkdir "%TMP%"
if "%JAVA_HOME%"=="" for /d %%d in ("%USERPROFILE%\.jdks\jdk-25*" "C:\Program Files\Eclipse Adoptium\jdk-25*" "C:\Program Files\Java\jdk-25*" "C:\Program Files\Microsoft\jdk-25*" "C:\Program Files\Zulu\zulu-25*" "C:\Program Files\Amazon Corretto\jdk25*") do set "JAVA_HOME=%%d"
if "%JAVA_HOME%"=="" for /d %%u in (C:\Users\*) do for /d %%d in ("%%u\.jdks\jdk-25*") do set "JAVA_HOME=%%d"

if /i not "%~1"=="--skip-build" (
  pushd web && call npm run build && popd || exit /b 1
  call gradlew.bat :server:shadowJar || exit /b 1
)

rem 取最新的 dq-tool-*.fat jar(排除 plain 存档 jar)
set JAR=
for /f "delims=" %%f in ('dir /b /o-d server\build\libs\dq-tool-*.jar ^| findstr /v plain') do (
  if not defined JAR set JAR=server\build\libs\%%f
)
rem 注意:单行 if (...) 块内不要直接写中文(原因见 scripts\package-win.bat 注释),多行块则安全
if not defined JAR (
  echo 找不到 server fat jar,请先执行 gradlew.bat :server:shadowJar
  exit /b 1
)

set RES=tauri\src-tauri\resources
rem PLACEHOLDER.txt 保留:tauri.conf.json 的 bundle.resources glob 要求 resources\ 下至少有一个非隐藏文件
if exist "%RES%\backend" rmdir /s /q "%RES%\backend"
if exist "%RES%\jre" rmdir /s /q "%RES%\jre"
mkdir "%RES%\backend"
copy "%JAR%" "%RES%\backend\dq-tool.jar" >nul

rem 内嵌完整 JRE 而非 jlink 裁剪(原因同 scripts\package-win.bat:JDBC 驱动反射,jdeps
rem 覆盖不全;实测达梦驱动要 jdk.charsets 的 EUC-KR),只删开发工具(bin 工具启动器 + jmods);
rem 复制+裁剪不依赖 jmods(部分 JDK 发行版无 jmods,jlink 直接不可用)
if "%JAVA_HOME%"=="" (
  echo 需要设置 JAVA_HOME 指向完整 JDK 25+
  exit /b 1
)
xcopy "%JAVA_HOME%" "%RES%\jre\" /E /I /Q >nul || exit /b 1
if exist "%RES%\jre\jmods" rmdir /s /q "%RES%\jre\jmods"
for %%t in (javac javadoc javap jar jarsigner serialver jconsole jdb jdeprscan jdeps jfr jhsdb jimage jinfo jlink jmap jmod jpackage jps jrunscript jshell jstack jstat jstatd jwebserver jcmd jnativescan) do if exist "%RES%\jre\bin\%%t.exe" del /q "%RES%\jre\bin\%%t.exe"

rem 冒烟:内嵌 jre 能正常启动即可(完整验证以安装后双击启动为准)
"%RES%\jre\bin\java.exe" -version || exit /b 1

pushd tauri
if not exist node_modules (call npm ci || (popd & exit /b 1))
rem npm 参数透传要用 "npm run <script> -- <args>" 形式,否则 --bundles 会被当成 cargo 参数
call npm run tauri -- build --bundles nsis || (popd & exit /b 1)
popd

echo 产物: tauri\src-tauri\target\release\bundle\nsis\
endlocal
