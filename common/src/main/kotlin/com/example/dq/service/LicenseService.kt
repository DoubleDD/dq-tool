package com.example.dq.service

import com.example.dq.license.LicenseCodec
import com.example.dq.model.LicenseAdminRequiredException
import com.example.dq.model.LicenseGenerateRequest
import com.example.dq.model.LicenseRecord
import com.example.dq.model.LicenseRecordView
import com.example.dq.model.LicenseRequiredException
import com.example.dq.model.LicenseStatusView
import com.example.dq.repository.LicenseRecordRepository
import com.example.dq.repository.LicenseRepository
import com.example.dq.util.CryptoUtil
import org.slf4j.LoggerFactory
import java.security.KeyFactory
import java.security.PrivateKey
import java.security.PublicKey
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import java.time.LocalDate
import java.time.format.DateTimeParseException
import java.time.temporal.ChronoUnit
import java.util.Base64
import java.util.UUID

/**
 * 授权码:离线 Ed25519 验签,公钥来自配置(AppConfig.licensePublicKey,由 dq.license.public-key-file 指向的公钥文件读入);
 * 激活后的授权码加密存本地 H2(license_info 单行)。
 *
 * 授权码管理(仅管理员实例):配置了签发私钥(AppConfig.licensePrivateKey)即管理员,
 * 可生成新授权码(payload 绑定当前软件版本号)并在 license_record 留档;留档可查看/删除,不可编辑。
 * 删除仅删留档记录,离线验签方案无法吊销已分发的授权码。
 */
class LicenseService(
    private val repository: LicenseRepository,
    private val crypto: CryptoUtil,
    publicKeyBase64: String,
    private val recordRepo: LicenseRecordRepository,
    privateKeyBase64: String,
    appVersion: String,
) {

    private val log = LoggerFactory.getLogger(LicenseService::class.java)

    private val publicKey: PublicKey? = parsePublicKey(publicKeyBase64)

    /** 签发私钥;可解析即管理员实例。解析失败按非管理员处理(私钥是可选配置,不影响启动) */
    private val privateKey: PrivateKey? = parsePrivateKey(privateKeyBase64)

    init {
        log.info("授权码管理:{}", if (privateKey != null) "已启用(管理员实例)" else "未启用(非管理员实例)")
    }

    private val appVersion: String = appVersion.ifBlank { "dev" }

    /** 状态缓存,activate 后刷新,避免每个请求查库验签 */
    @Volatile
    private var cached: LicenseStatusView? = null

    /** 当前授权状态(展示用,不回传授权码本身;serverUrl 永不回传) */
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

    /** 授权码留档列表(新签发的在前);完整授权码解密回传,仅管理员可见 */
    fun listLicenses(): List<LicenseRecordView> {
        requireAdmin()
        return recordRepo.findAll().map { toView(it) }
    }

    /** 生成新授权码:payload 绑定当前软件版本号,签发后留档;字段校验(非空/不含 |)由 encode 兜底 */
    @Synchronized
    fun generateLicense(req: LicenseGenerateRequest): LicenseRecordView {
        val key = requireAdmin()
        val customer = req.customer?.trim().orEmpty()
        val expiresRaw = req.expires?.trim().orEmpty()
        val expiresAt: LocalDate? = if (expiresRaw.equals("permanent", ignoreCase = true)) {
            null
        } else {
            try {
                LocalDate.parse(expiresRaw)
            } catch (e: DateTimeParseException) {
                throw IllegalArgumentException("有效期格式无效,应为 yyyy-MM-dd 或 permanent")
            }
        }
        val serverUrl = req.serverUrl?.trim()?.ifBlank { null }
        val username = req.username?.trim()?.ifBlank { null }
        // SID 未填时自动生成(UUID 去横杠,32 位);显式传入仍生效(向后兼容)
        val sid = req.sid?.trim()?.ifBlank { null }
            ?: UUID.randomUUID().toString().replace("-", "")
        val issuedAt = System.currentTimeMillis()
        val code = LicenseCodec.encode(customer, expiresAt, key,
            appVersion = appVersion, serverUrl = serverUrl.orEmpty(),
            username = username.orEmpty(), sid = sid.orEmpty(), timestamp = issuedAt)
        val record = LicenseRecord(
            id = 0, appVersion = appVersion, customer = customer, expiresAt = expiresAt,
            serverUrl = serverUrl, username = username, sid = sid,
            issuedAt = issuedAt, codeEnc = crypto.encrypt(code)!!, createdAt = null)
        val id = recordRepo.insert(record)
        return toView(record.copy(id = id))
    }

    /** 删除留档记录;不影响已分发的授权码(离线验签无法吊销) */
    fun deleteLicense(id: Long) {
        requireAdmin()
        recordRepo.delete(id)
    }

    /** 非管理员实例一律拒绝(配置了可解析的签发私钥才是管理员) */
    private fun requireAdmin(): PrivateKey {
        return privateKey
            ?: throw LicenseAdminRequiredException("当前实例未配置签发私钥,无授权码管理权限")
    }

    private fun toView(record: LicenseRecord): LicenseRecordView =
        LicenseRecordView(
            id = record.id, appVersion = record.appVersion, customer = record.customer,
            expiresAt = record.expiresAt, serverUrl = record.serverUrl, username = record.username,
            sid = record.sid, issuedAt = record.issuedAt,
            code = crypto.decrypt(record.codeEnc)!!, createdAt = record.createdAt)

    /** 从库中加载并校验(库里被手改导致验签失败时按未激活处理) */
    private fun loadStatus(): LicenseStatusView {
        val base = loadActivationStatus()
        return base.copy(admin = privateKey != null, appVersion = appVersion)
    }

    private fun loadActivationStatus(): LicenseStatusView {
        val row = repository.get() ?: return LicenseStatusView.notActivated()
        // 未配置公钥时无法验签,按未激活处理(activate 会拒绝并提示)
        val key = publicKey ?: return LicenseStatusView.notActivated()
        return try {
            val payload = LicenseCodec.decodeAndVerify(crypto.decrypt(row.codeEnc), key)
            val today = LocalDate.now()
            val expired = LicenseCodec.isExpired(payload.expiresAt, today)
            val daysLeft = payload.expiresAt?.let { maxOf(ChronoUnit.DAYS.between(today, it), 0) }
            LicenseStatusView(true, expired, payload.customer, payload.expiresAt, daysLeft,
                username = payload.username, sid = payload.sid, timestamp = payload.timestamp)
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
                throw IllegalStateException("授权验签公钥配置无效(dq.license.public-key-file): ${e.message}", e)
            }
        }
    }

    private fun parsePrivateKey(base64: String?): PrivateKey? {
        if (base64.isNullOrBlank()) {
            return null
        }
        return try {
            val der = Base64.getDecoder().decode(base64.trim())
            KeyFactory.getInstance("Ed25519").generatePrivate(PKCS8EncodedKeySpec(der))
        } catch (e: Exception) {
            // 私钥配置损坏只影响授权码管理,不阻断启动;按非管理员处理
            log.warn("签发私钥配置无效(dq.license.private-key-file),授权码管理不可用: {}", e.message)
            null
        }
    }
}
