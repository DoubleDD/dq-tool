package com.example.dq.env

import com.example.dq.config.AppConfig
import com.example.dq.dialect.DialectFactory
import com.example.dq.repository.AiConfigRepository
import com.example.dq.repository.DataSourceRepository
import com.example.dq.repository.Jdbc
import com.example.dq.repository.LicenseRecordRepository
import com.example.dq.repository.LicenseRepository
import com.example.dq.repository.ScanRepository
import com.example.dq.repository.SchemaInit
import com.example.dq.repository.SchemaStatRepository
import com.example.dq.repository.TableDocRepository
import com.example.dq.repository.TagRepository
import com.example.dq.scan.ChunkRunner
import com.example.dq.scan.InterruptRecovery
import com.example.dq.scan.ScanExecutor
import com.example.dq.service.AiConfigService
import com.example.dq.service.AiService
import com.example.dq.service.AutoTagService
import com.example.dq.service.DataSourceService
import com.example.dq.service.DataSourceTransferService
import com.example.dq.service.ExportService
import com.example.dq.service.LicenseService
import com.example.dq.service.MetadataService
import com.example.dq.service.ScanService
import com.example.dq.service.SshTunnelService
import com.example.dq.service.TableDocService
import com.example.dq.service.TagService
import com.example.dq.util.CryptoUtil
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource

/**
 * 应用级服务容器:共享内核的手动依赖组装,server(Javalin)与 desktop(Compose)启动时各构建一次。
 */
class ServiceEnv(val config: AppConfig) {

    /** H2 本地库(数据源配置 + 扫描结果);server 的退出封装(AppShutdown)需要显式关闭它 */
    val dataSource: HikariDataSource = HikariDataSource(HikariConfig().apply {
        jdbcUrl = config.h2JdbcUrl
        username = "sa"
        password = ""
        maximumPoolSize = 4
    })

    private val jdbc = Jdbc(dataSource)

    // 仓储
    val dataSourceRepo = DataSourceRepository(jdbc)
    val scanRepo = ScanRepository(jdbc)
    val schemaStatRepo = SchemaStatRepository(jdbc)
    val tableDocRepo = TableDocRepository(jdbc)
    val tagRepo = TagRepository(jdbc)
    val aiConfigRepo = AiConfigRepository(jdbc)
    val licenseRepo = LicenseRepository(jdbc)
    val licenseRecordRepo = LicenseRecordRepository(jdbc)

    // 基础组件
    val crypto = CryptoUtil(config)
    val dialectFactory = DialectFactory
    val executor = ScanExecutor(config)

    // 服务(注意构造顺序:被依赖的在前)
    val sshTunnelService = SshTunnelService()
    val dataSourceService = DataSourceService(dataSourceRepo, crypto, dialectFactory, config, schemaStatRepo, sshTunnelService)
    val dataSourceTransferService = DataSourceTransferService(dataSourceRepo, crypto, dataSourceService)
    val tagService = TagService(tagRepo, dataSourceRepo)
    val aiService = AiService()
    val aiConfigService = AiConfigService(aiConfigRepo, crypto, config)
    val autoTagService = AutoTagService(aiConfigService, aiService, tagService, tagRepo, scanRepo,
        tableDocRepo, dataSourceService, dialectFactory)
    private val chunkRunner = ChunkRunner(scanRepo, dataSourceService, dialectFactory, config, executor,
        tagService, autoTagService)
    val scanService = ScanService(scanRepo, dataSourceRepo, schemaStatRepo, dataSourceService,
        dialectFactory, config, executor, chunkRunner)
    val metadataService = MetadataService(dataSourceService, dialectFactory, scanRepo, schemaStatRepo)
    val exportService = ExportService(scanService)
    val tableDocService = TableDocService(tableDocRepo, aiConfigService, aiService, dataSourceService, dialectFactory)
    val licenseService = LicenseService(licenseRepo, crypto, config.licensePublicKey,
        licenseRecordRepo, config.licensePrivateKey, config.appVersion)

    init {
        // 建表/老库升级 + 把上次异常退出的 RUNNING 任务标记为已中断
        SchemaInit.run(dataSource)
        InterruptRecovery(scanService).recover()
    }

    fun shutdown() {
        dataSource.close()
    }
}
