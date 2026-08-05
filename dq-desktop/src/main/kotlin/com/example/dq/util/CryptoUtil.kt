package com.example.dq.util

import com.example.dq.config.AppConfig
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** 数据源密码 AES-GCM 对称加密 */
class CryptoUtil(config: AppConfig) {

    private val key: SecretKeySpec
    private val random = SecureRandom()

    init {
        val raw = MessageDigest.getInstance("SHA-256")
            .digest(config.securitySecret.toByteArray(StandardCharsets.UTF_8))
        key = SecretKeySpec(raw, "AES")
    }

    fun encrypt(plain: String?): String? {
        if (plain.isNullOrEmpty()) {
            return plain
        }
        try {
            val iv = ByteArray(12)
            random.nextBytes(iv)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(128, iv))
            val ct = cipher.doFinal(plain.toByteArray(StandardCharsets.UTF_8))
            val out = ByteArray(iv.size + ct.size)
            System.arraycopy(iv, 0, out, 0, iv.size)
            System.arraycopy(ct, 0, out, iv.size, ct.size)
            return Base64.getEncoder().encodeToString(out)
        } catch (e: Exception) {
            throw IllegalStateException("加密失败", e)
        }
    }

    fun decrypt(enc: String?): String? {
        if (enc.isNullOrEmpty()) {
            return enc
        }
        try {
            val all = Base64.getDecoder().decode(enc)
            val iv = ByteArray(12)
            System.arraycopy(all, 0, iv, 0, 12)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, iv))
            return String(cipher.doFinal(all, 12, all.size - 12), StandardCharsets.UTF_8)
        } catch (e: Exception) {
            throw IllegalStateException("解密失败", e)
        }
    }
}
