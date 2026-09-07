package com.example.dq.model

/**
 * 局域网发现的在线实例(peer):由 UDP 心跳报文 + 报文来源地址合成,
 * host 取自报文来源 IP(不报文自带,避免多网卡/ NAT 下自报地址不可达)。
 */
data class LanPeer(
    val instanceId: String,
    val instanceName: String,
    val host: String,
    val httpPort: Int,
    val appVersion: String,
    /** 最近一次收到心跳的时间(epoch milli) */
    val lastSeenAt: Long,
)

/** 本机局域网共享状态(/api/lan/status 视图) */
data class LanShareStatus(
    /** 有效开关(system_settings.lan_enabled 覆盖,NULL 回落配置文件 dq.lan.enabled) */
    val enabled: Boolean,
    /** 发现服务实际是否在跑(enabled 且内核已启动发现线程) */
    val running: Boolean,
    val instanceId: String,
    val instanceName: String,
    /** UDP 发现端口(局域网内各实例需一致) */
    val discoveryPort: Int,
    /** 当前在线的其他实例数 */
    val onlinePeers: Int,
)

/** PUT /api/lan/settings 请求;null=不修改该项 */
data class LanSettingsRequest(
    val enabled: Boolean? = null,
    val instanceName: String? = null,
)

/** 本机标注数据预览(共享出口,供 peer 拉取前展示可同步内容;含逐行详情,不只是条数概览) */
data class LanAnnotationsPreview(
    /** 标记定义清单(名字/颜色/描述/类型),peer 按名勾选 */
    val tags: List<AnnotationTagItem> = emptyList(),
    /** 表级数据按数据源名的分布(打标/描述/所属系统行数,概览计数) */
    val datasources: List<AnnotationPreviewDs> = emptyList(),
    /** 表级打标逐行明细(哪张表打了哪个标记) */
    val tableTags: List<AnnotationTableTagItem> = emptyList(),
    /** 表描述逐行明细(含描述原文) */
    val tableDocs: List<AnnotationTableDocItem> = emptyList(),
    /** 表所属系统逐行明细 */
    val tableSystems: List<AnnotationTableSystemItem> = emptyList(),
)

/** 本机扫描任务预览(共享出口,供 peer 勾选要导入的任务) */
data class LanScanJobItem(
    val jobId: Long,
    val datasourceName: String,
    val dbName: String? = null,
    val schemaName: String,
    val status: ScanStatus,
    val totalTables: Int = 0,
    val doneTables: Int = 0,
    /** ISO_LOCAL_DATE_TIME 字符串(与扫描导出文件口径一致) */
    val createdAt: String? = null,
)

/**
 * 共享出口:本机数据源清单(供 peer 做数据源映射选择与副本新建)。
 * 密码/SSH 秘密字段为 TransferCrypto 密文——与数据源导出文件同一口径(AES-GCM + 固定内置口令,
 * 跨实例可解,非明文;「防随手打开」级别保护,仅限可信内网)。
 */
data class LanDataSourceItem(
    val name: String,
    val jdbcUrl: String,
    val username: String? = null,
    /** 连接密码(TransferCrypto 密文,非明文) */
    val passwordEnc: String? = null,
    val schemaFilter: List<String>? = null,
    val groupName: String? = null,
    // ---- SSH 隧道(秘密字段同为 TransferCrypto 密文)----
    val sshEnabled: Boolean? = null,
    val sshHost: String? = null,
    val sshPort: Int? = null,
    val sshUsername: String? = null,
    /** 认证方式:password / publickey */
    val sshAuthMethod: String? = null,
    val sshPasswordEnc: String? = null,
    val sshPrivateKeyEnc: String? = null,
    val sshPassphraseEnc: String? = null,
)

/** 数据源映射项(预览弹窗逐行由用户确认/改选):对方数据源 + 本机同名匹配 */
data class LanDsMappingItem(
    val peerName: String,
    val jdbcUrl: String,
    val username: String? = null,
    /** 本机同名数据源 id(无则 null——可选「新建到本机(不含密码)」或「跳过」) */
    val matchedLocalId: Long? = null,
)

/** GET /api/lan/preview/{instanceId} 响应:对方实例可同步内容总览(同步前预览弹窗数据源) */
data class LanPeerPreview(
    val peerInstanceName: String,
    val peerHost: String,
    val annotations: LanAnnotationsPreview,
    val scanJobs: List<LanScanJobItem>,
    /** 对方数据源清单与本机同名匹配(映射下拉数据源) */
    val datasources: List<LanDsMappingItem> = emptyList(),
    /** 本机数据源清单(映射下拉选项) */
    val localDatasources: List<ScanPreviewLocalDs> = emptyList(),
)

/**
 * 标注数据选择(同步标记与描述):tagNames 为勾选同步的标记名(空=不同步标记);
 * 表级打标只带被勾选标记的关系,其余三类按类别整体勾选。
 */
data class LanAnnotationSelection(
    val tagNames: List<String> = emptyList(),
    val includeTableTags: Boolean = true,
    val includeDocs: Boolean = true,
    val includeSystems: Boolean = true,
)

/**
 * POST /api/lan/pull/{instanceId} 请求:annotations 与 scanJobIds 分别控制两段拉取,
 * 为 null 表示不拉该段;scanJobIds 空列表等同不拉(不发起请求)。
 * dsMapping 为用户确认的数据源映射(对方数据源名 → 本机数据源 id,0=跳过该数据源的数据);
 * createDatasources 为要新建到本机的对方数据源名(无密码副本,密码随后人工补配)。
 */
data class LanPullRequest(
    /** 标注数据选择(标记与描述);null=不同步标注 */
    val annotations: LanAnnotationSelection? = null,
    /** 勾选导入的对方扫描任务 id;null/空=不导入扫描记录 */
    val scanJobIds: List<Long>? = null,
    /** 数据源映射(对方名 → 本机 id,0=跳过);未给出的名按名回退匹配 */
    val dsMapping: Map<String, Long>? = null,
    /** 新建到本机的对方数据源名(只带名称/jdbcUrl/用户名,绝不含密码) */
    val createDatasources: List<String>? = null,
)

/** 拉取结果:分类型返回导入摘要,某类型失败记 errors 不中断其他类型 */
data class LanPullResult(
    val peerInstanceName: String,
    val peerHost: String,
    val annotations: AnnotationImportResult? = null,
    val scans: ScanImportResult? = null,
    /** 本次新建到本机的数据源名(无密码副本) */
    val datasourcesCreated: List<String> = emptyList(),
    val errors: List<String> = emptyList(),
)
