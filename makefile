# dq-tool 快捷命令(macOS/Linux 为主;package-*-win 目标仅在 Windows 上可用,调 scripts\package-*-win.bat)
# 直接敲 make 查看全部命令(按用途分组)

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

# 授权码绑定的软件版本:与安装包版本口径一致(去 0. 前缀,0.1.6 -> 1.6)
LICENSE_VERSION ?= $(VERSION:0.%=%)

# 前端源码变动时重新构建 web/dist(后端 run 的静态资源来自 processResources 拷贝的 dist)
WEB_SRC := $(shell find web/src -type f 2>/dev/null) web/index.html web/package.json

web/dist/index.html: $(WEB_SRC)
	@[ -d web/node_modules ] || (cd web && npm install)
	cd web && npm run build

.PHONY: help \
	dev dev-headless dev-web desktop shell tauri \
	build test run run-headless \
	package package-skip package-linux \
	package-shell package-shell-skip \
	package-tauri package-tauri-skip \
	package-win package-win-skip \
	package-shell-win package-shell-win-skip \
	package-tauri-win package-tauri-win-skip \
	license-keypair license clean

help: ## 显示全部可用命令(按用途分组)
	@printf '\n\033[1m本地运行调试\033[0m\n'
	@printf '  make %-18s %s\n' dev            '后端 + 浏览器 app 窗口/托盘(前端有改动时先重建 web/dist)'
	@printf '  make %-18s %s\n' dev-headless   '后端,无窗口/托盘(服务器方式调试)'
	@printf '  make %-18s %s\n' dev-web        '前端 5173 热更新(代理 /api 到 10000)'
	@printf '  make %-18s %s\n' desktop        'Compose Desktop 原生桌面版(已放弃不再维护,仅留档)'
	@printf '  make %-18s %s\n' shell          'JCEF 套壳版(Web UI + 内嵌 Chromium 窗口)'
	@printf '  make %-18s %s\n' tauri          'Tauri 2 套壳版(系统 WebView + Rust 侧车拉起 java 子进程)'
	@printf '\n\033[1m构建 / 测试 / 直接跑 jar\033[0m\n'
	@printf '  make %-18s %s\n' build          '构建前端 + 后端 fat jar(跳过测试)'
	@printf '  make %-18s %s\n' test           '全部测试(含 Testcontainers,需要 Docker)'
	@printf '  make %-18s %s\n' run            '构建并运行 fat jar,带窗口/托盘'
	@printf '  make %-18s %s\n' run-headless   '构建并运行 fat jar,无窗口/托盘(服务器方式)'
	@printf '\n\033[1m打包:浏览器 app 模式(server 安装版)\033[0m\n'
	@printf '  make %-18s %s\n' package        'macOS dmg(构建 + 打包)'
	@printf '  make %-18s %s\n' package-skip   'macOS dmg(跳过构建,用现有 jar 重打)'
	@printf '  make %-18s %s\n' package-linux  'Linux deb(--type rpm 打 rpm 需直接调 scripts/package-linux.sh)'
	@printf '  make %-18s %s\n' package-win      'Windows 免安装 zip(仅 Windows 可用)'
	@printf '  make %-18s %s\n' package-win-skip 'Windows zip(跳过构建,用现有 jar 重打)'
	@printf '\n\033[1m打包:JCEF 套壳\033[0m\n'
	@printf '  make %-18s %s\n' package-shell      'macOS dmg(内嵌 Chromium natives)'
	@printf '  make %-18s %s\n' package-shell-skip 'macOS dmg(跳过构建)'
	@printf '  make %-22s %s\n' package-shell-win      'Windows 版(仅 Windows 可用)'
	@printf '  make %-22s %s\n' package-shell-win-skip 'Windows 版(跳过构建)'
	@printf '\n\033[1m打包:Tauri 2 套壳\033[0m\n'
	@printf '  make %-18s %s\n' package-tauri      'macOS dmg(内嵌 fat jar + jlink JRE)'
	@printf '  make %-18s %s\n' package-tauri-skip 'macOS dmg(跳过构建)'
	@printf '  make %-22s %s\n' package-tauri-win      'Windows 版(仅 Windows 可用)'
	@printf '  make %-22s %s\n' package-tauri-win-skip 'Windows 版(跳过构建)'
	@printf '\n\033[1m授权码 / 清理\033[0m\n'
	@printf '  make %-18s %s\n' license-keypair '生成授权密钥对(只需一次,公钥写入 license-public.key 并拷入 server/src/main/resources/)'
	@printf '  make %-18s %s\n' license         '签发授权码(交互式;也可 customer=... expires=... 传参)[KEY=私钥文件]'
	@printf '  make %-18s %s\n' clean           '清理构建产物(server/build/ 与 web/dist/)'
	@printf '\n跨平台产物无法在本机构建(不支持交叉编译):macOS/Linux 包在对应系统上打,或推 v* tag 走 CI\n\n'

# ── 本地运行调试 ─────────────────────────────────────────────────────────────

dev: web/dist/index.html ## 后端开发模式,带窗口/托盘(显式关闭 headless;前端有改动时先重新构建)
	JAVA_TOOL_OPTIONS="-Djava.awt.headless=false" ./gradlew :server:run

dev-headless: web/dist/index.html ## 后端开发模式,无窗口/托盘(服务器调试;前端有改动时先重新构建)
	./gradlew :server:run

dev-web: ## 前端开发模式(5173,代理 /api 到 10000)
	cd web && npm run dev

desktop: ## 桌面版开发运行(Compose Desktop + Jewel,内嵌 JBR;已放弃不再维护,仅留档)
	./gradlew :desktop:run

shell: ## JCEF 套壳版开发运行(Web UI + 内嵌 Chromium 窗口)
	./gradlew :shell:run

tauri: build ## Tauri 2 套壳版开发运行(系统 WebView + Rust 侧车拉起 java 子进程;tauri 非 Gradle 模块,需先构建 fat jar)
	@[ -d tauri/node_modules ] || (cd tauri && npm install)
	cd tauri && npm run dev

# ── 构建 / 测试 / 直接跑 jar ─────────────────────────────────────────────────

build: ## 构建前端 + 后端 fat jar(跳过测试)
	cd web && npm run build
	./gradlew :server:shadowJar

test: ## 全部测试(含 Testcontainers,需要 Docker)
	./gradlew :common:test :server:test

run: build ## 构建并运行 fat jar,带窗口/托盘
	java -XX:+UseZGC -Djava.awt.headless=false -jar $(JAR)

run-headless: build ## 构建并运行 fat jar,无窗口/托盘(服务器方式)
	java -XX:+UseZGC -jar $(JAR)

# ── 打包:浏览器 app 模式(server 安装版,jpackage 内嵌 JRE)─────────────────────

package: ## macOS:构建并打 dmg 安装包
	scripts/package-mac.sh

package-skip: ## macOS:跳过构建,用现有 jar 重打 dmg
	scripts/package-mac.sh --skip-build

package-linux: ## Linux:构建并打 deb 安装包
	scripts/package-linux.sh

# Windows 目标仅在本机为 Windows 时可用:经 cmd 调对应 .bat 脚本(产物无法跨平台构建)

package-win: ## Windows:构建并打免安装 zip(仅 Windows 可用)
	cmd //c scripts\\package-win.bat

package-win-skip: ## Windows:跳过构建,用现有 jar 重打 zip(仅 Windows 可用)
	cmd //c "scripts\\package-win.bat --skip-build"

# ── 打包:JCEF 套壳(shell 模块,内嵌 Chromium)─────────────────────────────────

package-shell: ## macOS:构建并打 JCEF 套壳版 dmg
	scripts/package-shell-mac.sh

package-shell-skip: ## macOS:跳过构建,用现有 jar 重打 JCEF 套壳版 dmg
	scripts/package-shell-mac.sh --skip-build

package-shell-win: ## Windows:构建并打 JCEF 套壳版(仅 Windows 可用)
	cmd //c scripts\\package-shell-win.bat

package-shell-win-skip: ## Windows:跳过构建,重打 JCEF 套壳版(仅 Windows 可用)
	cmd //c "scripts\\package-shell-win.bat --skip-build"

# ── 打包:Tauri 2 套壳(tauri 模块,系统 WebView + java 侧车子进程)──────────────

package-tauri: ## macOS:构建并打 Tauri 2 套壳版 dmg(内嵌 fat jar + jlink JRE)
	scripts/package-tauri-mac.sh

package-tauri-skip: ## macOS:跳过构建,用现有 jar 重打 Tauri 2 套壳版 dmg
	scripts/package-tauri-mac.sh --skip-build

package-tauri-win: ## Windows:构建并打 Tauri 2 套壳版(仅 Windows 可用)
	cmd //c scripts\\package-tauri-win.bat

package-tauri-win-skip: ## Windows:跳过构建,重打 Tauri 2 套壳版(仅 Windows 可用)
	cmd //c "scripts\\package-tauri-win.bat --skip-build"

# ── 授权码 / 清理 ────────────────────────────────────────────────────────────

license-keypair: ## 生成授权密钥对(只需一次,公钥写入 license-public.key 并拷入 server/src/main/resources/)
	java scripts/LicenseKeygen.java --gen-keypair

license: ## 签发授权码(交互式;默认客户=内部测试、有效期=30 天后;也可 customer=... expires=... 直接传参)[KEY=私钥文件 SERVER_URL/USERNAME/SID=扩展字段]
	@bash -c 'c="$(customer)"; e="$(expires)"; \
		[ -n "$$c" ] || read -r -p "客户名称(回车=内部测试): " c; \
		c=$${c:-内部测试}; \
		[ -n "$$e" ] || { d=$$(date -v+30d +%F 2>/dev/null || date -d "+30 days" +%F); \
			read -r -p "有效期(yyyy-MM-dd 或 permanent,回车=$$d 即 30 天后): " e; e=$${e:-$$d}; }; \
		extra=""; \
		[ -z "$(LICENSE_VERSION)" ] || extra="$$extra --version $(LICENSE_VERSION)"; \
		[ -z "$(SERVER_URL)" ] || extra="$$extra --server-url $(SERVER_URL)"; \
		[ -z "$(USERNAME)" ] || extra="$$extra --username $(USERNAME)"; \
		[ -z "$(SID)" ] || extra="$$extra --sid $(SID)"; \
		java scripts/LicenseKeygen.java --key $(KEY) --customer "$$c" --expires "$$e" $$extra'

clean: ## 清理构建产物(server/build/ 与 web/dist/)
	./gradlew :server:clean
	rm -rf web/dist
