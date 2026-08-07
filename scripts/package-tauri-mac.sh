#!/usr/bin/env bash
# tauri 模块(Tauri 2 桌面壳)macOS 打包脚本:构建前端 + server fat jar,
# 完整 JRE 与 jar 一起作为 Tauri bundle resources 打进 .app,
# 产出 .dmg(运行时由 Rust 侧车拉起 内嵌 jre/bin/java -jar,见 tauri/src-tauri/src/main.rs)
# 用法: scripts/package-tauri-mac.sh [--skip-build]
#
# TODO: Windows / Linux 打包脚本本次未实现。要点:
#   - Tauri 不依赖 jpackage,`npm run tauri build` 在各平台原生构建(不支持交叉编译);
#     Windows 用 --bundles nsis/msi,Linux 用 --bundles deb/appimage
#   - resources/jre 需替换为对应平台的完整 JRE(Windows 上 java 是 bin/java.exe)
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

# 内嵌完整 JRE 而非 jlink 裁剪(原因同 jpackage 脚本:JDBC 驱动大量反射/按名加载,
# 实测达梦驱动初始化要 jdk.charsets 的 EUC-KR,裁剪后运行时才炸),运行库模块一个不动,
# 只删开发工具(bin 工具启动器 + jmods);复制+裁剪不依赖 jmods(部分 JDK 发行版无 jmods)
JDK_HOME="${JAVA_HOME:-$(/usr/libexec/java_home -v 25 2>/dev/null || true)}"
[[ -n "$JDK_HOME" ]] || { echo "找不到 JDK 25,请设置 JAVA_HOME" >&2; exit 1; }
cp -R "$JDK_HOME" "$RES/jre"
rm -rf "$RES/jre/jmods"
rm -f "$RES"/jre/bin/{javac,javadoc,javap,jar,jarsigner,serialver,jconsole,jdb,jdeprscan,jdeps,jfr,jhsdb,jimage,jinfo,jlink,jmap,jmod,jpackage,jps,jrunscript,jshell,jstack,jstat,jstatd,jwebserver,jcmd,jnativescan}

# 冒烟:内嵌 jre 能正常启动即可(完整验证以打包后双击启动为准)
"$RES/jre/bin/java" -version

# npm 参数透传要用 "npm run <script> -- <args>" 形式,否则 --bundles 会被当成 cargo 参数
(cd tauri && npm install && npm run tauri -- build --bundles dmg)

echo "产物: tauri/src-tauri/target/release/bundle/dmg/"
