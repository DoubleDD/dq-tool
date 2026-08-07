package com.example.dq.model

import jakarta.validation.constraints.NotBlank

/**
 * 数据源新增/编辑请求。password 为空表示不修改(编辑时)。
 * SSH 隧道字段全部可空带默认值:sshEnabled=true 时生效,三个秘密字段(sshPassword/sshPrivateKey/sshPassphrase)
 * 同样「留空表示不修改」。
 */
data class DataSourceRequest(
    @field:NotBlank val name: String?,
    @field:NotBlank val jdbcUrl: String?,
    val username: String?,
    val password: String?,
    val rowThreshold: Long?,
    val sizeThresholdBytes: Long?,
    /** 库过滤白名单:非空时库列表只显示这些库;空/NULL 表示不过滤 */
    val schemaFilter: List<String>? = null,
    val sshEnabled: Boolean? = null,
    val sshHost: String? = null,
    val sshPort: Int? = null,
    val sshUsername: String? = null,
    /** 认证方式:password / publickey */
    val sshAuthMethod: String? = null,
    val sshPassword: String? = null,
    /** SSH 私钥内容(非文件路径) */
    val sshPrivateKey: String? = null,
    val sshPassphrase: String? = null,
)

/** 库过滤白名单单独更新(库列表页「库过滤」弹窗);schemas 为 null/空表示不过滤 */
data class SchemaFilterRequest(
    val schemas: List<String>? = null,
)
