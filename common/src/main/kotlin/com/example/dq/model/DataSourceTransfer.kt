package com.example.dq.model

/** 数据源导出文件格式(Jackson 2 序列化;passwordEnc 用 TransferCrypto 固定密钥加密,跨实例可导入) */
data class DataSourceExportFile(
    val app: String,
    val version: Int,
    val exportedAt: String,
    val items: List<DataSourceExportItem>,
)

data class DataSourceExportItem(
    val name: String,
    val jdbcUrl: String,
    val username: String?,
    val passwordEnc: String?,
    val rowThreshold: Long?,
    val sizeThresholdBytes: Long?,
    /** 库过滤白名单,空/NULL 表示不过滤 */
    val schemaFilter: List<String>? = null,
    // SSH 隧道配置;三个秘密字段与 passwordEnc 一样走 TransferCrypto 固定密钥
    val sshEnabled: Boolean? = null,
    val sshHost: String? = null,
    val sshPort: Int? = null,
    val sshUsername: String? = null,
    val sshAuthMethod: String? = null,
    val sshPasswordEnc: String? = null,
    val sshPrivateKeyEnc: String? = null,
    val sshPassphraseEnc: String? = null,
)

/** 导入结果:total 为条目总数,renamed 记录重名改名(原名 → 新名),failed 不中断整批 */
data class ImportResult(
    var total: Int = 0,
    val imported: MutableList<String> = mutableListOf(),
    val renamed: MutableMap<String, String> = mutableMapOf(),
    val failed: MutableList<ImportFailure> = mutableListOf(),
    val warnings: MutableList<String> = mutableListOf(),
)

data class ImportFailure(
    val name: String,
    val reason: String,
)
