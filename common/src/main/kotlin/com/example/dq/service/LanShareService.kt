package com.example.dq.service

import com.example.dq.config.AppConfig
import com.example.dq.discovery.LanDiscoveryService
import com.example.dq.model.AnnotationExportFile
import com.example.dq.model.AnnotationImportResult
import com.example.dq.model.AnnotationPreviewDs
import com.example.dq.model.DataSourceRequest
import com.example.dq.model.LanAnnotationSelection
import com.example.dq.model.LanAnnotationsPreview
import com.example.dq.model.LanDataSourceItem
import com.example.dq.model.LanDsMappingItem
import com.example.dq.model.LanLocalAddress
import com.example.dq.model.LanPeer
import com.example.dq.model.LanPeerPreview
import com.example.dq.model.LanPullRequest
import com.example.dq.model.LanPullResult
import com.example.dq.model.LanScanJobItem
import com.example.dq.model.LanSettingsRequest
import com.example.dq.model.LanShareInfo
import com.example.dq.model.LanShareStatus
import com.example.dq.model.ScanImportResult
import com.example.dq.model.ScanPreviewLocalDs
import com.example.dq.repository.DataSourceRepository
import com.example.dq.repository.SystemSettingsRepository
import com.example.dq.util.CryptoUtil
import com.example.dq.util.TransferCrypto
import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import org.slf4j.LoggerFactory
import java.io.ByteArrayInputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.URI
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/**
 * 局域网共享编排:实例身份(instanceId/instanceName 持久化在 system_settings 单行)、
 * 发现服务生命周期(按有效开关启停)、共享出口(导出标记与描述/全部扫描记录供 peer 经 HTTP 拉取)、
 * 主动拉取(从 peer 的共享导出端点取数据并复用现有 Transfer 服务导入合并)。
 *
 * 数据复用口径与手工导入完全一致:标记按 name 合并,表级数据按 数据源名+db+schema+table 对齐;
 * 扫描记录导入的 mapping 为强制口径,此处自动按「文件数据源名 = 本机同名数据源 id」构建,
 * 匹配不到的数据源任务跳过(进 warnings),不产生静默错配。
 */
class LanShareService(
    private val discovery: LanDiscoveryService,
    private val settingsRepo: SystemSettingsRepository,
    private val annotationTransfer: AnnotationTransferService,
    private val scanTransfer: ScanTransferService,
    private val config: AppConfig,
    private val dataSourceRepo: DataSourceRepository,
    private val dataSourceService: DataSourceService,
    private val crypto: CryptoUtil,
) {

    private val log = LoggerFactory.getLogger(LanShareService::class.java)

    /** 共享出口预览/拉取过滤的 JSON 读写(Jackson 2 + Kotlin 模块,与 Transfer 体系一致) */
    private val mapper = com.fasterxml.jackson.module.kotlin.jacksonObjectMapper()

    /** start 时记录的 HTTP 端口:页面开关「开」时即时重启发现用(0=尚未启动过,等 start 生效) */
    @Volatile
    private var httpPort: Int = 0

    /** 手动添加实例的在线表:key="host:port",由周期直连探测维护(纯内存,地址清单持久化在 system_settings) */
    private val manualOnline = ConcurrentHashMap<String, LanPeer>()
    private var manualProbeScheduler: ScheduledExecutorService? = null

    /** 有效开关:DB 自定义优先,NULL 回落配置文件默认 */
    fun lanEnabled(): Boolean = settingsRepo.get()?.lanEnabled ?: config.lan.enabled

    /**
     * 启动(幂等):开关开时确保实例 id 已生成,并启动 UDP 发现。
     * 由 server 壳层在内核就绪后调用(需要实际 HTTP 端口随心跳广播)。
     */
    @Synchronized
    fun start(httpPort: Int) {
        this.httpPort = httpPort
        // 实例 id 无论开关都生成一次,保证状态接口/心跳有稳定身份
        val instanceId = ensureInstanceId()
        if (!lanEnabled()) {
            log.info("局域网共享已关闭(dq.lan.enabled / 页面开关),不启动发现")
            return
        }
        discovery.start(httpPort, instanceId, { effectiveInstanceName() }, config.appVersion)
        startManualProbe()
    }

    /** 停止发现(幂等);ServiceEnv.shutdown 时调用 */
    @Synchronized
    fun stop() {
        discovery.stop()
        stopManualProbe()
    }

    fun status(): LanShareStatus = LanShareStatus(
        enabled = lanEnabled(),
        running = discovery.isRunning(),
        instanceId = ensureInstanceId(),
        instanceName = effectiveInstanceName(),
        discoveryPort = config.lan.discoveryPort,
        onlinePeers = peers().size,
        httpPort = httpPort,
        addresses = localAddresses(),
        appVersion = config.appVersion,
    )

    /** 本机局域网 IPv4 地址(页面「本机地址」展示,用户据此告知他人手动添加地址);排除回环/链路本地,内网地址在前 */
    private fun localAddresses(): List<LanLocalAddress> = try {
        java.net.NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { ni -> ni.inetAddresses.toList().map { ni to it } }
            .filter { (_, addr) -> addr is java.net.Inet4Address }
            .filter { (_, addr) -> !addr.isLoopbackAddress && !addr.isLinkLocalAddress }
            .sortedByDescending { (_, addr) -> addr.isSiteLocalAddress }
            .map { (ni, addr) -> LanLocalAddress(addr.hostAddress, ni.displayName ?: ni.name ?: "") }
            .distinct()
    } catch (e: Exception) {
        log.warn("枚举本机网卡地址失败: {}", e.message)
        emptyList()
    }

    /** 当前在线实例:UDP 心跳发现 ∪ 手动添加直连探测,按 instanceId 去重(手动项优先,保留 manual 标记) */
    fun peers(): List<LanPeer> {
        val merged = LinkedHashMap<String, LanPeer>()
        discovery.peers().forEach { merged[it.instanceId] = it }
        val deadline = System.currentTimeMillis() - config.lan.announceIntervalSeconds.coerceIn(1, 300) * 3_000L
        manualOnline.entries.removeIf { it.value.lastSeenAt < deadline }
        manualOnline.values.forEach { merged[it.instanceId] = it }
        return merged.values.sortedWith(compareBy({ it.instanceName }, { it.host }, { it.httpPort }))
    }

    /** 保存共享设置:null 字段保留已存值;开关/名称即时生效(启停发现,名称下个心跳生效) */
    @Synchronized
    fun saveSettings(req: LanSettingsRequest): LanShareStatus {
        req.instanceName?.let { require(it.length <= 128) { "实例名称最长 128 字符" } }
        val prev = settingsRepo.get()
        val row = (prev ?: SystemSettingsRepository.SystemSettingsRow(
            workers = null, chunksPerTable = null, rowThreshold = null,
            sizeThresholdBytes = null, sampleRows = null, statementTimeoutSeconds = null,
        )).copy(
            lanEnabled = req.enabled ?: prev?.lanEnabled,
            instanceName = req.instanceName ?: prev?.instanceName,
            instanceId = prev?.instanceId ?: UUID.randomUUID().toString(),
        )
        settingsRepo.upsert(row)
        // 开关即时应用:start 已记录 HTTP 端口时可就地启停发现;
        // 端口未知(内核就绪前)说明尚未 start,等 start(httpPort) 按新开关生效
        if (lanEnabled()) {
            if (!discovery.isRunning() && httpPort > 0) {
                discovery.start(httpPort, ensureInstanceId(), { effectiveInstanceName() }, config.appVersion)
            }
            startManualProbe()
        } else {
            if (discovery.isRunning()) {
                discovery.stop()
            }
            stopManualProbe()
        }
        return status()
    }

    // ---- 手动添加实例(UDP 广播发现不可用的网络:跨子网/VLAN/AP 隔离等,按地址直连探测) ----

    /** 共享出口:本机实例身份,供其他实例手动添加时直连确认 */
    fun shareInfo(): LanShareInfo = LanShareInfo(ensureInstanceId(), effectiveInstanceName(), config.appVersion)

    /** 手动添加的实例地址清单(持久化值解析) */
    fun manualPeers(): List<Pair<String, Int>> = parseManualPeers(settingsRepo.get()?.lanManualPeers)

    /**
     * 手动添加实例:立即直连探测(/api/lan/share/info),确认对方是本软件实例才持久化;
     * 连不上/对方版本无此端点(旧版本)/对方是本机 抛 400 语义异常(带具体原因)。重复添加同地址视为刷新。
     */
    @Synchronized
    fun addManualPeer(host: String, port: Int?): LanPeer {
        val h = host.trim()
        require(h.isNotEmpty()) { "地址不能为空" }
        val p = port ?: DEFAULT_HTTP_PORT
        require(p in 1..65535) { "端口不正确: $p" }
        require(!isSelfAddress(h, p)) { "该地址是本机实例,无需添加" }
        val peer = probeManualPeerOrThrow(h, p)
        val list = manualPeers().filter { !(it.first == h && it.second == p) } + (h to p)
        persistManualPeers(list)
        manualOnline["$h:$p"] = peer
        startManualProbe()
        log.info("手动添加局域网实例 {}:{}({})", h, p, peer.instanceName)
        return peer
    }

    /** 移除手动添加的实例(持久化清单与内存在线表同步删除) */
    @Synchronized
    fun removeManualPeer(host: String, port: Int) {
        persistManualPeers(manualPeers().filter { !(it.first == host && it.second == port) })
        manualOnline.remove("$host:$port")
    }

    /**
     * 是否本机地址:端口等于本机 HTTP 端口,且 host 解析为回环地址或本机网卡地址。
     * 添加手动实例时在 HTTP 探测前拦截本机——经 VPN/虚拟网卡地址探测本机可能超时,
     * 走不到探测成功后的 instanceId 判等,会误报「连不上」。
     */
    private fun isSelfAddress(host: String, port: Int): Boolean {
        if (httpPort <= 0 || port != httpPort) {
            return false
        }
        val resolved = try {
            InetAddress.getByName(host)
        } catch (e: Exception) {
            return false // 解析不出交给直连探测报「连不上」
        }
        return resolved.isLoopbackAddress || localAddresses().any { it.address == resolved.hostAddress }
    }

    /** 直连探测手动实例(添加时用):成功返回 LanPeer;连不上/非本软件实例/是本机 抛带具体原因的异常 */
    private fun probeManualPeerOrThrow(host: String, port: Int): LanPeer {
        val info = try {
            mapper.readValue(httpGet("http://$host:$port/api/lan/share/info"), LanShareInfo::class.java)
        } catch (e: Exception) {
            throw IllegalArgumentException(
                "连不上 $host:$port(${e.message};确认对方已启动、已开启局域网共享且版本支持手动添加)")
        }
        require(info.instanceId.isNotBlank()) { "$host:$port 不是有效的 dq-tool 实例" }
        require(info.instanceId != ensureInstanceId()) { "该地址是本机实例,无需添加" }
        return LanPeer(
            instanceId = info.instanceId,
            instanceName = info.instanceName.ifBlank { "未命名实例" },
            host = host,
            httpPort = port,
            appVersion = info.appVersion,
            lastSeenAt = System.currentTimeMillis(),
            manual = true,
        )
    }

    /** 周期探测用:失败返回 null(原因记 debug,不打扰用户) */
    private fun probeManualPeer(host: String, port: Int): LanPeer? = try {
        probeManualPeerOrThrow(host, port)
    } catch (e: Exception) {
        log.debug("手动实例 {}:{} 探测失败: {}", host, port, e.message)
        null
    }

    /** 周期探测全部手动实例(与心跳同间隔);探测失败不更新 lastSeenAt,超时后按既有规则判离线 */
    private fun probeAllManualPeers() {
        for ((host, port) in manualPeers()) {
            probeManualPeer(host, port)?.let { manualOnline["$host:$port"] = it }
        }
    }

    @Synchronized
    private fun startManualProbe() {
        if (manualProbeScheduler != null) {
            return
        }
        val interval = config.lan.announceIntervalSeconds.coerceIn(1, 300).toLong()
        manualProbeScheduler = Executors.newSingleThreadScheduledExecutor { r ->
            Thread(r, "lan-manual-probe").apply { isDaemon = true }
        }.also { it.scheduleWithFixedDelay({ runCatching { probeAllManualPeers() } }, 0, interval, TimeUnit.SECONDS) }
    }

    @Synchronized
    private fun stopManualProbe() {
        manualProbeScheduler?.shutdownNow()
        manualProbeScheduler = null
        manualOnline.clear()
    }

    /** 持久化手动实例清单(其他设置列保持原值) */
    private fun persistManualPeers(list: List<Pair<String, Int>>) {
        val prev = settingsRepo.get()
        val row = (prev ?: SystemSettingsRepository.SystemSettingsRow(
            workers = null, chunksPerTable = null, rowThreshold = null,
            sizeThresholdBytes = null, sampleRows = null, statementTimeoutSeconds = null,
        )).copy(lanManualPeers = formatManualPeers(list))
        settingsRepo.upsert(row)
    }

    // ---- 共享出口(供 peer 经 /api/lan/share/* 拉取) ----

    /** 导出本机全部标记(定义+表级打标)+ 表描述 + 表所属系统,JSON 同手工导出格式 */
    fun exportAnnotations(out: OutputStream) = annotationTransfer.export(out)

    /** 导出本机扫描记录(含随任务携带的标注数据),JSON 同手工导出格式;jobIds 为空 = 全部任务 */
    fun exportScans(jobIds: List<Long>, out: OutputStream) = scanTransfer.export(jobIds, out)

    /** 本机标注数据预览:复用导出→解析,避免另写一套仓储查询(标注数据量小,内存开销可忽略);含逐行明细 */
    fun localAnnotationsPreview(): LanAnnotationsPreview {
        val buf = java.io.ByteArrayOutputStream()
        annotationTransfer.export(buf)
        val file = mapper.readValue(buf.toByteArray(), AnnotationExportFile::class.java)
        val rows = LinkedHashMap<String, AnnotationPreviewDs>()
        fun row(name: String) = rows.getOrPut(name) { AnnotationPreviewDs(name, 0, 0, 0) }
        file.tableTags.forEach { val r = row(it.datasourceName); rows[it.datasourceName] = r.copy(tableTags = r.tableTags + 1) }
        file.tableDocs.forEach { val r = row(it.datasourceName); rows[it.datasourceName] = r.copy(tableDocs = r.tableDocs + 1) }
        file.tableSystems.forEach { val r = row(it.datasourceName); rows[it.datasourceName] = r.copy(tableSystems = r.tableSystems + 1) }
        return LanAnnotationsPreview(
            tags = file.tags,
            datasources = rows.values.toList(),
            tableTags = file.tableTags,
            tableDocs = file.tableDocs,
            tableSystems = file.tableSystems,
        )
    }

    /** 本机扫描任务预览(供 peer 勾选要导入的任务) */
    fun localScanJobsPreview(): List<LanScanJobItem> = scanTransfer.localJobsPreview()

    /** 本机数据源清单(共享出口;秘密字段为 TransferCrypto 密文,与数据源导出文件同一口径,供 peer 建副本) */
    fun localDatasourcesPreview(): List<LanDataSourceItem> =
        dataSourceRepo.findAll()
            .filter { !it.name.isNullOrBlank() }
            .map {
                LanDataSourceItem(
                    name = it.name!!,
                    jdbcUrl = it.jdbcUrl ?: "",
                    username = it.username,
                    passwordEnc = TransferCrypto.encrypt(crypto.decrypt(it.password)),
                    schemaFilter = it.schemaFilter,
                    groupName = it.groupName,
                    sshEnabled = it.sshEnabled,
                    sshHost = it.sshHost,
                    sshPort = it.sshPort,
                    sshUsername = it.sshUsername,
                    sshAuthMethod = it.sshAuthMethod,
                    sshPasswordEnc = TransferCrypto.encrypt(crypto.decrypt(it.sshPassword)),
                    sshPrivateKeyEnc = TransferCrypto.encrypt(crypto.decrypt(it.sshPrivateKey)),
                    sshPassphraseEnc = TransferCrypto.encrypt(crypto.decrypt(it.sshPassphrase)),
                )
            }

    /** 同步前预览:拉取对方实例的标注/扫描任务/数据源预览,并与本机数据源按名预匹配;连不上抛 400/500 语义异常 */
    fun previewPeer(instanceId: String): LanPeerPreview {
        val peer = peers().find { it.instanceId == instanceId }
            ?: throw IllegalArgumentException("实例不在线或不存在: $instanceId")
        val base = "http://${peer.host}:${peer.httpPort}"
        val annotations = mapper.readValue(
            httpGet("$base/api/lan/share/annotations/preview"), LanAnnotationsPreview::class.java)
        val scanJobs = mapper.readValue(
            httpGet("$base/api/lan/share/scans/preview"),
            object : TypeReference<List<LanScanJobItem>>() {})
        val peerDatasources = mapper.readValue(
            httpGet("$base/api/lan/share/datasources"),
            object : TypeReference<List<LanDataSourceItem>>() {})
        // 与本机数据源按名预匹配(用户可在弹窗里改选/新建/跳过)
        val local = dataSourceRepo.findAll().filter { !it.name.isNullOrBlank() }
        val localIdByName = local.associate { it.name!! to it.id!! }
        return LanPeerPreview(
            peerInstanceName = peer.instanceName,
            peerHost = peer.host,
            annotations = annotations,
            scanJobs = scanJobs,
            datasources = peerDatasources.map {
                LanDsMappingItem(it.name, it.jdbcUrl, it.username, localIdByName[it.name])
            },
            localDatasources = local.map { ScanPreviewLocalDs(it.id!!, it.name!!) },
        )
    }

    // ---- 主动拉取 ----

    /**
     * 按用户勾选从在线 peer 拉取数据并导入合并;某段失败记 errors 不中断另一段。
     * peer 不在线(心跳超时/从未发现)抛 400。
     * 数据源映射:先按 createDatasources 把对方数据源建成无密码副本(密码随后人工补配),
     * 再合并用户确认的 dsMapping(0=跳过);扫描记录对未映射的名按名回退自动匹配。
     */
    fun pull(instanceId: String, req: LanPullRequest): LanPullResult {
        val peer = peers().find { it.instanceId == instanceId }
            ?: throw IllegalArgumentException("实例不在线或不存在: $instanceId")
        val base = "http://${peer.host}:${peer.httpPort}"
        val errors = mutableListOf<String>()
        val datasourcesCreated = mutableListOf<String>()
        var annotations: AnnotationImportResult? = null
        var scans: ScanImportResult? = null

        // 数据源映射:用户确认值优先;待新建的对方数据源建成无密码副本后映射到新 id
        val mapping = LinkedHashMap<String, Long>(req.dsMapping ?: emptyMap())
        if (!req.createDatasources.isNullOrEmpty()) {
            val peerDs = try {
                mapper.readValue(httpGet("$base/api/lan/share/datasources"),
                    object : TypeReference<List<LanDataSourceItem>>() {})
            } catch (e: Exception) {
                log.warn("从 {} 拉取数据源清单失败: {}", peer.instanceName, e.message)
                errors += "数据源清单拉取失败: ${e.message}"
                emptyList<LanDataSourceItem>()
            }
            for (name in req.createDatasources) {
                if ((mapping[name] ?: 0L) > 0L) continue // 已显式映射到本机数据源,不重复建
                val info = peerDs.find { it.name == name }
                if (info == null) {
                    errors += "对方没有数据源「$name」,无法同步到本机"
                    continue
                }
                try {
                    // 同名已存在则直接用现有的(不建重名副本)
                    val existing = dataSourceRepo.findAll().find { it.name == name }?.id
                    val localId = existing ?: dataSourceService.create(
                        DataSourceRequest(
                            name = info.name,
                            jdbcUrl = info.jdbcUrl,
                            username = info.username,
                            // 秘密字段随行走 TransferCrypto 密文(与数据源导出文件同一口径),此处解密落库
                            password = TransferCrypto.decrypt(info.passwordEnc),
                            rowThreshold = null,
                            sizeThresholdBytes = null,
                            schemaFilter = info.schemaFilter,
                            groupName = info.groupName,
                            sshEnabled = info.sshEnabled,
                            sshHost = info.sshHost,
                            sshPort = info.sshPort,
                            sshUsername = info.sshUsername,
                            sshAuthMethod = info.sshAuthMethod,
                            sshPassword = TransferCrypto.decrypt(info.sshPasswordEnc),
                            sshPrivateKey = TransferCrypto.decrypt(info.sshPrivateKeyEnc),
                            sshPassphrase = TransferCrypto.decrypt(info.sshPassphraseEnc),
                        )
                    )
                    mapping[name] = localId
                    if (existing == null) {
                        datasourcesCreated += name
                        log.info("对方数据源「{}」已同步到本机(id={})", name, localId)
                    }
                } catch (e: Exception) {
                    log.warn("数据源「{}」同步到本机失败: {}", name, e.message)
                    errors += "数据源「$name」同步到本机失败: ${e.message}"
                }
            }
        }

        req.annotations?.let { sel ->
            try {
                val bytes = httpGet("$base/api/lan/share/annotations")
                val file = mapper.readValue(bytes, AnnotationExportFile::class.java)
                val filtered = filterAnnotations(file, sel)
                val out = java.io.ByteArrayOutputStream()
                mapper.writeValue(out, filtered)
                annotations = annotationTransfer.importJson(ByteArrayInputStream(out.toByteArray()), mapping)
            } catch (e: Exception) {
                log.warn("从 {} 拉取标记与描述失败: {}", peer.instanceName, e.message)
                errors += "标记与描述同步失败: ${e.message}"
            }
        }
        val jobIds = req.scanJobIds
        if (!jobIds.isNullOrEmpty()) {
            try {
                val ids = jobIds.joinToString(",")
                val bytes = httpGet("$base/api/lan/share/scans?ids=$ids", readTimeoutMs = 120_000)
                // 扫描记录导入的 mapping 是强制口径(未映射=跳过):按名自动匹配打底,用户确认值(含 0=跳过)覆盖
                val preview = scanTransfer.preview(ByteArrayInputStream(bytes))
                val auto = preview.datasources
                    .mapNotNull { d -> d.matchedDatasourceId?.let { d.datasourceName to it } }
                    .toMap()
                scans = scanTransfer.importJson(ByteArrayInputStream(bytes), auto + mapping)
            } catch (e: Exception) {
                log.warn("从 {} 拉取扫描记录失败: {}", peer.instanceName, e.message)
                errors += "扫描记录导入失败: ${e.message}"
            }
        }
        return LanPullResult(
            peerInstanceName = peer.instanceName,
            peerHost = peer.host,
            annotations = annotations,
            scans = scans,
            datasourcesCreated = datasourcesCreated,
            errors = errors,
        )
    }

    /**
     * 按用户勾选过滤标注导出文件(伴生函数,便于单测):
     * 标记定义只保留勾选的;表级打标只保留「类别勾选 且 标记被勾选」的行;描述/所属系统按类别整体取舍。
     */
    internal fun filterAnnotations(file: AnnotationExportFile, sel: LanAnnotationSelection): AnnotationExportFile {
        val keepTags = sel.tagNames.toSet()
        return file.copy(
            tags = file.tags.filter { it.name in keepTags },
            tableTags = if (sel.includeTableTags) file.tableTags.filter { it.tagName in keepTags } else emptyList(),
            tableDocs = if (sel.includeDocs) file.tableDocs else emptyList(),
            tableSystems = if (sel.includeSystems) file.tableSystems else emptyList(),
        )
    }

    /** 确保实例 id 已生成并持久化(其他列为 null 时新建单行) */
    @Synchronized
    private fun ensureInstanceId(): String {
        val prev = settingsRepo.get()
        prev?.instanceId?.let { return it }
        val id = UUID.randomUUID().toString()
        settingsRepo.upsert((prev ?: SystemSettingsRepository.SystemSettingsRow(
            workers = null, chunksPerTable = null, rowThreshold = null,
            sizeThresholdBytes = null, sampleRows = null, statementTimeoutSeconds = null,
        )).copy(instanceId = id))
        return id
    }

    /** 有效实例名:DB 自定义优先,空/未设置回落主机名 */
    private fun effectiveInstanceName(): String {
        val custom = settingsRepo.get()?.instanceName?.trim()
        if (!custom.isNullOrEmpty()) {
            return custom
        }
        return runCatching { InetAddress.getLocalHost().hostName }.getOrNull()?.takeIf { it.isNotBlank() }
            ?: "未命名实例"
    }

    /** 简单 HTTP GET(读全部响应字节);非 2xx 抛异常带状态码 */
    private fun httpGet(url: String, readTimeoutMs: Int = 15_000): ByteArray {
        val conn = URI(url).toURL().openConnection() as HttpURLConnection
        conn.connectTimeout = 5_000
        conn.readTimeout = readTimeoutMs
        try {
            val code = conn.responseCode
            if (code !in 200..299) {
                throw IllegalStateException("对方实例返回 HTTP $code")
            }
            return conn.inputStream.use { it.readBytes() }
        } finally {
            conn.disconnect()
        }
    }

    companion object {
        /** 手动添加未填端口时的默认 HTTP 端口(与 server application.yml 默认一致) */
        const val DEFAULT_HTTP_PORT = 10000

        /** 解析持久化的手动实例清单:逗号分隔 host:port,空白/非法项忽略 */
        internal fun parseManualPeers(raw: String?): List<Pair<String, Int>> =
            raw?.split(',')?.mapNotNull { item ->
                val t = item.trim()
                val idx = t.lastIndexOf(':')
                if (idx <= 0 || idx == t.length - 1) {
                    null
                } else {
                    val port = t.substring(idx + 1).toIntOrNull()
                    if (port == null || port !in 1..65535) null else t.substring(0, idx) to port
                }
            } ?: emptyList()

        /** 手动实例清单格式化回持久化串;空清单存 NULL */
        internal fun formatManualPeers(peers: List<Pair<String, Int>>): String? =
            peers.joinToString(",") { "${it.first}:${it.second}" }.ifEmpty { null }
    }
}
