package com.example.dq.service

import com.example.dq.license.LicenseCodec
import com.example.dq.model.LicenseRequiredException
import com.example.dq.model.LicenseStatusView
import com.example.dq.repository.LicenseRepository
import com.example.dq.util.CryptoUtil
import org.slf4j.LoggerFactory
import java.security.KeyFactory
import java.security.PublicKey
import java.security.spec.X509EncodedKeySpec
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import java.util.Base64

/**
 * 授权码:离线 Ed25519 验签,公钥来自配置(AppConfig.licensePublicKey,即原 dq.license.public-key);
 * 激活后的授权码加密存本地 H2(license_info 单行)。
 */
class LicenseService(
    private val repository: LicenseRepository,
    private val crypto: CryptoUtil,
    publicKeyBase64: String,
) {

    private val log = LoggerFactory.getLogger(LicenseService::class.java)

    private val publicKey: PublicKey? = parsePublicKey(publicKeyBase64)

    /** 状态缓存,activate 后刷新,避免每个请求查库验签 */
    @Volatile
    private var cached: LicenseStatusView? = null

    /** 当前授权状态(展示用,不回传授权码本身) */
    fun status(): LicenseStatusView {
        return cached ?: loadStatus().also { cached = it }
    }

    /** 提交授权码激活,成功后返回最新状态;code 为 null/无效时抛 IllegalArgumentException(web 层映射 400) */
    @Synchronized
    fun activate(code: String?): LicenseStatusView {
        checkNotNull(publicKey) { "程序未配置授权公钥,请联系分发方" }
        val payload = LicenseCodec.decodeAndVerify(code, publicKey)
        require(!LicenseCodec.isExpired(payload.expiresAt, LocalDate.now())) {
            "授权码已于 ${payload.expiresAt} 到期,请向分发方索取新授权码"
        }
        // code 为 null 时上一行 decodeAndVerify 已抛"授权码无效"
        val compact = code!!.replace("\\s+".toRegex(), "")
        repository.upsert(crypto.encrypt(compact)!!, payload.customer, payload.expiresAt)
        return loadStatus().also { cached = it }
    }

    /** server web 层授权前置校验调用:未激活/已过期抛 LicenseRequiredException */
    fun checkActive() {
        val s = status()
        if (!s.activated) {
            throw LicenseRequiredException("程序未激活,请先输入授权码")
        }
        if (s.expired) {
            throw LicenseRequiredException("授权已于 ${s.expiresAt} 到期,请更新授权码")
        }
    }

    /** 从库中加载并校验(库里被手改导致验签失败时按未激活处理) */
    private fun loadStatus(): LicenseStatusView {
        val row = repository.get() ?: return LicenseStatusView.notActivated()
        // 未配置公钥时无法验签,按未激活处理(activate 会拒绝并提示)
        val key = publicKey ?: return LicenseStatusView.notActivated()
        return try {
            val payload = LicenseCodec.decodeAndVerify(crypto.decrypt(row.codeEnc), key)
            val today = LocalDate.now()
            val expired = LicenseCodec.isExpired(payload.expiresAt, today)
            val daysLeft = payload.expiresAt?.let { maxOf(ChronoUnit.DAYS.between(today, it), 0) }
            LicenseStatusView(true, expired, payload.customer, payload.expiresAt, daysLeft)
        } catch (e: RuntimeException) {
            log.warn("库存授权码校验失败,按未激活处理: {}", e.message)
            LicenseStatusView.notActivated()
        }
    }

    companion object {
        private fun parsePublicKey(base64: String?): PublicKey? {
            if (base64.isNullOrBlank()) {
                return null
            }
            return try {
                val der = Base64.getDecoder().decode(base64.trim())
                KeyFactory.getInstance("Ed25519").generatePublic(X509EncodedKeySpec(der))
            } catch (e: Exception) {
                throw IllegalStateException("dq.license.public-key 配置无效: ${e.message}", e)
            }
        }
    }
}
