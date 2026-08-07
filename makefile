# dq-tool 快捷命令(macOS/Linux;Windows 打包用 scripts\package-win.bat)
# 直接敲 make 查看全部命令

SHELL := /bin/bash
.DEFAULT_GOAL := help

# 项目要求 JDK 25+,而本机默认 java 可能更低:macOS 上自动探测 JDK 25 覆盖 JAVA_HOME
ifeq ($(shell uname -s),Darwin)
JDK25 := $(shell /usr/libexec/java_home -v 25 2>/dev/null)
ifneq ($(JDK25),)
export JAVA_HOME := $(JDK25)
endif
endif

VERSION = $(shell sed -n 's/^version = "\(.*\)"/\1/p' server/build.gradle.kts | head -1)
JAR = server/build/libs/dq-tool-$(VERSION).jar

KEY ?= license-private.key

# 前端源码变动时重新构建 web/dist(后端 run 的静态资源来自 processResources 拷贝的 dist)
WEB_SRC := $(shell find web/src -type f 2>/dev/null) web/index.html web/package.json

web/dist/index.html: $(WEB_SRC)
	@[ -d web/node_modules ] || (cd web && npm install)
	cd web && npm run build

.PHONY: help dev dev-headless dev-web desktop shell tauri build test run run-headless package package-skip package-linux package-tauri package-tauri-skip license-keypair license clean

help: ## 显示全部可用命令
	@grep -E '^[a-zA-Z_-]+:.*?## ' $(MAKEFILE_LIST) | awk 'BEGIN {FS = ":.*?## "}; {printf "  make %-14s %s\n", $$1, $$2}'

dev: web/dist/index.html ## 后端开发模式,带窗口/托盘(显式关闭 headless;前端有改动时先重新构建)
	JAVA_TOOL_OPTIONS="-Djava.awt.headless=false" ./gradlew :server:run

dev-headless: web/dist/index.html ## 后端开发模式,无窗口/托盘(服务器调试;前端有改动时先重新构建)
	./gradlew :server:run

dev-web: ## 前端开发模式(5173,代理 /api 到 10000)
	cd web && npm run dev

desktop: ## 桌面版开发运行(Compose Desktop + Jewel,内嵌 JBR)
	./gradlew :desktop:run

shell: ## JCEF 套壳版开发运行(Web UI + 内嵌 Chromium 窗口)
	./gradlew :shell:run

tauri: build ## Tauri 2 套壳版开发运行(系统 WebView + Rust 侧车拉起 java 子进程;tauri 非 Gradle 模块,需先构建 fat jar)
	@[ -d tauri/node_modules ] || (cd tauri && npm install)
	cd tauri && npm run dev

build: ## 构建前端 + 后端 fat jar(跳过测试)
	cd web && npm run build
	./gradlew :server:shadowJar

test: ## 全部测试(含 Testcontainers,需要 Docker)
	./gradlew :common:test :server:test

run: build ## 构建并运行 fat jar,带窗口/托盘
	java -XX:+UseZGC -Djava.awt.headless=false -jar $(JAR)

run-headless: build ## 构建并运行 fat jar,无窗口/托盘(服务器方式)
	java -XX:+UseZGC -jar $(JAR)

package: ## macOS:构建并打 dmg 安装包
	scripts/package-mac.sh

package-skip: ## macOS:跳过构建,用现有 jar 重打 dmg
	scripts/package-mac.sh --skip-build

package-linux: ## Linux:构建并打 deb 安装包
	scripts/package-linux.sh

package-tauri: ## macOS:构建并打 Tauri 2 套壳版 dmg(内嵌 fat jar + jlink JRE)
	scripts/package-tauri-mac.sh

package-tauri-skip: ## macOS:跳过构建,用现有 jar 重打 Tauri 2 套壳版 dmg
	scripts/package-tauri-mac.sh --skip-build

license-keypair: ## 生成授权密钥对(只需一次,公钥填入 application.yml 的 dq.license.public-key)
	java scripts/LicenseKeygen.java --gen-keypair

license: ## 签发授权码(交互式;默认客户=内部测试、有效期=30 天后;也可 customer=... expires=... 直接传参)[KEY=私钥文件]
	@bash -c 'c="$(customer)"; e="$(expires)"; \
		[ -n "$$c" ] || read -r -p "客户名称(回车=内部测试): " c; \
		c=$${c:-内部测试}; \
		[ -n "$$e" ] || { d=$$(date -v+30d +%F 2>/dev/null || date -d "+30 days" +%F); \
			read -r -p "有效期(yyyy-MM-dd 或 permanent,回车=$$d 即 30 天后): " e; e=$${e:-$$d}; }; \
		java scripts/LicenseKeygen.java --key $(KEY) --customer "$$c" --expires "$$e"'

clean: ## 清理构建产物(server/build/ 与 web/dist/)
	./gradlew :server:clean
	rm -rf web/dist
