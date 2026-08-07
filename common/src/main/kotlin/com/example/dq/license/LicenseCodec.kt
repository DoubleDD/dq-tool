package com.example.dq.license

import java.nio.charset.StandardCharsets
import java.security.PrivateKey
import java.security.PublicKey
import java.security.Signature
import java.time.LocalDate
import java.time.format.DateTimeParseException
import java.util.Base64

/**
 * 授权码编解码与离线验签(纯函数,不依赖任何框架)。
 *
 * 授权码格式:DQ1.<base64url(payload)>.<base64url(signature)>
 *  - payload   = "客户名|yyyy-MM-dd|软件版本|server_url|username|sid|timestamp"(永久授权有效期段为 PERMANENT)的 UTF-8 字节;
 *                timestamp 为签发时间(epoch 毫秒);server_url 属敏感信息,仅存于授权码,禁止回传前端状态接口
 *  - 兼容旧格式:2 段 "客户名|yyyy-MM-dd" 与 6 段(无软件版本段),解码后缺失的扩展字段为 null
 *  - signature = Ed25519(payload),由签发方私钥生成,程序内嵌公钥离线验证
 * DQ1 为版本前缀,便于将来格式演进。
 */
object LicenseCodec {

    const val VERSION = "DQ1"

    /** payload 中有效期的永久标记:客户名|PERMANENT */
    const val PERMANENT = "PERMANENT"

    private val B64: Base64.Encoder = Base64.getUrlEncoder().withoutPadding()
    private val B64_DEC: Base64.Decoder = Base64.getUrlDecoder()

    /**
     * 解析出的授权内容;expiresAt 为 null 表示永久有效。
     * appVersion/serverUrl/username/sid/timestamp 为扩展字段,旧格式授权码解码后为 null;
     * serverUrl 禁止回传前端状态接口(仅管理员实例的授权码管理可见)。
     */
    data class LicensePayload(
        val customer: String,
        val expiresAt: LocalDate?,
        val appVersion: String? = null,
        val serverUrl: String? = null,
        val username: String? = null,
        val sid: String? = null,
        val timestamp: Long? = null,
    )

    /**
     * 签发授权码(签发工具与测试使用);expiresAt 传 null 表示永久有效;
     * appVersion 为绑定的软件版本号(可空);timestamp 为签发时间(epoch 毫秒),默认取当前时间;
     * 扩展字段不允许含 | 字符
     */
    @JvmOverloads
    @JvmStatic
    fun encode(
        customer: String,
        expiresAt: LocalDate?,
        privateKey: PrivateKey,
        appVersion: String = "",
        serverUrl: String = "",
        username: String = "",
        sid: String = "",
        timestamp: Long = System.currentTimeMillis(),
    ): String {
        require(customer.isNotBlank()) { "客户标识不能为空" }
        require(!customer.contains("|")) { "客户标识不能包含 | 字符" }
        require(!appVersion.contains("|")) { "软件版本不能包含 | 字符" }
        require(!serverUrl.contains("|")) { "server_url 不能包含 | 字符" }
        require(!username.contains("|")) { "username 不能包含 | 字符" }
        require(!sid.contains("|")) { "sid 不能包含 | 字符" }
        val expiry = expiresAt?.toString() ?: PERMANENT
        val payload = "$customer|$expiry|$appVersion|$serverUrl|$username|$sid|$timestamp"
            .toByteArray(StandardCharsets.UTF_8)
        val signature = sign(payload, privateKey)
        return "$VERSION.${B64.encodeToString(payload)}.${B64.encodeToString(signature)}"
    }

    /**
     * 解析并验签授权码,失败一律抛 IllegalArgumentException("授权码无效")。
     * 容忍粘贴时带入的空白与换行;兼容 2 段(最初旧格式)与 6 段(无软件版本段)旧格式。
     */
    @JvmStatic
    fun decodeAndVerify(code: String?, publicKey: PublicKey): LicensePayload {
        if (code == null) {
            throw invalid()
        }
        val compact = code.replace("\\s+".toRegex(), "")
        // Kotlin split 默认保留尾部空串,与 Java split(regex, -1) 等价
        val parts = compact.split(".")
        if (parts.size != 3 || parts[0] != VERSION) {
            throw invalid()
        }
        val payload: ByteArray
        val signature: ByteArray
        try {
            payload = B64_DEC.decode(parts[1])
            signature = B64_DEC.decode(parts[2])
        } catch (e: IllegalArgumentException) {
            throw invalid()
        }
        if (!verify(payload, signature, publicKey)) {
            throw invalid()
        }
        // 签发侧已拒绝字段含 |,这里可直接按段拆分;2/6/7 段为合法历史与当前格式,其余一律无效
        val fields = String(payload, StandardCharsets.UTF_8).split("|")
        if (fields.size != 2 && fields.size != 6 && fields.size != 7) {
            throw invalid()
        }
        val customer = fields[0]
        if (customer.isBlank()) {
            throw invalid()
        }
        val expiresAt: LocalDate? = if (fields[1] == PERMANENT) {
            null
        } else {
            try {
                LocalDate.parse(fields[1])
            } catch (e: DateTimeParseException) {
                throw invalid()
            }
        }
        if (fields.size == 2) {
            return LicensePayload(customer, expiresAt)
        }
        // 6 段旧格式无软件版本段,扩展字段整体后移一位
        val offset = if (fields.size == 6) -1 else 0
        val timestamp = try {
            fields[6 + offset].toLong()
        } catch (e: NumberFormatException) {
            throw invalid()
        }
        return LicensePayload(
            customer, expiresAt,
            appVersion = if (fields.size == 7) fields[2].ifBlank { null } else null,
            serverUrl = fields[3 + offset].ifBlank { null },
            username = fields[4 + offset].ifBlank { null },
            sid = fields[5 + offset].ifBlank { null },
            timestamp = timestamp,
        )
    }

    /** 到期日当天仍有效,today 晚于 expiresAt 才算过期;expiresAt 为 null(永久)时永不过期 */
    @JvmStatic
    fun isExpired(expiresAt: LocalDate?, today: LocalDate): Boolean {
        return expiresAt != null && today.isAfter(expiresAt)
    }

    private fun invalid(): IllegalArgumentException {
        return IllegalArgumentException("授权码无效,请核对后重新输入")
    }

    private fun sign(payload: ByteArray, privateKey: PrivateKey): ByteArray {
        try {
            val sig = Signature.getInstance("Ed25519")
            sig.initSign(privateKey)
            sig.update(payload)
            return sig.sign()
        } catch (e: Exception) {
            throw IllegalStateException("授权码签名失败: ${e.message}", e)
        }
    }

    private fun verify(payload: ByteArray, signature: ByteArray, publicKey: PublicKey): Boolean {
        return try {
            val sig = Signature.getInstance("Ed25519")
            sig.initVerify(publicKey)
            sig.update(payload)
            sig.verify(signature)
        } catch (e: Exception) {
            false
        }
    }
}
