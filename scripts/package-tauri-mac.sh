#!/usr/bin/env bash
# tauri 模块(Tauri 2 桌面壳)macOS 打包脚本:构建前端 + server fat jar,
# 用 jlink 从本机 JDK 25 裁出运行时,与 jar 一起作为 Tauri bundle resources 打进 .app,
# 产出 .dmg(运行时由 Rust 侧车拉起 内嵌 jre/bin/java -jar,见 tauri/src-tauri/src/main.rs)
# 用法: scripts/package-tauri-mac.sh [--skip-build]
#
# TODO: Windows / Linux 打包脚本本次未实现。要点:
#   - Tauri 不依赖 jpackage,`npm run tauri build` 在各平台原生构建(不支持交叉编译);
#     Windows 用 --bundles nsis/msi,Linux 用 --bundles deb/appimage
#   - resources/jre 需替换为对应平台的 jlink 产物(Windows 上 java 是 bin/java.exe)
set -euo pipefail
cd "$(dirname "$0")/.."

if [[ "${1:-}" != "--skip-build" ]]; then
  (cd web && npm run build)
  ./gradlew :server:shadowJar
fi

# 与 tauri/src-tauri/tauri.conf.json 的 version 保持同一映射:项目版本 0.x.y -> 安装包 x.y
JAR=$(ls -t server/build/libs/dq-tool-*.jar | grep -v plain | head -1)
[[ -f "$JAR" ]] || { echo "找不到 server fat jar,请先执行 ./gradlew :server:shadowJar" >&2; exit 1; }

RES=tauri/src-tauri/resources
# PLACEHOLDER.txt 保留:tauri.conf.json 的 bundle.resources glob 要求 resources/ 下至少有一个非隐藏文件
rm -rf "$RES/backend" "$RES/jre"
mkdir -p "$RES/backend"
cp "$JAR" "$RES/backend/dq-tool.jar"

# jlink 裁剪运行时:fat jar 是未命名模块,--add-modules 需显式覆盖运行所需 JDK 模块:
#   java.desktop    AWT 类被引用(BrowserOpener/TrayManager,headless 下不激活但类要可加载)
#   java.sql/java.naming/java.management  HikariCP + JDBC
#   java.logging    H2 日志;java.xml    H2/Jackson 3 依赖
#   jdk.crypto.ec   授权码 Ed25519 验签;jdk.crypto.cryptoki  SunEC 等加密提供者注册表
#   jdk.zipfs       shadow jar 内资源访问兜底;jdk.unsupported  部分库反射 Unsafe
# 模块不全的典型症状是启动报 NoClassDefFoundError/Provider 缺失,届时按报错补 --add-modules
JAVA_HOME_DETECTED=$(/usr/libexec/java_home -v 25 2>/dev/null || true)
JLINK="${JAVA_HOME_DETECTED:+$JAVA_HOME_DETECTED/bin/jlink}"
JLINK="${JLINK:-$(command -v jlink)}"
"$JLINK" \
  --add-modules java.base,java.desktop,java.sql,java.naming,java.management,java.logging,java.xml,jdk.crypto.ec,jdk.crypto.cryptoki,jdk.zipfs,jdk.unsupported \
  --strip-debug --no-man-pages --no-header-files --compress=zip-6 \
  --output "$RES/jre"

# 冒烟:内嵌 jre 能正常启动即模块裁剪无明显缺失(完整验证以打包后双击启动为准)
"$RES/jre/bin/java" -version

(cd tauri && npm install && npm run tauri build -- --bundles dmg)

echo "产物: tauri/src-tauri/target/release/bundle/dmg/"
