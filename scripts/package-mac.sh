#!/usr/bin/env bash
# macOS 打包脚本:构建前端 + fat jar,再用 jpackage 生成带内嵌 JRE 的 .dmg
# 用法: scripts/package-mac.sh [--skip-build]
set -euo pipefail
cd "$(dirname "$0")/.."

if [[ "${1:-}" != "--skip-build" ]]; then
  (cd web && npm run build)
  mvn -q -DskipTests package
fi

APP_VERSION=$(mvn -q help:evaluate -Dexpression=project.version -DforceStdout)
# dmg/msi 要求主版本号 >= 1,去掉开头的 "0."(0.1.0 -> 1.0)
PKG_VERSION="${APP_VERSION#0.}"
JAR="target/dq-tool-${APP_VERSION}.jar"
[[ -f "$JAR" ]] || { echo "找不到 $JAR,请先执行 mvn package" >&2; exit 1; }

INPUT=target/jpackage/input
DIST=target/jpackage/dist
rm -rf "$INPUT" && mkdir -p "$INPUT"
cp "$JAR" "$INPUT/"

# 数据目录:应用双击启动时工作目录不可写,改为存到 ~/.dq-tool/data
# (${user.home} 由 Spring 在运行时解析)
jpackage \
  --type dmg \
  --name dq-tool \
  --app-version "$PKG_VERSION" \
  --mac-package-identifier com.example.dqtool \
  --input "$INPUT" \
  --main-jar "dq-tool-${APP_VERSION}.jar" \
  --main-class org.springframework.boot.loader.launch.JarLauncher \
  --java-options '-Ddq.data-dir=${user.home}/.dq-tool/data' \
  --java-options '-Djava.awt.headless=false' \
  --java-options '-Dapple.awt.UIElement=true' \
  --dest "$DIST"

echo "产物: $DIST/dq-tool-${PKG_VERSION}.dmg"
