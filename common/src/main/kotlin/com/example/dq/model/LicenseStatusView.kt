package com.example.dq.model

import java.time.LocalDate

/**
 * 授权状态视图(只回传展示字段,不回传授权码本身)。
 * 授权码扩展字段中的 serverUrl 属敏感信息,禁止出现在本视图;前端只展示 sid/username/timestamp。
 */
data class LicenseStatusView(
    val activated: Boolean,
    val expired: Boolean,
    val customer: String?,
    val expiresAt: LocalDate?,
    val daysLeft: Long?,
    /** 授权码中的用户名(旧格式授权码为 null) */
    val username: String? = null,
    /** 授权码中的 SID(旧格式授权码为 null) */
    val sid: String? = null,
    /** 签发时间(epoch 毫秒,旧格式授权码为 null) */
    val timestamp: Long? = null,
    /** 当前实例是否管理员(配置了签发私钥),管理员可见授权码管理入口 */
    val admin: Boolean = false,
    /** 软件版本号(构建期注入;页脚展示) */
    val appVersion: String? = null,
    /** 已授权功能 key 列表(基础功能恒有;受控功能 logs/license_admin 需授权码显式包含);未激活为 null */
    val features: List<String>? = null,
) {
    companion object {
        @JvmStatic
        fun notActivated() = LicenseStatusView(false, false, null, null, null)
    }
}
