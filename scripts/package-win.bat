@echo off
rem Windows 打包脚本:构建前端 + fat jar,再用 jpackage 生成带内嵌 JRE 的免安装 zip
rem 前置要求: JDK 21+ (含 jpackage)、Node 18+
setlocal
cd /d %~dp0\..

rem 经 MSYS2 的 make 调用本脚本时环境变量会被剥光(JAVA_HOME/TMP/USERPROFILE 全丢),做两处兜底:
rem 1) TMP/TEMP 指向仓库构建目录,否则 Java 的 java.io.tmpdir 回落到 C:\Windows,Kotlin 编译写 .alive 文件直接 AccessDenied
if "%TMP%"=="" set "TMP=%~dp0..\build\tmp"
if "%TEMP%"=="" set "TEMP=%TMP%"
if not exist "%TMP%" mkdir "%TMP%"
rem 2) JAVA_HOME 为空时自动探测常见安装位置的 JDK 25+(Gradle toolchain 会自动扫 ~/.jdks,这里主要为 jpackage 的 --runtime-image)
if "%JAVA_HOME%"=="" for /d %%d in ("%USERPROFILE%\.jdks\jdk-25*" "C:\Program Files\Eclipse Adoptium\jdk-25*" "C:\Program Files\Java\jdk-25*" "C:\Program Files\Microsoft\jdk-25*" "C:\Program Files\Zulu\zulu-25*" "C:\Program Files\Amazon Corretto\jdk25*") do set "JAVA_HOME=%%d"
if "%JAVA_HOME%"=="" for /d %%u in (C:\Users\*) do for /d %%d in ("%%u\.jdks\jdk-25*") do set "JAVA_HOME=%%d"

if /i not "%~1"=="--skip-build" (
  pushd web && call npm run build && popd || exit /b 1
  call gradlew.bat :server:shadowJar || exit /b 1
)

rem 版本号从根目录 VERSION 文件读取(唯一源头)
for /f "delims=" %%a in (VERSION) do set APP_VERSION=%%a
rem 安装包版本与其他平台口径一致:从 APP_VERSION 派生,去掉开头的 "0."(0.1.7 -> 1.7),避免两处手工同步遗漏
set PKG_VERSION=%APP_VERSION:0.=%
set JAR=server\build\libs\dq-tool-%APP_VERSION%.jar
rem 注意:单行 if (...) 块内不要直接写中文 —— 本文件是 UTF-8,中文 Windows 上 cmd 按 GBK 解析,
rem 错位的字节配对可能吃掉括号/换行使块解析失败(报"文件名、目录名或卷标语法不正确"),多行块则安全
if not exist "%JAR%" (
  echo 找不到 %JAR%,请先执行 gradlew.bat :server:shadowJar
  exit /b 1
)

set INPUT=server\build\jpackage\input
set DIST=server\build\jpackage\dist
if exist "%INPUT%" rmdir /s /q "%INPUT%"
mkdir "%INPUT%"
copy "%JAR%" "%INPUT%\" >nul
rem 原生启动画面:打包进 app 镜像,启动时经 -splash:${APPDIR}/splash.png 立即显示(见下方 jpackage 参数)
copy "server\src\main\resources\splash.png" "%INPUT%\" >nul

rem 免安装绿色目录(app-image),解压后双击 dq-tool.exe 即用
rem 数据目录固定为 %%USERPROFILE%%\.dq-tool\data(${user.home} 由应用启动时展开,见 ConfigLoader)
rem 内嵌完整 JRE 而非 jdeps/jlink 裁剪:JDBC 驱动大量反射/按名加载(实测:达梦驱动初始化要
rem jdk.charsets 的 EUC-KR,jdeps 探测不到,裁剪后运行时才炸),运行库模块一个不动;
rem 只删开发工具(bin 工具启动器 + jmods),不把 JDK 分发给最终用户
if "%JAVA_HOME%"=="" (
  echo 需要设置 JAVA_HOME 指向完整 JDK 25+
  exit /b 1
)
set JRE=server\build\jpackage\jre
if exist "%JRE%" rmdir /s /q "%JRE%"
xcopy "%JAVA_HOME%" "%JRE%\" /E /I /Q >nul || exit /b 1
if exist "%JRE%\jmods" rmdir /s /q "%JRE%\jmods"
for %%t in (javac javadoc javap jar jarsigner serialver jconsole jdb jdeprscan jdeps jfr jhsdb jimage jinfo jlink jmap jmod jpackage jps jrunscript jshell jstack jstat jstatd jwebserver jcmd jnativescan) do if exist "%JRE%\bin\%%t.exe" del /q "%JRE%\bin\%%t.exe"
rem app-image 目标目录已存在时 jpackage 会直接报错,先清掉(重复打包场景)
if exist "%DIST%\dq-tool" rmdir /s /q "%DIST%\dq-tool"
jpackage ^
  --type app-image ^
  --name dq-tool ^
  --app-version %PKG_VERSION% ^
  --runtime-image "%JRE%" ^
  --input "%INPUT%" ^
  --main-jar dq-tool-%APP_VERSION%.jar ^
  --main-class com.example.dq.DqApplication ^
  --java-options "-Ddq.data-dir=${user.home}/.dq-tool/data" ^
  --java-options "-Djava.awt.headless=false" ^
  --java-options "-XX:+UseZGC" ^
  --java-options "-splash:${APPDIR}/splash.png" ^
  --dest "%DIST%" || exit /b 1

rem 打成 zip 便于分发
powershell -NoProfile -Command "Compress-Archive -Force -Path '%DIST%\dq-tool' -DestinationPath '%DIST%\dq-tool-%PKG_VERSION%.zip'" || exit /b 1

echo 产物: %DIST%\dq-tool-%PKG_VERSION%.zip
endlocal
