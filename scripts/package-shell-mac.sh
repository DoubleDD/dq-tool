#!/usr/bin/env bash
# shell 模块(JCEF 桌面壳)macOS 打包脚本:构建前端 + shell fat jar(内嵌当前平台 JCEF natives),
# 再用 jpackage 生成带内嵌 JRE 的 .dmg
# 用法: scripts/package-shell-mac.sh [--skip-build]
#
# TODO: Linux 打包脚本未实现(Windows 已有 scripts\package-shell-win.bat)。要点:
#   - natives 构件按平台区分(jcef-natives-windows-amd64 / linux-amd64 等),
#     shell/build.gradle.kts 按构建机器自动选择,需在对应平台上分别打包(jpackage 不支持交叉编译)
#   - Linux 参考 scripts/package-linux.sh
set -euo pipefail
cd "$(dirname "$0")/.."

if [[ "${1:-}" != "--skip-build" ]]; then
  (cd web && npm run build)
  # shell 的 shadowJar 经 project(":server") 依赖自动带上 server/common 与前端静态资源
  ./gradlew :shell:shadowJar
fi

APP_VERSION=$(sed -n 's/^version = "\(.*\)"/\1/p' shell/build.gradle.kts | head -1)
# dmg 要求主版本号 >= 1,去掉开头的 "0."(0.1.0 -> 1.0)
PKG_VERSION="${APP_VERSION#0.}"
JAR="shell/build/libs/dq-tool-shell-${APP_VERSION}.jar"
[[ -f "$JAR" ]] || { echo "找不到 $JAR,请先执行 ./gradlew :shell:shadowJar" >&2; exit 1; }

INPUT=shell/build/jpackage/input
DIST=shell/build/jpackage/dist
rm -rf "$INPUT" && mkdir -p "$INPUT"
cp "$JAR" "$INPUT/"

# JCEF natives 已在 fat jar 内(tar.gz),运行时由 jcefmaven 解压到 ~/.dq-tool/jcef-bundle
# (jpackage 安装目录只读,不能放应用内),java.library.path 由 jcefmaven 运行时补丁,无需额外配置;
# --add-opens 三个 java.desktop 包是 JDK 16+ 上 JCEF(macOS)的硬性要求,与应用内嵌 JRE 版本无关
# 数据目录与安装版约定一致:~/.dq-tool/data(${user.home} 由应用启动时展开,见 ConfigLoader)
# 不注入 -Djava.awt.headless:shell 需要图形环境,默认即 headful
# 内嵌完整 JRE 而非 jdeps/jlink 裁剪(原因同 scripts/package-mac.sh:JDBC 驱动反射,jdeps 覆盖不全;
# 实测达梦驱动要 jdk.charsets 的 EUC-KR),只删开发工具(bin 工具启动器 + jmods)
JDK_HOME="${JAVA_HOME:-$(dirname "$(dirname "$(command -v jpackage)")")}"
JRE=shell/build/jpackage/jre
rm -rf "$JRE" && cp -R "$JDK_HOME" "$JRE"
rm -rf "$JRE/jmods"
rm -f "$JRE"/bin/{javac,javadoc,javap,jar,jarsigner,serialver,jconsole,jdb,jdeprscan,jdeps,jfr,jhsdb,jimage,jinfo,jlink,jmap,jmod,jpackage,jps,jrunscript,jshell,jstack,jstat,jstatd,jwebserver,jcmd,jnativescan}
jpackage \
  --type dmg \
  --name dq-tool-shell \
  --app-version "$PKG_VERSION" \
  --mac-package-identifier com.example.dqtool.shell \
  --runtime-image "$JRE" \
  --input "$INPUT" \
  --main-jar "dq-tool-shell-${APP_VERSION}.jar" \
  --main-class com.example.dq.shell.MainKt \
  --java-options '-Ddq.data-dir=${user.home}/.dq-tool/data' \
  --java-options '-XX:+UseZGC' \
  --java-options '--add-opens java.desktop/sun.awt=ALL-UNNAMED' \
  --java-options '--add-opens java.desktop/sun.lwawt=ALL-UNNAMED' \
  --java-options '--add-opens java.desktop/sun.lwawt.macosx=ALL-UNNAMED' \
  --dest "$DIST"

echo "产物: $DIST/dq-tool-shell-${PKG_VERSION}.dmg"
