# dq-tool 常用快捷命令

KEY ?= license-private.key

.DEFAULT_GOAL := help

.PHONY: help
help: ## 列出可用命令
	@grep -E '^[a-zA-Z_-]+:.*?## ' $(MAKEFILE_LIST) | awk 'BEGIN {FS = ":.*?## "}; {printf "  \033[36m%-18s\033[0m %s\n", $$1, $$2}'

.PHONY: license-keypair
license-keypair: ## 生成授权密钥对(只需一次,公钥填入 application.yml 的 dq.license.public-key)
	java scripts/LicenseKeygen.java --gen-keypair

.PHONY: license
license: ## 签发授权码:make license customer="某某公司" expires=2026-12-31(或 expires=permanent 永久授权)[KEY=私钥文件]
	@test -n "$(customer)" || { echo "缺少参数:customer=\"某某公司\""; exit 1; }
	@test -n "$(expires)" || { echo "缺少参数:expires=yyyy-MM-dd"; exit 1; }
	java scripts/LicenseKeygen.java --key $(KEY) --customer "$(customer)" --expires $(expires)
