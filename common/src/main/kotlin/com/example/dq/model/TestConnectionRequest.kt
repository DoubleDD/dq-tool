package com.example.dq.model

import jakarta.validation.constraints.NotBlank

/** 测试连接请求:不需要名称,只要有连接参数;SSH 隧道字段可空,sshEnabled=true 时经一次性隧道连接 */
data class TestConnectionRequest(
    @field:NotBlank val jdbcUrl: String?,
    val username: String?,
    val password: String?,
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
    /** 已存数据源 id:非空时留空字段(密码/SSH 秘密)由服务端回落到已存配置,供编辑态改了连接信息但密码留空时拉库列表 */
    val id: Long? = null,
)
