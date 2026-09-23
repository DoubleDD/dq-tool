#!/usr/bin/env bash
# 业务层更新包(分层分发的业务层:jar + 前端 static)单独打包脚本:
# 构建前端 + server fat jar → 组装 dq-tool.jar + static/ + manifest.json →
# 打成 dq-tool-<v>-business.zip(包布局与 CI release.yml 的 business 包一致)。
# 本机装有 minisign 时顺带用 scripts/updater-private.key 签出同名 .zip.sig(base64 单行),
# 并把「业务 zip + .sig」打成 dq-tool-<v>-business-bundle.zip 整包
# —— 系统设置页「选择升级包」手动离线升级只需选这一个整包,程序解包后自行验签。
# 用法: scripts/package-business.sh [--skip-build]
set -euo pipefail
cd "$(dirname "$0")/.."

if [[ "${1:-}" != "--skip-build" ]]; then
  (cd web && npm run build)
  ./gradlew :server:shadowJar
fi

JAR=$(ls -t server/build/libs/dq-tool-*.jar | grep -v plain | head -1)
[[ -f "$JAR" ]] || { echo "找不到 server fat jar,请先执行 ./gradlew :server:shadowJar" >&2; exit 1; }
[[ -f web/dist/index.html ]] || { echo "找不到前端产物 web/dist/index.html,请先执行 cd web && npm run build" >&2; exit 1; }

# 版本号从 VERSION 文件读取(唯一源头),去开头 "0." 前缀:0.2.0.14 -> 2.0.14
# (与 scripts/package-tauri-mac.sh、release.yml business 包同口径)
APP_VERSION=$(cat VERSION)
APP_VERSION="${APP_VERSION#0.}"

STAGE=build/business-staging
ZIP="build/dq-tool-$APP_VERSION-business.zip"
rm -rf "$STAGE"
mkdir -p "$STAGE"
# 包布局与 release.yml「Build business package」一致:zip 根下 dq-tool.jar + static/ + manifest.json
cp "$JAR" "$STAGE/dq-tool.jar"
cp -R web/dist "$STAGE/static"
echo "{\"version\": \"$APP_VERSION\"}" > "$STAGE/manifest.json"

rm -f "$ZIP" "$ZIP.sig"
(cd "$STAGE" && zip -qr -X "$OLDPWD/$ZIP" .)
rm -rf "$STAGE"

# 签名(可选):minisign 不在 PATH 时跳过(手动离线升级要求签名,跳过则不出整包);
# 私钥为空口令,minisign 无免交互参数,用 echo 喂空行当空口令(同 release.yml business-manifest 任务)
if command -v minisign >/dev/null 2>&1; then
  # macOS 的 base64 不吃位置参数(GNU 才认 -d <file>),统一走 stdin 重定向
  base64 -d < scripts/updater-private.key > build/.updater-private.key.tmp
  echo "" | minisign -S -s build/.updater-private.key.tmp -m "$ZIP"
  rm -f build/.updater-private.key.tmp
  base64 < "$ZIP.minisig" | tr -d '\n' > "$ZIP.sig"
  echo >> "$ZIP.sig"
  rm -f "$ZIP.minisig"
  # 整包:业务 zip + .sig 打成一个 zip,手动离线升级只需拷贝/选择这一个文件
  BUNDLE="build/dq-tool-$APP_VERSION-business-bundle.zip"
  rm -f "$BUNDLE"
  (cd build && zip -q -X "dq-tool-$APP_VERSION-business-bundle.zip" \
    "dq-tool-$APP_VERSION-business.zip" "dq-tool-$APP_VERSION-business.zip.sig")
  echo "产物: $ZIP"
  echo "      $ZIP.sig(minisign 签名,base64 单行)"
  echo "      $BUNDLE(整包:zip + sig,手动离线升级选它)"
else
  echo "产物: $ZIP"
  echo "(未找到 minisign,跳过签名与整包;手动离线升级需要整包时请装 minisign 后重打)"
fi
