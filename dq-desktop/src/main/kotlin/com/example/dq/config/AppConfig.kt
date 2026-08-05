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
) {
    /** H2 文件库连接串,与原工程一致 */
    val h2JdbcUrl: String
        get() = "jdbc:h2:file:${dataDir.toAbsolutePath()}/dqconfig;AUTO_SERVER=TRUE"

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
                ai = AiDefaults(
                    apiKey = str("ai.api-key") ?: "",
                    baseUrl = str("ai.base-url") ?: "",
                    model = str("ai.model") ?: "",
                ),
            )
        }
    }
}
