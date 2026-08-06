#!/usr/bin/env bash
# Linux 打包脚本:构建前端 + fat jar,再用 jpackage 生成带内嵌 JRE 的 .deb / .rpm
# 前置要求: JDK 21+ (含 jpackage)、Node 18+;deb 需要 fakeroot,rpm 需要 rpmbuild
# 用法: scripts/package-linux.sh [--skip-build] [--type deb|rpm](默认 deb)
set -euo pipefail
cd "$(dirname "$0")/.."

SKIP_BUILD=0
PKG_TYPE=deb
while [[ $# -gt 0 ]]; do
  case "$1" in
    --skip-build) SKIP_BUILD=1; shift ;;
    --type) PKG_TYPE="${2:?--type 需要参数 deb 或 rpm}"; shift 2 ;;
    *) echo "未知参数: $1" >&2; exit 1 ;;
  esac
done
[[ "$PKG_TYPE" == deb || "$PKG_TYPE" == rpm ]] || { echo "--type 只支持 deb 或 rpm" >&2; exit 1; }

if [[ "$SKIP_BUILD" == 0 ]]; then
  (cd web && npm run build)
  ./gradlew :server:bootJar
fi

APP_VERSION=$(sed -n 's/^version = "\(.*\)"/\1/p' server/build.gradle.kts | head -1)
# 与其他平台安装包版本保持一致,去掉开头的 "0."(0.1.0 -> 1.0)
PKG_VERSION="${APP_VERSION#0.}"
JAR="server/build/libs/dq-tool-${APP_VERSION}.jar"
[[ -f "$JAR" ]] || { echo "找不到 $JAR,请先执行 ./gradlew :server:bootJar" >&2; exit 1; }

INPUT=server/build/jpackage/input
DIST=server/build/jpackage/dist
rm -rf "$INPUT" && mkdir -p "$INPUT"
cp "$JAR" "$INPUT/"

# 数据目录存到 ~/.dq-tool/data(${user.home} 由 Spring 在运行时解析)
jpackage \
  --type "$PKG_TYPE" \
  --name dq-tool \
  --app-version "$PKG_VERSION" \
  --linux-package-name dq-tool \
  --linux-app-category utility \
  --input "$INPUT" \
  --main-jar "dq-tool-${APP_VERSION}.jar" \
  --main-class org.springframework.boot.loader.launch.JarLauncher \
  --java-options '-Ddq.data-dir=${user.home}/.dq-tool/data' \
  --java-options '-Djava.awt.headless=false' \
  --dest "$DIST"

if [[ "$PKG_TYPE" == deb ]]; then
  echo "产物: $DIST/dq-tool_${PKG_VERSION}_amd64.deb"
else
  echo "产物: $DIST/dq-tool-${PKG_VERSION}-1.x86_64.rpm"
fi
