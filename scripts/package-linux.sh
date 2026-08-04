#!/usr/bin/env bash
# Linux 打包脚本:构建前端 + fat jar,再用 jpackage 生成带内嵌 JRE 的 .deb
# 前置要求: JDK 21+ (含 jpackage)、Maven、Node 18+、fakeroot(deb 打包需要)
# 用法: scripts/package-linux.sh [--skip-build]
# 需要 rpm 时把 --type deb 改为 --type rpm(需安装 rpm-build)
set -euo pipefail
cd "$(dirname "$0")/.."

if [[ "${1:-}" != "--skip-build" ]]; then
  (cd web && npm run build)
  mvn -q -DskipTests package
fi

APP_VERSION=$(mvn -q help:evaluate -Dexpression=project.version -DforceStdout)
# 与其他平台安装包版本保持一致,去掉开头的 "0."(0.1.0 -> 1.0)
PKG_VERSION="${APP_VERSION#0.}"
JAR="target/dq-tool-${APP_VERSION}.jar"
[[ -f "$JAR" ]] || { echo "找不到 $JAR,请先执行 mvn package" >&2; exit 1; }

INPUT=target/jpackage/input
DIST=target/jpackage/dist
rm -rf "$INPUT" && mkdir -p "$INPUT"
cp "$JAR" "$INPUT/"

# 数据目录存到 ~/.dq-tool/data(${user.home} 由 Spring 在运行时解析)
jpackage \
  --type deb \
  --name dq-tool \
  --app-version "$PKG_VERSION" \
  --linux-package-name dq-tool \
  --linux-app-category utility \
  --input "$INPUT" \
  --main-jar "dq-tool-${APP_VERSION}.jar" \
  --main-class org.springframework.boot.loader.launch.JarLauncher \
  --java-options '-Dspring.datasource.url=jdbc:h2:file:${user.home}/.dq-tool/data/dqconfig;AUTO_SERVER=TRUE' \
  --java-options '-Djava.awt.headless=false' \
  --dest "$DIST"

echo "产物: $DIST/dq-tool_${PKG_VERSION}_amd64.deb"
