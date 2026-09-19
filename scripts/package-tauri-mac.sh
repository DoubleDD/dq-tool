#!/usr/bin/env bash
# tauri 模块(Tauri 2 桌面壳)macOS 打包脚本:构建前端 + server fat jar,
# 完整 JRE(runtime 层)与业务层(jar + 前端,版本化目录 resources/versions/<v>/ +
# current 指针)作为 Tauri bundle resources 打进 .app,
# 产出 .dmg 与自动更新包 .app.tar.gz + .sig(--bundles app,dmg;
# 运行时由 Rust 侧车从 versions/current 指向的版本目录拉起 jre/bin/java -jar,
# 并经 dq:// 协议从同目录 static/ 直载前端,见 tauri/src-tauri/src/)
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

JAR=$(ls -t server/build/libs/dq-tool-*.jar | grep -v plain | head -1)
[[ -f "$JAR" ]] || { echo "找不到 server fat jar,请先执行 ./gradlew :server:shadowJar" >&2; exit 1; }

# 版本号从 VERSION 文件读取(唯一源头),去开头 "0." 前缀:0.2.0.14 -> 2.0.14
# (${VAR#0.} 是前缀匹配;批处理脚本的全局替换坑在 shell 里不存在)
APP_VERSION=$(cat VERSION)
APP_VERSION="${APP_VERSION#0.}"

RES=tauri/src-tauri/resources
# PLACEHOLDER.txt 保留:tauri.conf.json 的 bundle.resources glob 要求 resources/ 下至少有一个非隐藏文件
rm -rf "$RES/backend" "$RES/versions" "$RES/jre"
# 业务层版本化目录(与 scripts\package-tauri-win.bat 同布局):versions/<v>/dq-tool.jar +
# versions/<v>/static/(web 构建产物)+ versions/current 指针 + 包内 manifest.json
mkdir -p "$RES/versions/$APP_VERSION"
cp "$JAR" "$RES/versions/$APP_VERSION/dq-tool.jar"
cp -R web/dist "$RES/versions/$APP_VERSION/static"
echo "$APP_VERSION" > "$RES/versions/current"
echo "{\"version\": \"$APP_VERSION\"}" > "$RES/versions/$APP_VERSION/manifest.json"

# 内嵌完整 JRE 而非 jlink 裁剪(原因同 jpackage 脚本:JDBC 驱动大量反射/按名加载,
# 实测达梦驱动初始化要 jdk.charsets 的 EUC-KR,裁剪后运行时才炸),运行库模块一个不动,
# 只删开发工具(bin 工具启动器 + jmods);复制+裁剪不依赖 jmods(部分 JDK 发行版无 jmods)
JDK_HOME="${JAVA_HOME:-$(/usr/libexec/java_home -v 25 2>/dev/null || true)}"
[[ -n "$JDK_HOME" ]] || { echo "找不到 JDK 25,请设置 JAVA_HOME" >&2; exit 1; }
cp -R "$JDK_HOME" "$RES/jre"
# JDK 源文件大量只读(legal/ 等 r--r--r--):tauri-build 会把 resources 复制到
# target/release/resources 且保留权限,再次构建覆盖只读旧文件即 EACCES,这里统一加 u+w
chmod -R u+w "$RES/jre"
rm -rf "$RES/jre/jmods"
rm -f "$RES"/jre/bin/{javac,javadoc,javap,jar,jarsigner,serialver,jconsole,jdb,jdeprscan,jdeps,jfr,jhsdb,jimage,jinfo,jlink,jmap,jmod,jpackage,jps,jrunscript,jshell,jstack,jstat,jstatd,jwebserver,jcmd,jnativescan}

# 冒烟:内嵌 jre 能正常启动即可(完整验证以打包后双击启动为准)
"$RES/jre/bin/java" -version

# 自动更新签名私钥:未显式配置时从入库文件读取(与 CI release.yml 注入方式一致;文件单行无密码)
# 密码变量必须存在(可为空),否则 tauri CLI 会交互式询问密码,无终端环境下签名失败
export TAURI_SIGNING_PRIVATE_KEY="${TAURI_SIGNING_PRIVATE_KEY:-$(cat scripts/updater-private.key)}"
export TAURI_SIGNING_PRIVATE_KEY_PASSWORD="${TAURI_SIGNING_PRIVATE_KEY_PASSWORD-}"

# tauri 依赖已迁 pnpm(与 web 一致,lock 唯一来源 tauri/pnpm-lock.yaml);
# npm 参数透传要用 "npm run <script> -- <args>" 形式,否则 --bundles 会被当成 cargo 参数
(cd tauri && pnpm install --frozen-lockfile && npm run tauri -- build --bundles dmg)

# npm 参数透传要用 "npm run <script> -- <args>" 形式,否则 --bundles 会被当成 cargo 参数
# app target 产出自动更新包 bundle/macos/*.app.tar.gz + .sig(dmg 不支持 updater 产物)
(cd tauri && pnpm install --frozen-lockfile && npm run tauri -- build --bundles app,dmg)

echo "产物: tauri/src-tauri/target/release/bundle/dmg/(安装包)"
echo "      tauri/src-tauri/target/release/bundle/macos/(自动更新包 .app.tar.gz + .sig)"
