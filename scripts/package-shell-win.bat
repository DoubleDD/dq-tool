@echo off
rem shell 模块(JCEF 桌面壳)Windows 打包脚本:构建前端 + shell fat jar(内嵌当前平台 JCEF natives),
rem 再用 jpackage 生成带内嵌 JRE 的免安装 zip(app-image)
rem 前置要求: JDK 25+ (含 jpackage)、Node 24+
setlocal
cd /d %~dp0\..

if /i not "%~1"=="--skip-build" (
  pushd web && call npm run build && popd || exit /b 1
  call gradlew.bat :shell:shadowJar || exit /b 1
)

rem 版本号需与 shell/build.gradle.kts 保持一致
set APP_VERSION=0.1.0
rem 与其他平台安装包版本保持一致,去掉开头的 "0."(0.1.0 -> 1.0)
set PKG_VERSION=1.0
set JAR=shell\build\libs\dq-tool-shell-%APP_VERSION%.jar
if not exist "%JAR%" (echo 找不到 %JAR%,请先执行 gradlew.bat :shell:shadowJar & exit /b 1)

set INPUT=shell\build\jpackage\input
set DIST=shell\build\jpackage\dist
if exist "%INPUT%" rmdir /s /q "%INPUT%"
mkdir "%INPUT%"
copy "%JAR%" "%INPUT%\" >nul

rem 免安装绿色目录(app-image),解压后双击 dq-tool-shell.exe 即用
rem 数据目录固定为 %%USERPROFILE%%\.dq-tool\data(${user.home} 由应用启动时展开,见 ConfigLoader)
rem JCEF natives 已在 fat jar 内(运行时 jcefmaven 解压到 %%USERPROFILE%%\.dq-tool\jcef-bundle);
rem 三个 --add-opens 是 JDK 16+ 上 JCEF 的硬性要求(非 macOS 加了也无害);
rem 不注入 -Djava.awt.headless:shell 需要图形环境,默认即 headful
rem
rem 诊断「Failed to launch JVM」:该弹窗发生在 JVM 创建之前,Java 侧日志(startup.log)记不到。
rem 先 set WITH_CONSOLE=1 再运行本脚本,打出的 exe 带控制台窗口,JVM 初始化失败的真实原因
rem (不支持的 VM 选项、运行环境损坏、类版本不匹配等)会直接打印在控制台
set CONSOLE_OPT=
if defined WITH_CONSOLE set CONSOLE_OPT=--win-console
jpackage ^
  --type app-image ^
  --name dq-tool-shell ^
  --app-version %PKG_VERSION% ^
  --input "%INPUT%" ^
  --main-jar dq-tool-shell-%APP_VERSION%.jar ^
  --main-class com.example.dq.shell.MainKt ^
  --java-options "-Ddq.data-dir=${user.home}/.dq-tool/data" ^
  --java-options "-XX:+UseZGC" ^
  --java-options "--add-opens java.desktop/sun.awt=ALL-UNNAMED" ^
  --java-options "--add-opens java.desktop/sun.lwawt=ALL-UNNAMED" ^
  --java-options "--add-opens java.desktop/sun.lwawt.macosx=ALL-UNNAMED" ^
  %CONSOLE_OPT% ^
  --dest "%DIST%" || exit /b 1

rem 打成 zip 便于分发
powershell -NoProfile -Command "Compress-Archive -Force -Path '%DIST%\dq-tool-shell' -DestinationPath '%DIST%\dq-tool-shell-%PKG_VERSION%.zip'" || exit /b 1

echo 产物: %DIST%\dq-tool-shell-%PKG_VERSION%.zip
endlocal
