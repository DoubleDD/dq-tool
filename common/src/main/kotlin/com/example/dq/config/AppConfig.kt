package com.example.dq.config

import java.nio.file.Files
import java.nio.file.Path
import java.util.Properties
import kotlin.io.path.exists

/** 扫描参数,默认值与原 application.yml 的 dq.scan.* 一致 */
data class ScanConfig(
    val workers: Int = 8,
    val chunksPerTable: Int = 100,
    val rowThreshold: Long = 1_000_000L,
    val sizeThresholdBytes: Long = 10_737_418_240L,
    val sampleRows: Long = 100_000L,
    val statementTimeoutSeconds: Int = 1800,
)

/** AI 大模型接口默认配置:页面「AI 配置」未设置的字段逐字段回落到这里 */
data class AiDefaults(
    val apiKey: String = "",
    val baseUrl: String = "",
    val model: String = "",
    /** 计费价格默认值 = DeepSeek 官方价(2026-08 起,旗舰 V4-Pro;默认峰谷计价开启:工作时间段按高峰价,其余/周末按谷价) */
    val peakValleyEnabled: Boolean = true,  // 是否启用峰谷计价
    val peakInputPrice: Double = 9.0,       // 工作时间(高峰)输入价 / 单一输入价(元/百万 token)
    val peakOutputPrice: Double = 27.0,     // 工作时间(高峰)输出价(元/百万 token)
    val valleyInputPrice: Double = 4.5,     // 非工作时间(谷价)输入价(元/百万 token)
    val valleyOutputPrice: Double = 13.5,   // 非工作时间(谷价)输出价(元/百万 token)
    /** 工作时间段(高峰),可多段;"HH:mm-HH:mm,..." */
    val workPeriods: String = "09:00-12:00,14:00-18:00",
    val weekendValley: Boolean = true,      // 周末全天按谷价
)

/** 局域网共享(实例自动发现 + 数据互拉)默认配置;页面「局域网共享」的开关覆盖 enabled */
data class LanConfig(
    /** 默认开关(system_settings.lan_enabled 为 NULL 时回落到这里) */
    val enabled: Boolean = true,
    /** UDP 发现端口(广播心跳 + 监听);同一局域网内各实例需一致 */
    val discoveryPort: Int = 17386,
    /** 心跳间隔(秒);超过 3 个间隔未收到心跳的实例判离线 */
    val announceIntervalSeconds: Int = 5,
)

/**
 * 应用配置。默认值与原 Spring Boot 工程的 application.yml 对齐,
 * 支持在数据目录下放 config.properties 覆盖(键名沿用 yml 的点分层级,如 dq.scan.workers=16)。
 */
data class AppConfig(
    val dataDir: Path,
    val scan: ScanConfig = ScanConfig(),
    val securitySecret: String = "change-me-32bytes-secret-key-0000",
    val ai: AiDefaults = AiDefaults(),
    /** 授权码验签公钥(base64,Ed25519);空表示未配置,激活会被拒绝 */
    val licensePublicKey: String = "",
    /** 签发私钥(base64,Ed25519 PKCS8);非空即管理员实例,开放授权码管理。绝不外泄到接口/日志 */
    val licensePrivateKey: String = "",
    /** 软件版本号(构建期注入,与安装包版本一致);空表示未知 */
    val appVersion: String = "",
    /** 局域网共享默认配置 */
    val lan: LanConfig = LanConfig(),
) {
    /** H2 文件库连接串,与原工程一致 */
    val h2JdbcUrl: String
        get() = "jdbc:h2:file:${dataDir.toAbsolutePath()}/dqconfig;AUTO_SERVER=TRUE"

    /** AI 用量统计独立 H2 文件库(调用流水含请求/响应内容,数据量大,与主库分离) */
    val h2AiUsageJdbcUrl: String
        get() = "jdbc:h2:file:${dataDir.toAbsolutePath()}/dqaiusage;AUTO_SERVER=TRUE"

    companion object {
        fun load(): AppConfig {
            // 安装版由打包参数注入 -Ddq.data.dir=~/.dq-tool/data;
            // 也可通过环境变量 DQ_DATA_DIR 指定;直接运行默认 ./data
            val dataDir = Path.of(
                System.getProperty("dq.data.dir")
                    ?: System.getenv("DQ_DATA_DIR")
                    ?: "./data"
            )
            Files.createDirectories(dataDir)

            val props = Properties()
            val file = dataDir.resolve("config.properties")
            if (file.exists()) {
                Files.newInputStream(file).use { props.load(it) }
            }

            fun str(key: String): String? = props.getProperty(key)?.takeIf { it.isNotBlank() }
            fun int(key: String): Int? = str(key)?.toIntOrNull()
            fun long(key: String): Long? = str(key)?.toLongOrNull()

            return AppConfig(
                dataDir = dataDir,
                scan = ScanConfig(
                    workers = int("dq.scan.workers") ?: 8,
                    chunksPerTable = int("dq.scan.chunks-per-table") ?: 100,
                    rowThreshold = long("dq.scan.row-threshold") ?: 1_000_000L,
                    sizeThresholdBytes = long("dq.scan.size-threshold-bytes") ?: 10_737_418_240L,
                    sampleRows = long("dq.scan.sample-rows") ?: 100_000L,
                    statementTimeoutSeconds = int("dq.scan.statement-timeout-seconds") ?: 1800,
                ),
                securitySecret = str("dq.security.secret") ?: "change-me-32bytes-secret-key-0000",
                // 公钥改为文件存放:dq.license.public-key-file 指向公钥文件(支持 ${user.home});未配时兼容内联 dq.license.public-key
                licensePublicKey = str("dq.license.public-key-file")
                    ?.let {
                        Files.readString(Path.of(it.replace("\${user.home}", System.getProperty("user.home")))).trim()
                    }
                    ?: str("dq.license.public-key") ?: "",
                // 签发私钥文件:配置即管理员实例(授权码管理);desktop 无管理 UI,仅为配置口径一致
                licensePrivateKey = str("dq.license.private-key-file")
                    ?.let {
                        Files.readString(Path.of(it.replace("\${user.home}", System.getProperty("user.home")))).trim()
                    }
                    ?: "",
                ai = AiDefaults(
                    apiKey = str("ai.api-key") ?: "",
                    baseUrl = str("ai.base-url") ?: "",
                    model = str("ai.model") ?: "",
                    peakValleyEnabled = str("ai.peak-valley-enabled")?.toBooleanStrictOrNull() ?: true,
                    peakInputPrice = str("ai.peak-input-price")?.toDoubleOrNull() ?: 9.0,
                    peakOutputPrice = str("ai.peak-output-price")?.toDoubleOrNull() ?: 27.0,
                    valleyInputPrice = str("ai.valley-input-price")?.toDoubleOrNull() ?: 4.5,
                    valleyOutputPrice = str("ai.valley-output-price")?.toDoubleOrNull() ?: 13.5,
                    workPeriods = str("ai.work-periods") ?: "09:00-12:00,14:00-18:00",
                    weekendValley = str("ai.weekend-valley")?.toBooleanStrictOrNull() ?: true,
                ),
                lan = LanConfig(
                    enabled = str("dq.lan.enabled")?.toBooleanStrictOrNull() ?: true,
                    discoveryPort = int("dq.lan.discovery-port") ?: 17386,
                    announceIntervalSeconds = int("dq.lan.announce-interval-seconds") ?: 5,
                ),
            )
        }
    }
}
