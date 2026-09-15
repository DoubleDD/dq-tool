package com.example.dq.model

import jakarta.validation.constraints.NotBlank
import java.time.LocalDate
import java.time.LocalDateTime

/** 授权码签发留档(license_record 表);codeEnc 为 AES-GCM 加密后的完整授权码,读取后由 service 解密 */
data class LicenseRecord(
    val id: Long,
    val appVersion: String,
    val customer: String,
    /** null 表示永久授权 */
    val expiresAt: LocalDate?,
    val serverUrl: String?,
    val username: String?,
    val sid: String?,
    /** 签发时间戳(epoch 毫秒,与授权码 payload 内一致) */
    val issuedAt: Long,
    /** 授权码显式包含的旧功能 key(逗号分隔,可空;仅旧格式留档有值,展示时按它推导菜单) */
    val features: String?,
    /** 授权码开放菜单 key(逗号分隔,可空;旧记录 NULL 时留档视图按 features 推导展示) */
    val menus: String? = null,
    /** 免接口鉴权标记(演示用;NULL=false) */
    val bypassAuth: Boolean? = null,
    /** 签发方备注(仅管理端留档展示,不写入授权码) */
    val remark: String? = null,
    val codeEnc: String,
    val createdAt: LocalDateTime?,
)

/** 授权码管理列表回传视图(仅管理员实例);code 为解密后的完整授权码,serverUrl 在此可见 */
data class LicenseRecordView(
    val id: Long,
    val appVersion: String,
    val customer: String,
    val expiresAt: LocalDate?,
    val serverUrl: String?,
    val username: String?,
    val sid: String?,
    val issuedAt: Long,
    /** 留档视图统一给出开放菜单 key(逗号分隔):新记录取授权码菜单段,旧记录(NULL)按旧功能段推导 */
    val menus: String?,
    /** 免接口鉴权标记(演示用) */
    val bypassAuth: Boolean = false,
    /** 签发方备注(仅管理端留档展示,不写入授权码) */
    val remark: String? = null,
    val code: String,
    val createdAt: LocalDateTime?,
)

/** 生成授权码请求;expires 为 "yyyy-MM-dd" 或 "permanent"(永久) */
data class LicenseGenerateRequest(
    @field:NotBlank
    val customer: String?,
    @field:NotBlank
    val expires: String?,
    val serverUrl: String? = null,
    val username: String? = null,
    val sid: String? = null,
    /** 授权码开放菜单 key 列表(勾选即客户实例侧边栏可见);为空/未传时按 features 推导,两者皆无则默认开放除 数据比对/授权管理 外的全部菜单 */
    val menus: List<String>? = null,
    /** 旧版签发端兼容:未传 menus 时按旧功能 key 列表(compare/license_admin 等)推导菜单 */
    val features: List<String>? = null,
    /** 免接口鉴权标记(演示用):true 时客户实例跳过 dq.access-token 校验,默认 false */
    val bypassAuth: Boolean? = null,
    /** 签发方备注(仅管理端留档展示,不写入授权码 payload) */
    val remark: String? = null,
)
