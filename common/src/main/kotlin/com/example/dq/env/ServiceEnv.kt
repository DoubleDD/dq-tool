package com.example.dq.env

import com.example.dq.config.AppConfig
import com.example.dq.dialect.DialectFactory
import com.example.dq.repository.AiConfigRepository
import com.example.dq.repository.DataSourceRepository
import com.example.dq.repository.Jdbc
import com.example.dq.repository.LicenseRecordRepository
import com.example.dq.repository.MetaCacheRepository
import com.example.dq.repository.LicenseRepository
import com.example.dq.repository.ReportExportRepository
import com.example.dq.repository.ScanRepository
import com.example.dq.repository.SchemaDocRepository
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
import com.example.dq.service.WordReportExportService
import com.example.dq.service.WordReportService
import com.example.dq.util.CryptoUtil
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource

/**
 * 应用级服务容器:共享内核的手动依赖组装,server(Javalin)与 desktop(Compose)启动时各构建一次。
 * 构造只完成 H2 连接池 + 全部 service 对象图(纯内存装配,毫秒级);
 * 建表/迁移/中断恢复等持久化重活由 [initDatabase] 显式完成——server 先绑定 HTTP 端口
 * 提供静态页面与就绪探针(/api/health),再调用本方法,期间业务接口由就绪闸门返回 503。
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
    val metaCacheRepo = MetaCacheRepository(jdbc)
    val schemaDocRepo = SchemaDocRepository(jdbc)
    val tableDocRepo = TableDocRepository(jdbc)
    val tagRepo = TagRepository(jdbc)
    val aiConfigRepo = AiConfigRepository(jdbc)
    val licenseRepo = LicenseRepository(jdbc)
    val licenseRecordRepo = LicenseRecordRepository(jdbc)
    val reportExportRepo = ReportExportRepository(jdbc)

    // 基础组件
    val crypto = CryptoUtil(config)
    val dialectFactory = DialectFactory
    val executor = ScanExecutor(config)

    // 服务(注意构造顺序:被依赖的在前)
    val sshTunnelService = SshTunnelService()
    val dataSourceService = DataSourceService(dataSourceRepo, crypto, dialectFactory, config, schemaStatRepo, metaCacheRepo, sshTunnelService)
    val dataSourceTransferService = DataSourceTransferService(dataSourceRepo, crypto, dataSourceService)
    val tagService = TagService(tagRepo, dataSourceRepo)
    val aiService = AiService()
    val aiConfigService = AiConfigService(aiConfigRepo, crypto, config)
    val autoTagService = AutoTagService(aiConfigService, aiService, tagService, tagRepo, scanRepo,
        tableDocRepo, dataSourceService, dialectFactory)
    private val chunkRunner = ChunkRunner(scanRepo, dataSourceService, dialectFactory, config, executor,
        tagService, autoTagService)
    val scanService = ScanService(scanRepo, dataSourceRepo, schemaStatRepo, metaCacheRepo, dataSourceService,
        dialectFactory, config, executor, chunkRunner)
    val metadataService = MetadataService(dataSourceService, dialectFactory, scanRepo, schemaStatRepo, schemaDocRepo, metaCacheRepo)
    val exportService = ExportService(scanService)
    val wordReportService = WordReportService(dataSourceService, metadataService, scanRepo, schemaDocRepo,
        dialectFactory, tagRepo, tableDocRepo, aiConfigService, aiService)
    val wordReportExportService = WordReportExportService(wordReportService, reportExportRepo, dataSourceRepo, config)
    val tableDocService = TableDocService(tableDocRepo, aiConfigService, aiService, dataSourceService, dialectFactory)
    val licenseService = LicenseService(licenseRepo, crypto, config.licensePublicKey,
        licenseRecordRepo, config.licensePrivateKey, config.appVersion)

    /**
     * 共享内核持久化初始化:建表/老库升级(Flyway,已最新时走快速路径跳过)+ 把上次异常退出的
     * RUNNING 任务标记为已中断 + 恢复未完成的 Word 报告导出任务。
     * 从构造函数移出(原 init 块):server 绑定端口、打开窗口后再调用,避免首页等待初始化完成。
     */
    fun initDatabase() {
        SchemaInit.run(dataSource)
        InterruptRecovery(scanService).recover()
        wordReportExportService.recoverUnfinished()
    }

    fun shutdown() {
        dataSource.close()
    }
}
