#!/usr/bin/env bash
# 一键修改版本号:更新 VERSION 文件并同步 tauri.conf.json
# 用法: scripts/bump-version.sh 0.1.8(或 0.1.7.1 四段补丁号)
set -euo pipefail

if [[ $# -ne 1 ]]; then
  echo "用法: scripts/bump-version.sh <版本号>"
  echo "示例: scripts/bump-version.sh 0.1.8"
  echo "      scripts/bump-version.sh 0.1.7.1(四段补丁版本)"
  exit 1
fi

NEW_VERSION="$1"

# 校验格式: x.y.z 或 x.y.z.w
if [[ ! "$NEW_VERSION" =~ ^[0-9]+\.[0-9]+\.[0-9]+(\.[0-9]+)?$ ]]; then
  echo "错误: 版本号格式应为 x.y.z(如 0.1.8)或 x.y.z.w(如 0.1.7.1),当前输入: $NEW_VERSION"
  exit 1
fi

ROOT="$(cd "$(dirname "$0")/.." && pwd)"

# 1. 更新 VERSION 文件(唯一源头)
echo "$NEW_VERSION" > "$ROOT/VERSION"

# 2. 派生 tauri 版本号:去掉 "0." 前缀;三段补 ".0"(0.1.8 -> 1.8.0),四段直接用(0.1.7.1 -> 1.7.1)
BASE_VERSION="${NEW_VERSION#0.}"
if [[ "$NEW_VERSION" =~ ^[0-9]+\.[0-9]+\.[0-9]+\.[0-9]+$ ]]; then
  TAURI_VERSION="$BASE_VERSION"
else
  TAURI_VERSION="$BASE_VERSION.0"
fi

# 3. 同步 tauri.conf.json(tauri CLI 构建时直接读 JSON,不能动态读取,须写字面量)
CONF="$ROOT/tauri/src-tauri/tauri.conf.json"
sed -i.bak "s/\"version\": \".*\"/\"version\": \"$TAURI_VERSION\"/" "$CONF"
rm -f "$CONF.bak"

echo "版本号已更新:"
echo "  VERSION 文件:       $NEW_VERSION"
echo "  tauri.conf.json:    $TAURI_VERSION"
echo ""
echo "后续操作:"
echo "  git add VERSION tauri/src-tauri/tauri.conf.json"
echo "  git commit -m 'chore: 版本号升至 ${NEW_VERSION#0.}'"
echo "  git tag v${NEW_VERSION#0.}"
echo "  git push origin main && git push origin v${NEW_VERSION#0.}"
