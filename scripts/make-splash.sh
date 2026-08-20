#!/usr/bin/env bash
# 生成 JVM 原生启动画面 splash.png(方案一:点击图标即显示,盖住 JVM 启动 + 首次 AWT 初始化的黑屏期)。
# 用 JDK 11+ 单文件源码运行(scripts/SplashGen.java),无需编译产物。
# 产物: server/src/main/resources/splash.png(打进 fat jar;打包脚本再复制进 jpackage app 镜像)。
# 用法: scripts/make-splash.sh
set -euo pipefail
cd "$(dirname "$0")/.."

java scripts/SplashGen.java
