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

VERSION = $(shell mvn -q help:evaluate -Dexpression=project.version -DforceStdout 2>/dev/null | tail -1)
JAR = target/dq-tool-$(VERSION).jar

KEY ?= license-private.key

.PHONY: help dev dev-headless dev-web build test run run-headless package package-skip package-linux license-keypair license clean

help: ## 显示全部可用命令
	@grep -E '^[a-zA-Z_-]+:.*?## ' $(MAKEFILE_LIST) | awk 'BEGIN {FS = ":.*?## "}; {printf "  make %-14s %s\n", $$1, $$2}'

dev: ## 后端开发模式,带窗口/托盘(显式关闭 headless)
	mvn spring-boot:run -Dspring-boot.run.jvmArguments="-Djava.awt.headless=false"

dev-headless: ## 后端开发模式,无窗口/托盘(服务器调试)
	mvn spring-boot:run

dev-web: ## 前端开发模式(5173,代理 /api 到 10000)
	cd web && npm run dev

build: ## 构建前端 + 后端 fat jar(跳过测试)
	cd web && npm run build
	mvn -q -DskipTests package

test: ## 全部测试(含 Testcontainers,需要 Docker)
	mvn test

run: build ## 构建并运行 fat jar,带窗口/托盘
	java -Djava.awt.headless=false -jar $(JAR)

run-headless: build ## 构建并运行 fat jar,无窗口/托盘(服务器方式)
	java -jar $(JAR)

package: ## macOS:构建并打 dmg 安装包
	scripts/package-mac.sh

package-skip: ## macOS:跳过构建,用现有 jar 重打 dmg
	scripts/package-mac.sh --skip-build

package-linux: ## Linux:构建并打 deb 安装包
	scripts/package-linux.sh

license-keypair: ## 生成授权密钥对(只需一次,公钥填入 application.yml 的 dq.license.public-key)
	java scripts/LicenseKeygen.java --gen-keypair

license: ## 签发授权码(交互式;默认客户=内部测试、有效期=30 天后;也可 customer=... expires=... 直接传参)[KEY=私钥文件]
	@bash -c 'c="$(customer)"; e="$(expires)"; \
		[ -n "$$c" ] || read -r -p "客户名称(回车=内部测试): " c; \
		c=$${c:-内部测试}; \
		[ -n "$$e" ] || { d=$$(date -v+30d +%F 2>/dev/null || date -d "+30 days" +%F); \
			read -r -p "有效期(yyyy-MM-dd 或 permanent,回车=$$d 即 30 天后): " e; e=$${e:-$$d}; }; \
		java scripts/LicenseKeygen.java --key $(KEY) --customer "$$c" --expires "$$e"'

clean: ## 清理构建产物(target/ 与 web/dist/)
	mvn -q clean
	rm -rf web/dist
