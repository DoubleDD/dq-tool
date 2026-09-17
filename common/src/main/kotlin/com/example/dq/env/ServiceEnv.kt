package com.example.dq.env

import com.example.dq.config.AppConfig
import com.example.dq.dialect.DialectFactory
import com.example.dq.discovery.LanDiscoveryService
import com.example.dq.model.AiScene
import com.example.dq.repository.AiConfigRepository
import com.example.dq.repository.AiUsageRepository
import com.example.dq.repository.CompareImportRepository
import com.example.dq.repository.CompareRepository
import com.example.dq.repository.DataSourceRepository
import com.example.dq.repository.Jdbc
import com.example.dq.repository.LicenseRecordRepository
import com.example.dq.repository.MetaCacheRepository
import com.example.dq.repository.MetaSyncRepository
import com.example.dq.repository.MetaWriteQueue
import com.example.dq.repository.LicenseRepository
import com.example.dq.repository.ManualCollectRepository
import com.example.dq.repository.ObjectCatalogRepository
import com.example.dq.repository.RelationInferJobRepository
import com.example.dq.repository.ReportExportRepository
import com.example.dq.repository.SampleExportRepository
import com.example.dq.repository.ScanRepository
import com.example.dq.repository.SchemaDocRepository
import com.example.dq.repository.SchemaInit
import com.example.dq.repository.SchemaStatRepository
import com.example.dq.repository.SystemSettingsRepository
import com.example.dq.repository.TableDocRepository
import com.example.dq.repository.TableRelationRepository
import com.example.dq.repository.TableSystemRepository
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
import com.example.dq.service.ChangelogService
import com.example.dq.service.CompareImportService
import com.example.dq.service.CompareService
import com.example.dq.service.DataSourceService
import com.example.dq.service.DataSourceTransferService
import com.example.dq.service.DbStructExportService
import com.example.dq.service.DiagnosticsService
import com.example.dq.service.ErrorCenterService
import com.example.dq.service.ExportService
import com.example.dq.service.LicenseService
import com.example.dq.service.ListExportService
import com.example.dq.service.LanShareService
import com.example.dq.service.LocalH2ConsoleService
import com.example.dq.service.ManualCollectService
import com.example.dq.service.MetadataTransferService
import com.example.dq.service.ObjectCatalogService
import com.example.dq.service.MetadataService
import com.example.dq.service.MetaSyncService
import com.example.dq.service.PreviewService
import com.example.dq.service.RelationInferService
import com.example.dq.service.SampleExportService
import com.example.dq.service.ScanDocService
import com.example.dq.service.SqlConsoleService
import com.example.dq.service.ScanService
import com.example.dq.service.ScanTransferService
import com.example.dq.service.ScanWordExportService
import com.example.dq.service.SshTunnelService
import com.example.dq.service.SystemSettingsService
import com.example.dq.service.TableDocService
import com.example.dq.service.TableRelationService
import com.example.dq.service.TableSystemService
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

    /**
     * 元数据缓存统一写队列(meta_* / schema_stat):全局单线程串行,
     * 对 H2 元数据表禁止并发写(扫描多 worker 刷不同粒度时不再互相等锁/踩唯一键)。
     * 手工导入(MetadataTransferService)结构缓存也共用它。
     */
    private val metaWriteQueue = MetaWriteQueue()

    // 仓储
    val dataSourceRepo = DataSourceRepository(jdbc)
    val scanRepo = ScanRepository(jdbc)
    val schemaStatRepo = SchemaStatRepository(jdbc, metaWriteQueue)
    val metaCacheRepo = MetaCacheRepository(jdbc, metaWriteQueue)
    val schemaDocRepo = SchemaDocRepository(jdbc)
    val tableDocRepo = TableDocRepository(jdbc)
    val tableSystemRepo = TableSystemRepository(jdbc)
    val tagRepo = TagRepository(jdbc)
    val aiConfigRepo = AiConfigRepository(jdbc)
    val aiUsageRepo = AiUsageRepository(aiUsageJdbc)
    val systemSettingsRepo = SystemSettingsRepository(jdbc)
    val licenseRepo = LicenseRepository(jdbc)
    val licenseRecordRepo = LicenseRecordRepository(jdbc)
    val reportExportRepo = ReportExportRepository(jdbc)
    val sampleExportRepo = SampleExportRepository(jdbc)
    val manualCollectRepo = ManualCollectRepository(jdbc)
    val objectCatalogRepo = ObjectCatalogRepository(jdbc)
    val tableRelationRepo = TableRelationRepository(jdbc)
    val relationInferJobRepo = RelationInferJobRepository(jdbc)
    val metaSyncRepo = MetaSyncRepository(jdbc)
    val compareRepo = CompareRepository(jdbc)
    val compareImportRepo = CompareImportRepository(jdbc)

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
    val objectCatalogService = ObjectCatalogService(objectCatalogRepo, dataSourceRepo, metaCacheRepo)
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
    /** 元数据浏览/缓存编排:缓存优先 + 回源覆盖 + 不可达降级;结构写缓存统一走 metaWriteQueue */
    val metadataService = MetadataService(dataSourceService, dialectFactory, scanRepo, schemaStatRepo, schemaDocRepo, metaCacheRepo)
    /** AI 表说明:表/字段结构复用 metadataService 的缓存优先路径(不再直连业务库绕过缓存) */
    val tableDocService = TableDocService(tableDocRepo, aiConfigService, aiService, metadataService)
    val tableSystemService = TableSystemService(tableSystemRepo)
    val scanDocService = ScanDocService(aiConfigService, scanRepo, tableDocRepo, tableDocService, scanAiTracker)
    private val chunkRunner = ChunkRunner(scanRepo, dataSourceService, dialectFactory, systemSettingsService, executor,
        tagService, autoTagService, scanDocService, scanAiTracker)
    val scanService = ScanService(scanRepo, dataSourceRepo, schemaStatRepo, metaCacheRepo, dataSourceService,
        dialectFactory, systemSettingsService, executor, chunkRunner, autoTagService, scanDocService, scanAiTracker)
    val metaSyncService = MetaSyncService(metaSyncRepo, metadataService, dataSourceService, dialectFactory)
    val previewService = PreviewService(dataSourceService, dialectFactory, systemSettingsService)
    val sqlConsoleService = SqlConsoleService(dataSourceService, systemSettingsService, dialectFactory)
    /** 本地 H2 库(应用自身配置库)只读查询:SQL 控制台「本地 H2 库」入口,复用主库连接池 */
    val localH2ConsoleService = LocalH2ConsoleService(dataSource, systemSettingsService)
    val annotationTransferService = AnnotationTransferService(tagRepo, tableDocRepo, tableSystemRepo, dataSourceRepo)
    /** 元数据缓存导入导出:结构缓存整粒度替换 + 派生标注按自然键合并,供离线使用 ER/图谱/对象管理 */
    val metadataTransferService = MetadataTransferService(jdbc, dataSourceRepo, crypto, dataSourceService, metaSyncRepo,
        tagRepo, tableDocRepo, schemaDocRepo, tableSystemRepo, manualCollectRepo, tableRelationRepo, objectCatalogRepo,
        metaWriteQueue)
    val scanTransferService = ScanTransferService(scanRepo, dataSourceRepo, tagRepo, tableDocRepo)
    /** 局域网共享:UDP 发现(纯网络,不做持久化)+ 拉取导入编排;start(httpPort) 由壳层在内核就绪后调用 */
    val lanDiscoveryService = LanDiscoveryService(config.lan)
    val lanShareService = LanShareService(lanDiscoveryService, systemSettingsRepo,
        annotationTransferService, scanTransferService, config, dataSourceRepo, dataSourceService, crypto)
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
    val changelogService = ChangelogService(config)
    /** 统一错误收集(前端/后端/数据库/任务/启动):server 侧采集器与未捕获异常处理共用,页面「错误中心」读它 */
    val errorCenterService = ErrorCenterService(config, jdbc)
    val tableRelationService = TableRelationService(tableRelationRepo, metaCacheRepo)
    val relationInferService = RelationInferService(tableRelationRepo, relationInferJobRepo, metaCacheRepo,
        dataSourceService, systemSettingsService, dialectFactory, aiConfigService, tableDocRepo, aiService)
    val compareService = CompareService(compareRepo, dataSourceService, dialectFactory,
        metadataService, systemSettingsService, tableSystemRepo, aiConfigService,
        // 匹配逻辑 3 的补配调用计入 AI 用量统计(场景:比对匹配)
        aiChat = { c, s, u -> aiService.chat(c, s, u, AiScene.COMPARE_MATCH) },
        // 列级对比字段映射预生成(场景:比对映射)
        aiMappingChat = { c, s, u -> aiService.chat(c, s, u, AiScene.COMPARE_MAPPING) },
        // 导出「数据最新更新时间」的时间字段语义匹配(场景:比对时间)
        aiTimeChat = { c, s, u -> aiService.chat(c, s, u, AiScene.COMPARE_TIME) })
    /** 比对任务批量导入:一 sheet 一任务,数据源实测建档 + 大模型推导字段映射,任务落 PENDING 待人工审核 */
    val compareImportService = CompareImportService(compareImportRepo, dataSourceRepo, dataSourceService,
        metadataService, compareService, aiConfigService, config,
        aiMappingChat = { c, s, u -> aiService.chat(c, s, u, AiScene.COMPARE_MAPPING) })

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
        relationInferJobRepo.failRunningOnStartup()
        metaSyncService.recoverUnfinished()
        compareService.recoverUnfinished()
        compareImportService.recoverUnfinished()
        // 错误中心最后就绪:此前(建表/迁移/恢复期)产生的错误已落 logs/error-spool.jsonl,此处回灌入库并执行保留策略
        errorCenterService.markReady()
    }

    fun shutdown() {
        lanShareService.stop()
        // 先停在途元数据写入,再关 H2 连接池(避免写任务拿到已关闭的池)
        metaWriteQueue.shutdown()
        aiUsageDataSource.close()
        dataSource.close()
    }
}
