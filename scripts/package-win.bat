@echo off
rem Windows 打包脚本:构建前端 + fat jar,再用 jpackage 生成带内嵌 JRE 的免安装 zip
rem 前置要求: JDK 21+ (含 jpackage)、Maven、Node 18+
setlocal
cd /d %~dp0\..

if /i not "%~1"=="--skip-build" (
  pushd web && call npm run build && popd || exit /b 1
  call mvn -q -DskipTests package || exit /b 1
)

rem 版本号需与 pom.xml 保持一致
set APP_VERSION=0.1.3
rem 与其他平台安装包版本保持一致,去掉开头的 "0."(0.1.3 -> 1.3)
set PKG_VERSION=1.3
set JAR=target\dq-tool-%APP_VERSION%.jar
if not exist "%JAR%" (echo 找不到 %JAR%,请先执行 mvn package & exit /b 1)

set INPUT=target\jpackage\input
set DIST=target\jpackage\dist
if exist "%INPUT%" rmdir /s /q "%INPUT%"
mkdir "%INPUT%"
copy "%JAR%" "%INPUT%\" >nul

rem 免安装绿色目录(app-image),解压后双击 dq-tool.exe 即用
rem 数据目录固定为 %%USERPROFILE%%\.dq-tool\data(${user.home} 由 Spring 在运行时解析)
jpackage ^
  --type app-image ^
  --name dq-tool ^
  --app-version %PKG_VERSION% ^
  --input "%INPUT%" ^
  --main-jar dq-tool-%APP_VERSION%.jar ^
  --main-class org.springframework.boot.loader.launch.JarLauncher ^
  --java-options "-Ddq.data-dir=${user.home}/.dq-tool/data" ^
  --java-options "-Djava.awt.headless=false" ^
  --dest "%DIST%" || exit /b 1

rem 打成 zip 便于分发
powershell -NoProfile -Command "Compress-Archive -Force -Path '%DIST%\dq-tool' -DestinationPath '%DIST%\dq-tool-%PKG_VERSION%.zip'" || exit /b 1

echo 产物: %DIST%\dq-tool-%PKG_VERSION%.zip
endlocal
