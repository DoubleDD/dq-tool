package com.example.dq.service

import com.example.dq.license.LicenseCodec
import com.example.dq.license.LicenseMenu
import com.example.dq.model.LicenseAdminRequiredException
import com.example.dq.model.LicenseGenerateRequest
import com.example.dq.model.LicenseMenuRequiredException
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

    /** 当前授权状态(展示用;code 回传当前激活码明文供前端输入框回填,serverUrl 永不回传) */
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

    /** 生成新授权码:payload 绑定当前软件版本号、开放菜单列表与免鉴权标记,签发后留档;字段校验(非空/不含 |)由 encode 兜底 */
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
        // 菜单列表:显式传 menus 按它签发(勾选即客户实例侧边栏可见);未传时兼容旧版签发端按 features 推导;
        // 两者皆无则默认开放除 数据比对/授权管理 外的全部菜单(与旧版「基础功能恒有」默认口径一致);未知 key 忽略并按枚举声明顺序规范化
        val menuSet = when {
            req.menus != null -> LicenseMenu.parse(req.menus.joinToString(",") { it.trim() })
            req.features != null -> LicenseMenu.fromLegacyFeatures(req.features.joinToString(",") { it.trim() })
            else -> LicenseMenu.fromLegacyFeatures(null)
        }
        val menus = LicenseMenu.encode(menuSet).ifBlank { null }
        // 免接口鉴权标记(演示用):签发后该实例所有请求跳过 dq.access-token 校验
        val bypassAuth = req.bypassAuth == true
        // 备注仅管理端留档展示,不写入授权码 payload
        val remark = req.remark?.trim()?.ifBlank { null }
        val issuedAt = System.currentTimeMillis()
        val code = LicenseCodec.encode(customer, expiresAt, key,
            appVersion = appVersion, serverUrl = serverUrl.orEmpty(),
            username = username.orEmpty(), sid = sid.orEmpty(), timestamp = issuedAt,
            features = "", menus = menus.orEmpty(), bypassAuth = bypassAuth)
        val record = LicenseRecord(
            id = 0, appVersion = appVersion, customer = customer, expiresAt = expiresAt,
            serverUrl = serverUrl, username = username, sid = sid,
            issuedAt = issuedAt, features = null, menus = menus, bypassAuth = bypassAuth,
            remark = remark, codeEnc = crypto.encrypt(code)!!, createdAt = null)
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
            // 旧留档(NULL)按旧功能段推导开放菜单,与新码口径一致
            menus = record.menus ?: LicenseMenu.encode(LicenseMenu.fromLegacyFeatures(record.features)),
            bypassAuth = record.bypassAuth == true,
            remark = record.remark,
            code = crypto.decrypt(record.codeEnc)!!, createdAt = record.createdAt)

    /** 从库中加载并校验(库里被手改导致验签失败时按未激活处理) */
    private fun loadStatus(): LicenseStatusView {
        val base = loadActivationStatus()
        return base.copy(admin = privateKey != null, appVersion = appVersion)
    }

    /**
     * 菜单前置校验:授权码未开放该菜单抛 LicenseMenuRequiredException(web 层转 403)。
     * @param requireActive 是否要求已激活未过期;授权码管理(license-admin)在 /api/license 前缀下,
     *                      管理员实例可不激活使用 —— 未激活直接放行,已激活则校验授权码是否开放该菜单
     */
    @JvmOverloads
    fun checkMenu(menu: LicenseMenu, requireActive: Boolean = true) {
        if (requireActive) {
            checkActive()
        } else {
            val pre = status()
            if (pre.activated && pre.expired) {
                throw LicenseRequiredException("授权已于 ${pre.expiresAt} 到期,请更新授权码")
            }
            if (!pre.activated) {
                return
            }
        }
        val granted = status().menus.orEmpty()
        if (menu.key !in granted) {
            throw LicenseMenuRequiredException("当前授权未开放「${menu.label}」菜单,请联系分发方调整授权码")
        }
    }

    /**
     * 授权码「免接口鉴权」标记是否生效(演示用):已激活未过期且授权码带标记时返回 true,
     * web 层据此整体跳过 dq.access-token 校验;任何异常按 false 处理(宁严勿松)。
     */
    fun isAuthBypassed(): Boolean = try {
        status().bypassAuth
    } catch (e: Exception) {
        log.warn("读取授权免鉴权标记失败,按不豁免处理: {}", e.message)
        false
    }

    private fun loadActivationStatus(): LicenseStatusView {
        val row = repository.get() ?: return LicenseStatusView.notActivated()
        // 未配置公钥时无法验签,按未激活处理(activate 会拒绝并提示)
        val key = publicKey ?: return LicenseStatusView.notActivated()
        return try {
            val code = crypto.decrypt(row.codeEnc)
            val payload = LicenseCodec.decodeAndVerify(code, key)
            val today = LocalDate.now()
            val expired = LicenseCodec.isExpired(payload.expiresAt, today)
            val daysLeft = payload.expiresAt?.let { maxOf(ChronoUnit.DAYS.between(today, it), 0) }
            LicenseStatusView(true, expired, payload.customer, payload.expiresAt, daysLeft,
                username = payload.username, sid = payload.sid, timestamp = payload.timestamp,
                menus = LicenseMenu.ALL.filter { it in LicenseMenu.granted(payload.features, payload.menus) }.map { it.key },
                bypassAuth = !expired && payload.bypassAuth,
                code = code)
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
