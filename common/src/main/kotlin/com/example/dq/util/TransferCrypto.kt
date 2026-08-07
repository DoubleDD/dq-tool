package com.example.dq.util

import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * 数据源导出文件专用加解密:算法与 CryptoUtil 相同(AES-GCM,12 字节随机 IV 前置 + Base64),
 * 但密钥固定为 SHA-256(内置默认口令),与实例的 securitySecret 无关,保证导出文件可跨实例导入。
 * 注意:内置口令只是「防随手打开」级别的保护,任何拿到源码的人都能解密,并非强安全设计。
 */
object TransferCrypto {

    /** 内置默认口令(导出文件通用,跨实例一致) */
    private const val DEFAULT_PASSWORD = "dq-tool-datasource-transfer"

    private val key: SecretKeySpec = SecretKeySpec(
        MessageDigest.getInstance("SHA-256").digest(DEFAULT_PASSWORD.toByteArray(StandardCharsets.UTF_8)), "AES")
    private val random = SecureRandom()

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
            throw IllegalArgumentException("密码密文无法解密,文件可能损坏或版本不兼容", e)
        }
    }
}
