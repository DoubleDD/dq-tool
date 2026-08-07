@echo off
rem Windows 打包脚本:构建前端 + fat jar,再用 jpackage 生成带内嵌 JRE 的免安装 zip
rem 前置要求: JDK 21+ (含 jpackage)、Node 18+
setlocal
cd /d %~dp0\..

if /i not "%~1"=="--skip-build" (
  pushd web && call npm run build && popd || exit /b 1
  call gradlew.bat :server:shadowJar || exit /b 1
)

rem 版本号需与 server/build.gradle.kts 保持一致
set APP_VERSION=0.1.6
rem 安装包版本与其他平台口径一致:从 APP_VERSION 派生,去掉开头的 "0."(0.1.6 -> 1.6),避免两处手工同步遗漏
set PKG_VERSION=%APP_VERSION:0.=%
set JAR=server\build\libs\dq-tool-%APP_VERSION%.jar
if not exist "%JAR%" (echo 找不到 %JAR%,请先执行 gradlew.bat :server:shadowJar & exit /b 1)

set INPUT=server\build\jpackage\input
set DIST=server\build\jpackage\dist
if exist "%INPUT%" rmdir /s /q "%INPUT%"
mkdir "%INPUT%"
copy "%JAR%" "%INPUT%\" >nul

rem 免安装绿色目录(app-image),解压后双击 dq-tool.exe 即用
rem 数据目录固定为 %%USERPROFILE%%\.dq-tool\data(${user.home} 由应用启动时展开,见 ConfigLoader)
rem 内嵌完整 JRE 而非 jdeps/jlink 裁剪:JDBC 驱动大量反射/按名加载(实测:达梦驱动初始化要
rem jdk.charsets 的 EUC-KR,jdeps 探测不到,裁剪后运行时才炸),运行库模块一个不动;
rem 只删开发工具(bin 工具启动器 + jmods),不把 JDK 分发给最终用户
if "%JAVA_HOME%"=="" (echo 需要设置 JAVA_HOME 指向完整 JDK 25+ & exit /b 1)
set JRE=server\build\jpackage\jre
if exist "%JRE%" rmdir /s /q "%JRE%"
xcopy "%JAVA_HOME%" "%JRE%\" /E /I /Q >nul || exit /b 1
if exist "%JRE%\jmods" rmdir /s /q "%JRE%\jmods"
for %%t in (javac javadoc javap jar jarsigner serialver jconsole jdb jdeprscan jdeps jfr jhsdb jimage jinfo jlink jmap jmod jpackage jps jrunscript jshell jstack jstat jstatd jwebserver jcmd jnativescan) do if exist "%JRE%\bin\%%t.exe" del /q "%JRE%\bin\%%t.exe"
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
  --dest "%DIST%" || exit /b 1

rem 打成 zip 便于分发
powershell -NoProfile -Command "Compress-Archive -Force -Path '%DIST%\dq-tool' -DestinationPath '%DIST%\dq-tool-%PKG_VERSION%.zip'" || exit /b 1

echo 产物: %DIST%\dq-tool-%PKG_VERSION%.zip
endlocal
