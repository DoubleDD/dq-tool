package com.example.dq.util

import java.nio.charset.StandardCharsets
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Navicat 11+(.ncx 导出文件 Ver 1.x)密码解密,公开算法
 * (参考 DoubleLabyrinth/HyperSine 的 how-does-navicat-encrypt-password):
 * AES-128-CBC + PKCS7 填充(JDK 的 PKCS5Padding 等价),key/IV 为固定 ASCII 串,密文为大写十六进制。
 */
object NavicatCrypto {

    private val key = SecretKeySpec("libcckeylibcckey".toByteArray(StandardCharsets.US_ASCII), "AES")

    /** IV 为 "libcciv " 重复两遍(中间与末尾都是空格) */
    private val iv = IvParameterSpec("libcciv libcciv ".toByteArray(StandardCharsets.US_ASCII))

    /** 解密大写十六进制密文为明文密码;失败抛 IllegalArgumentException */
    fun decrypt(hex: String): String {
        try {
            val bytes = hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
            val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
            cipher.init(Cipher.DECRYPT_MODE, key, iv)
            return String(cipher.doFinal(bytes), StandardCharsets.UTF_8)
        } catch (e: Exception) {
            throw IllegalArgumentException("Navicat 密码密文无法解密,文件可能损坏或版本不兼容", e)
        }
    }

    /** 加密为大写十六进制密文(供单测 roundtrip 使用) */
    fun encrypt(plain: String): String {
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(Cipher.ENCRYPT_MODE, key, iv)
        return cipher.doFinal(plain.toByteArray(StandardCharsets.UTF_8))
            .joinToString("") { "%02X".format(it) }
    }
}
