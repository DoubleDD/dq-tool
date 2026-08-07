@echo off
rem tauri 模块(Tauri 2 桌面壳)Windows 打包脚本:构建前端 + server fat jar,
rem 用 jlink 从本机 JDK 25 裁出运行时,与 jar 一起作为 Tauri bundle resources 打进安装包,
rem 产出 NSIS 安装程序(运行时由 Rust 侧车拉起 内嵌 jre\bin\java.exe -jar,见 tauri/src-tauri/src/main.rs)
rem 前置要求: JDK 25+ (含 jlink)、Node 24+、Rust(cargo)
setlocal
cd /d %~dp0\..

if /i not "%~1"=="--skip-build" (
  pushd web && call npm run build && popd || exit /b 1
  call gradlew.bat :server:shadowJar || exit /b 1
)

rem 取最新的 dq-tool-*.fat jar(排除 plain 存档 jar)
set JAR=
for /f "delims=" %%f in ('dir /b /o-d server\build\libs\dq-tool-*.jar ^| findstr /v plain') do (
  if not defined JAR set JAR=server\build\libs\%%f
)
if not defined JAR (echo 找不到 server fat jar,请先执行 gradlew.bat :server:shadowJar & exit /b 1)

set RES=tauri\src-tauri\resources
rem PLACEHOLDER.txt 保留:tauri.conf.json 的 bundle.resources glob 要求 resources\ 下至少有一个非隐藏文件
if exist "%RES%\backend" rmdir /s /q "%RES%\backend"
if exist "%RES%\jre" rmdir /s /q "%RES%\jre"
mkdir "%RES%\backend"
copy "%JAR%" "%RES%\backend\dq-tool.jar" >nul

rem jlink 裁剪运行时,模块列表与 scripts/package-tauri-mac.sh 保持一致;
rem 缺模块的典型症状是启动报 NoClassDefFoundError/Provider 缺失,按报错补 --add-modules
jlink ^
  --add-modules java.base,java.desktop,java.sql,java.naming,java.management,java.logging,java.xml,jdk.crypto.ec,jdk.crypto.cryptoki,jdk.zipfs,jdk.unsupported ^
  --strip-debug --no-man-pages --no-header-files --compress=zip-6 ^
  --output "%RES%\jre" || exit /b 1

rem 冒烟:内嵌 jre 能正常启动即模块裁剪无明显缺失(完整验证以安装后双击启动为准)
"%RES%\jre\bin\java.exe" -version || exit /b 1

pushd tauri
if not exist node_modules (call npm ci || (popd & exit /b 1))
rem npm 参数透传要用 "npm run <script> -- <args>" 形式,否则 --bundles 会被当成 cargo 参数
call npm run tauri -- build --bundles nsis || (popd & exit /b 1)
popd

echo 产物: tauri\src-tauri\target\release\bundle\nsis\
endlocal
