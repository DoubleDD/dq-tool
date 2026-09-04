package com.example.dq.env

import com.example.dq.config.AppConfig
import com.example.dq.dialect.DialectFactory
import com.example.dq.repository.AiConfigRepository
import com.example.dq.repository.AiUsageRepository
import com.example.dq.repository.DataSourceRepository
import com.example.dq.repository.Jdbc
import com.example.dq.repository.LicenseRecordRepository
import com.example.dq.repository.MetaCacheRepository
import com.example.dq.repository.LicenseRepository
import com.example.dq.repository.ManualCollectRepository
import com.example.dq.repository.ReportExportRepository
import com.example.dq.repository.SampleExportRepository
import com.example.dq.repository.ScanRepository
import com.example.dq.repository.SchemaDocRepository
import com.example.dq.repository.SchemaInit
import com.example.dq.repository.SchemaStatRepository
import com.example.dq.repository.SystemSettingsRepository
import com.example.dq.repository.TableDocRepository
import com.example.dq.repository.TagRepository
import com.example.dq.scan.ChunkRunner
import com.example.dq.scan.InterruptRecovery
import com.example.dq.scan.ScanAiTracker
import com.example.dq.scan.ScanExecutor
import com.example.dq.service.AiConfigService
import com.example.dq.service.AiService
import com.example.dq.service.AiUsageService
import com.example.dq.service.AnnotationTransferService
import com.example.dq.service.AutoTagService
import com.example.dq.service.DataSourceService
import com.example.dq.service.DataSourceTransferService
import com.example.dq.service.DbStructExportService
import com.example.dq.service.DiagnosticsService
import com.example.dq.service.ExportService
import com.example.dq.service.LicenseService
import com.example.dq.service.ListExportService
import com.example.dq.service.ManualCollectService
import com.example.dq.service.MetadataService
import com.example.dq.service.PreviewService
import com.example.dq.service.SampleExportService
import com.example.dq.service.ScanDocService
import com.example.dq.service.SqlConsoleService
import com.example.dq.service.ScanService
import com.example.dq.service.ScanTransferService
import com.example.dq.service.ScanWordExportService
import com.example.dq.service.SshTunnelService
import com.example.dq.service.SystemSettingsService
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

    /** AI 用量统计独立 H2 库(调用流水含请求/响应内容,数据量大,与主库分离) */
    val aiUsageDataSource: HikariDataSource = HikariDataSource(HikariConfig().apply {
        jdbcUrl = config.h2AiUsageJdbcUrl
        username = "sa"
        password = ""
        maximumPoolSize = 2
    })

    private val jdbc = Jdbc(dataSource)
    private val aiUsageJdbc = Jdbc(aiUsageDataSource)

    // 仓储
    val dataSourceRepo = DataSourceRepository(jdbc)
    val scanRepo = ScanRepository(jdbc)
    val schemaStatRepo = SchemaStatRepository(jdbc)
    val metaCacheRepo = MetaCacheRepository(jdbc)
    val schemaDocRepo = SchemaDocRepository(jdbc)
    val tableDocRepo = TableDocRepository(jdbc)
    val tagRepo = TagRepository(jdbc)
    val aiConfigRepo = AiConfigRepository(jdbc)
    val aiUsageRepo = AiUsageRepository(aiUsageJdbc)
    val systemSettingsRepo = SystemSettingsRepository(jdbc)
    val licenseRepo = LicenseRepository(jdbc)
    val licenseRecordRepo = LicenseRecordRepository(jdbc)
    val reportExportRepo = ReportExportRepository(jdbc)
    val sampleExportRepo = SampleExportRepository(jdbc)
    val manualCollectRepo = ManualCollectRepository(jdbc)

    // 基础组件
    val crypto = CryptoUtil(config)
    val dialectFactory = DialectFactory
    val executor = ScanExecutor(config)

    // 服务(注意构造顺序:被依赖的在前)
    val sshTunnelService = SshTunnelService()
    val dataSourceService = DataSourceService(dataSourceRepo, crypto, dialectFactory, config, schemaStatRepo, metaCacheRepo, sshTunnelService)
    val dataSourceTransferService = DataSourceTransferService(dataSourceRepo, crypto, dataSourceService)
    val tagService = TagService(tagRepo, dataSourceRepo)
    val manualCollectService = ManualCollectService(manualCollectRepo, dataSourceRepo)
    /** 扫描标签解析:记录用量时快照数据源名/库/schema(主库查询,任务被删返回 null 兜底) */
    private val scanLabelResolver: (Long) -> AiUsageRepository.ScanJobLabel? = { jobId ->
        scanRepo.findJob(jobId)?.let { job ->
            val dsName = dataSourceRepo.findById(job.datasourceId)?.name
            AiUsageRepository.ScanJobLabel(
                listOfNotNull(dsName, job.dbName, job.schemaName).joinToString(" "), job.createdAt)
        }
    }
    val aiUsageService = AiUsageService(aiUsageRepo, aiConfigRepo, config, scanLabelResolver)
    val aiService = AiService(aiUsageService::record)
    val aiConfigService = AiConfigService(aiConfigRepo, crypto, config, aiService)
    val systemSettingsService = SystemSettingsService(systemSettingsRepo, config)
    val scanAiTracker = ScanAiTracker(scanRepo)
    val autoTagService = AutoTagService(aiConfigService, aiService, tagService, tagRepo, scanRepo,
        tableDocRepo, dataSourceService, dialectFactory, scanAiTracker)
    val tableDocService = TableDocService(tableDocRepo, aiConfigService, aiService, dataSourceService, dialectFactory)
    val scanDocService = ScanDocService(aiConfigService, scanRepo, tableDocRepo, tableDocService, scanAiTracker)
    private val chunkRunner = ChunkRunner(scanRepo, dataSourceService, dialectFactory, systemSettingsService, executor,
        tagService, autoTagService, scanDocService, scanAiTracker)
    val scanService = ScanService(scanRepo, dataSourceRepo, schemaStatRepo, metaCacheRepo, dataSourceService,
        dialectFactory, systemSettingsService, executor, chunkRunner, autoTagService, scanDocService, scanAiTracker)
    val metadataService = MetadataService(dataSourceService, dialectFactory, scanRepo, schemaStatRepo, schemaDocRepo, metaCacheRepo)
    val previewService = PreviewService(dataSourceService, dialectFactory, systemSettingsService)
    val sqlConsoleService = SqlConsoleService(dataSourceService, systemSettingsService)
    val annotationTransferService = AnnotationTransferService(tagRepo, tableDocRepo, dataSourceRepo)
    val scanTransferService = ScanTransferService(scanRepo, dataSourceRepo, tagRepo, tableDocRepo)
    val exportService = ExportService(scanService, tableDocRepo)
    val scanWordExportService = ScanWordExportService(scanService, tagRepo, schemaDocRepo)
    val dbStructExportService = DbStructExportService(metadataService, dataSourceService, dialectFactory, tagRepo)
    val listExportService = ListExportService()
    val wordReportService = WordReportService(dataSourceService, metadataService, scanRepo, schemaDocRepo,
        dialectFactory, tagRepo, tableDocRepo, aiConfigService, aiService)
    val wordReportExportService = WordReportExportService(wordReportService, reportExportRepo, dataSourceRepo, config)
    val sampleExportService = SampleExportService(sampleExportRepo, dataSourceRepo, dataSourceService,
        systemSettingsService, dialectFactory, config)
    val licenseService = LicenseService(licenseRepo, crypto, config.licensePublicKey,
        licenseRecordRepo, config.licensePrivateKey, config.appVersion)
    val diagnosticsService = DiagnosticsService(config, dataSourceService, dataSourceRepo, licenseService,
        aiConfigService, scanRepo, reportExportRepo)

    /**
     * 共享内核持久化初始化:建表/老库升级(Flyway,已最新时走快速路径跳过)+ 把上次异常退出的
     * RUNNING 任务标记为已中断 + 恢复未完成的 Word 报告导出任务。
     * 从构造函数移出(原 init 块):server 绑定端口、打开窗口后再调用,避免首页等待初始化完成。
     */
    fun initDatabase() {
        SchemaInit.run(dataSource)
        SchemaInit.run(aiUsageDataSource, "db/migration-aiusage")
        // 老版本 AI 用量流水在主库,一次性搬迁到独立库(新库非空即跳过)
        aiUsageRepo.migrateLegacyIfEmpty(jdbc, scanLabelResolver)
        InterruptRecovery(scanService).recover()
        wordReportExportService.recoverUnfinished()
        sampleExportService.recoverUnfinished()
    }

    fun shutdown() {
        aiUsageDataSource.close()
        dataSource.close()
    }
}
