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
)
