@echo off
rem Windows 打包脚本:构建前端 + fat jar,再用 jpackage 生成带内嵌 JRE 的 .exe 安装包
rem 前置要求: JDK 21+ (含 jpackage)、Maven、Node 18+;生成 exe 安装包还需 WiX Toolset 3.x
rem 若无 WiX,把下面的 --type exe 改为 --type app-image,产物为免安装的绿色目录
setlocal
cd /d %~dp0\..

if /i not "%~1"=="--skip-build" (
  pushd web && call npm run build && popd || exit /b 1
  call mvn -q -DskipTests package || exit /b 1
)

rem 版本号需与 pom.xml 保持一致
set APP_VERSION=0.1.0
rem exe 安装包要求主版本号 >= 1,去掉开头的 "0."(0.1.0 -> 1.0)
set PKG_VERSION=1.0
set JAR=target\dq-tool-%APP_VERSION%.jar
if not exist "%JAR%" (echo 找不到 %JAR%,请先执行 mvn package & exit /b 1)

set INPUT=target\jpackage\input
set DIST=target\jpackage\dist
if exist "%INPUT%" rmdir /s /q "%INPUT%"
mkdir "%INPUT%"
copy "%JAR%" "%INPUT%\" >nul

rem 数据目录:安装到 Program Files 后目录不可写,改为存到 %%USERPROFILE%%\.dq-tool\data
rem (${user.home} 由 Spring 在运行时解析)
jpackage ^
  --type exe ^
  --name dq-tool ^
  --app-version %PKG_VERSION% ^
  --input "%INPUT%" ^
  --main-jar dq-tool-%APP_VERSION%.jar ^
  --main-class org.springframework.boot.loader.launch.JarLauncher ^
  --java-options "-Dspring.datasource.url=jdbc:h2:file:${user.home}/.dq-tool/data/dqconfig;AUTO_SERVER=TRUE" ^
  --java-options "-Djava.awt.headless=false" ^
  --win-console ^
  --dest "%DIST%" || exit /b 1

echo 产物: %DIST%\dq-tool-%PKG_VERSION%.exe
endlocal
