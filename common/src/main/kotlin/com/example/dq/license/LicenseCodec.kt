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
 *  - payload   = "客户名|yyyy-MM-dd"(永久授权为 "客户名|PERMANENT")的 UTF-8 字节
 *  - signature = Ed25519(payload),由签发方私钥生成,程序内嵌公钥离线验证
 * DQ1 为版本前缀,便于将来格式演进。
 */
object LicenseCodec {

    const val VERSION = "DQ1"

    /** payload 中有效期的永久标记:客户名|PERMANENT */
    const val PERMANENT = "PERMANENT"

    private val B64: Base64.Encoder = Base64.getUrlEncoder().withoutPadding()
    private val B64_DEC: Base64.Decoder = Base64.getUrlDecoder()

    /** 解析出的授权内容;expiresAt 为 null 表示永久有效 */
    data class LicensePayload(val customer: String, val expiresAt: LocalDate?)

    /** 签发授权码(签发工具与测试使用);expiresAt 传 null 表示永久有效 */
    @JvmStatic
    fun encode(customer: String, expiresAt: LocalDate?, privateKey: PrivateKey): String {
        require(customer.isNotBlank()) { "客户标识不能为空" }
        require(!customer.contains("|")) { "客户标识不能包含 | 字符" }
        val expiry = expiresAt?.toString() ?: PERMANENT
        val payload = "$customer|$expiry".toByteArray(StandardCharsets.UTF_8)
        val signature = sign(payload, privateKey)
        return "$VERSION.${B64.encodeToString(payload)}.${B64.encodeToString(signature)}"
    }

    /**
     * 解析并验签授权码,失败一律抛 IllegalArgumentException("授权码无效")。
     * 容忍粘贴时带入的空白与换行。
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
        val text = String(payload, StandardCharsets.UTF_8)
        val sep = text.lastIndexOf('|')
        if (sep <= 0 || sep == text.length - 1) {
            throw invalid()
        }
        val customer = text.substring(0, sep)
        val expiry = text.substring(sep + 1)
        val expiresAt: LocalDate? = if (expiry == PERMANENT) {
            null
        } else {
            try {
                LocalDate.parse(expiry)
            } catch (e: DateTimeParseException) {
                throw invalid()
            }
        }
        return LicensePayload(customer, expiresAt)
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
