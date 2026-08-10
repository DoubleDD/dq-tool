#!/usr/bin/env bash
# macOS 打包脚本:构建前端 + fat jar,再用 jpackage 生成带内嵌 JRE 的 .dmg
# 用法: scripts/package-mac.sh [--skip-build]
set -euo pipefail
cd "$(dirname "$0")/.."

if [[ "${1:-}" != "--skip-build" ]]; then
  (cd web && npm run build)
  ./gradlew :server:shadowJar
fi

APP_VERSION=$(cat VERSION)
# dmg/msi 要求主版本号 >= 1,去掉开头的 "0."(0.1.0 -> 1.0)
PKG_VERSION="${APP_VERSION#0.}"
JAR="server/build/libs/dq-tool-${APP_VERSION}.jar"
[[ -f "$JAR" ]] || { echo "找不到 $JAR,请先执行 ./gradlew :server:shadowJar" >&2; exit 1; }

INPUT=server/build/jpackage/input
DIST=server/build/jpackage/dist
rm -rf "$INPUT" && mkdir -p "$INPUT"
cp "$JAR" "$INPUT/"

# 数据目录:应用双击启动时工作目录不可写,改为存到 ~/.dq-tool/data
# (${user.home} 由应用启动时展开,见 ConfigLoader)
# 内嵌完整 JRE 而非 jdeps/jlink 裁剪:JDBC 驱动大量反射/按名加载(实测:达梦驱动初始化要
# jdk.charsets 的 EUC-KR,jdeps 探测不到,裁剪后运行时才炸),运行库模块一个不动;
# 只删开发工具(bin 工具启动器 + jmods),不把 JDK 分发给最终用户。
# 复制+裁剪不依赖 jmods(部分 JDK 发行版无 jmods,jlink 直接不可用)
JDK_HOME="${JAVA_HOME:-$(dirname "$(dirname "$(command -v jpackage)")")}"
JRE=server/build/jpackage/jre
rm -rf "$JRE" && cp -R "$JDK_HOME" "$JRE"
rm -rf "$JRE/jmods"
rm -f "$JRE"/bin/{javac,javadoc,javap,jar,jarsigner,serialver,jconsole,jdb,jdeprscan,jdeps,jfr,jhsdb,jimage,jinfo,jlink,jmap,jmod,jpackage,jps,jrunscript,jshell,jstack,jstat,jstatd,jwebserver,jcmd,jnativescan}
jpackage \
  --type dmg \
  --name dq-tool \
  --app-version "$PKG_VERSION" \
  --mac-package-identifier com.example.dqtool \
  --runtime-image "$JRE" \
  --input "$INPUT" \
  --main-jar "dq-tool-${APP_VERSION}.jar" \
  --main-class com.example.dq.DqApplication \
  --java-options '-Ddq.data-dir=${user.home}/.dq-tool/data' \
  --java-options '-XX:+UseZGC' \
  --java-options '-Djava.awt.headless=false' \
  --java-options '-Dapple.awt.UIElement=true' \
  --dest "$DIST"

echo "产物: $DIST/dq-tool-${PKG_VERSION}.dmg"
