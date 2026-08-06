package com.example.dq.ui

import com.example.dq.config.AppConfig
import com.example.dq.dialect.DialectFactory
import com.example.dq.repository.AiConfigRepository
import com.example.dq.repository.DataSourceRepository
import com.example.dq.repository.Jdbc
import com.example.dq.repository.ScanRepository
import com.example.dq.repository.SchemaInit
import com.example.dq.repository.SchemaStatRepository
import com.example.dq.repository.TableDocRepository
import com.example.dq.scan.ChunkRunner
import com.example.dq.scan.InterruptRecovery
import com.example.dq.scan.ScanExecutor
import com.example.dq.service.AiConfigService
import com.example.dq.service.AiService
import com.example.dq.service.DataSourceService
import com.example.dq.service.ExportService
import com.example.dq.service.MetadataService
import com.example.dq.service.ScanService
import com.example.dq.service.TableDocService
import com.example.dq.util.CryptoUtil
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource

/**
 * 应用级服务容器:替代 Spring 的手动依赖组装,在 main 启动时构建一次。
 */
class AppEnv(val config: AppConfig) {

    /** H2 本地库(数据源配置 + 扫描结果) */
    private val h2: HikariDataSource = HikariDataSource(HikariConfig().apply {
        jdbcUrl = config.h2JdbcUrl
        username = "sa"
        password = ""
        maximumPoolSize = 4
    })

    private val jdbc = Jdbc(h2)

    // 仓储
    val dataSourceRepo = DataSourceRepository(jdbc)
    val scanRepo = ScanRepository(jdbc)
    val schemaStatRepo = SchemaStatRepository(jdbc)
    val tableDocRepo = TableDocRepository(jdbc)
    val aiConfigRepo = AiConfigRepository(jdbc)

    // 基础组件
    val crypto = CryptoUtil(config)
    val dialectFactory = DialectFactory
    val executor = ScanExecutor(config)

    // 服务(注意构造顺序:被依赖的在前)
    val dataSourceService = DataSourceService(dataSourceRepo, crypto, dialectFactory, config, schemaStatRepo)
    private val chunkRunner = ChunkRunner(scanRepo, dataSourceService, dialectFactory, config, executor)
    val scanService = ScanService(scanRepo, dataSourceRepo, schemaStatRepo, dataSourceService,
        dialectFactory, config, executor, chunkRunner)
    val metadataService = MetadataService(dataSourceService, dialectFactory, scanRepo, schemaStatRepo)
    val exportService = ExportService(scanService)
    val aiService = AiService()
    val aiConfigService = AiConfigService(aiConfigRepo, crypto, config)
    val tableDocService = TableDocService(tableDocRepo, aiConfigService, aiService, dataSourceService, dialectFactory)

    init {
        // 建表/老库升级 + 把上次异常退出的 RUNNING 任务标记为已中断
        SchemaInit.run(h2)
        InterruptRecovery(scanService).recover()
    }

    fun shutdown() {
        h2.close()
    }
}
