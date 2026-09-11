package com.example.dq.service

import com.example.dq.dialect.DialectFactory
import com.example.dq.model.MetaSyncDetail
import com.example.dq.model.MetaSyncItem
import com.example.dq.repository.MetaSyncRepository
import com.example.dq.util.ConnectionFailureClassifier
import org.slf4j.LoggerFactory
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * 元数据批量同步(数据源页「刷新」):对选中的数据源把 库清单 → schema 清单 → 表清单/字段总数/
 * 整库 lite 字段 → 逐表详细字段+索引 全部回源并覆盖本地 H2 缓存(复用 MetadataService 公共 refresh
 * 路径,缓存存全量不按白名单裁剪,与懒加载口径一致);每库结束刷库概览(schema_stat)。
 * DDL 不同步(Oracle/达梦逐表取 DDL 太慢,保留按需浏览+缓存降级现状)。
 *
 * 任务范式同 SampleExportService:任务落 H2(meta_sync_job/meta_sync_item)+ 4 线程守护池 +
 * 前端轮询;单个数据源失败不中断整批。成败判定先实测连接(getConnection)——不能直接以
 * MetadataService 的 refresh 调用判成败:有缓存时连接失败会降级返回缓存而不抛错。
 * 同步中途断连(refresh 降级读了缓存)也算失败,避免把陈旧缓存当同步成果
 */
class MetaSyncService(
    private val repo: MetaSyncRepository,
    private val metadataService: MetadataService,
    private val dataSourceService: DataSourceService,
    private val dialectFactory: DialectFactory,
) {

    private val log = LoggerFactory.getLogger(javaClass)

    private val executor = Executors.newFixedThreadPool(4) { r ->
        Thread(r, "meta-sync-" + THREAD_IDX.incrementAndGet()).apply { isDaemon = true }
    }

    /** 工作线程在检查点发现任务已取消时抛出;run() 捕获后把当前明细置 CANCELED */
    private class SyncCanceledException : RuntimeException()

    /**
     * 提交同步任务:空列表抛 IllegalArgumentException(400);已有未结束任务抛 IllegalStateException(409);
     * 落 job+items 后异步执行,立即返回 jobId
     */
    fun submit(datasourceIds: List<Long>): Long {
        val ids = datasourceIds.distinct()
        if (ids.isEmpty()) {
            throw IllegalArgumentException("请选择要同步的数据源")
        }
        if (repo.hasRunning()) {
            throw IllegalStateException("已有元数据同步任务正在运行,请等待其完成或先取消")
        }
        // 校验数据源存在并快照名称(删除后明细仍可展示)
        val items = ids.map { id ->
            val ds = try {
                dataSourceService.get(id)
            } catch (e: IllegalArgumentException) {
                throw IllegalArgumentException("数据源不存在: $id")
            }
            id to (ds.name ?: "数据源$id")
        }
        val jobId = repo.insertJob(ids.size)
        repo.insertItems(jobId, items)
        executor.execute { run(jobId) }
        log.info("元数据批量同步任务已提交: id={}, 数据源数={}", jobId, ids.size)
        return jobId
    }

    /** 取消任务:落库标志,工作线程在逐表/逐数据源检查点响应 */
    fun cancel(jobId: Long) {
        repo.findJob(jobId) ?: throw IllegalArgumentException("同步任务不存在: $jobId")
        if (repo.markCanceled(jobId) == 0) {
            throw IllegalStateException("任务已结束,无法取消")
        }
    }

    /** 最近一次任务(含明细;页面打开时恢复轮询用),从未同步过返回 null */
    fun latest(): MetaSyncDetail? {
        val job = repo.findLatest() ?: return null
        return MetaSyncDetail(job, repo.listItems(job.id))
    }

    /** 任务详情(进度轮询) */
    fun detail(jobId: Long): MetaSyncDetail {
        val job = repo.findJob(jobId) ?: throw IllegalArgumentException("同步任务不存在: $jobId")
        return MetaSyncDetail(job, repo.listItems(jobId))
    }

    /** 服务重启恢复:PENDING/RUNNING 任务与明细置 FAILED(ServiceEnv 装配时调用一次) */
    fun recoverUnfinished() {
        val n = repo.failUnfinished()
        repo.failUnfinishedItems()
        if (n > 0) {
            log.warn("服务重启,{} 个未完成的元数据同步任务已置为失败", n)
        }
    }

    // ---------- 后台执行 ----------

    private fun run(jobId: Long) {
        repo.markRunning(jobId)
        for (item in repo.listItems(jobId)) {
            if (repo.isCanceled(jobId)) {
                repo.cancelItem(item.id)
                continue
            }
            repo.markItemRunning(item.id)
            try {
                val (dbCount, schemaCount, tableCount) = syncDatasource(jobId, item)
                repo.finishItem(item.id, dbCount, schemaCount, tableCount)
                repo.incrDone(jobId)
                log.info("元数据同步完成: jobId={}, 数据源={}({}), schema={}, 表={}",
                    jobId, item.datasourceName, item.datasourceId, schemaCount, tableCount)
            } catch (e: SyncCanceledException) {
                repo.cancelItem(item.id)
                log.info("元数据同步已取消: jobId={}, 数据源={}", jobId, item.datasourceName)
            } catch (e: Exception) {
                val msg = (e.message ?: "同步失败").take(2000)
                // 单数据源失败是任务内的真实错误(结构写库失败/同步中断等),按 error 记录并带堆栈便于定位
                log.error("元数据同步失败: jobId={}, 数据源={}({}): {}", jobId, item.datasourceName, item.datasourceId, msg, e)
                repo.failItem(item.id, msg)
                repo.incrFailed(jobId)
            }
        }
        // 取消的任务状态已由 cancel() 落库,不覆盖;否则按明细结果收终态
        val job = repo.findJob(jobId) ?: return
        if (job.status == "CANCELED") return
        if (job.failedDs > 0) {
            repo.finishJob(jobId, "FAILED", "${job.failedDs} 个数据源同步失败")
        } else {
            repo.finishJob(jobId, "DONE", null)
        }
    }

    /** 同步单个数据源的元数据缓存,返回 (库数, schema 数, 表数) 统计 */
    private fun syncDatasource(jobId: Long, item: MetaSyncItem): Triple<Int, Int, Int> {
        val dsId = item.datasourceId
        // 先实测连接判成败(关键:浏览接口有缓存时会降级返回而不抛错,不能据此判定)
        try {
            dataSourceService.getConnection(dsId).use { }
        } catch (e: Exception) {
            if (ConnectionFailureClassifier.isConnectionFailure(e)) {
                // 借分类器写数据源连接状态标记(网络不可达/认证失败),供数据源页红标提示
                runCatching {
                    dataSourceService.markConnFailure(dsId,
                        ConnectionFailureClassifier.describe(e), ConnectionFailureClassifier.classify(e))
                }
                throw IllegalStateException("数据源连接失败: " + ConnectionFailureClassifier.describe(e), e)
            }
            throw e
        }
        val ds = dataSourceService.get(dsId)
        val dialect = dialectFactory.get(ds.dbType!!)

        // 单库方言 schema 即用户眼中的库,无库清单一层
        val databases: List<String?> =
            if (dialect.supportsMultiDatabase()) {
                synced(jobId) { metadataService.listDatabases(dsId, unfiltered = true, refresh = true) }
            } else {
                listOf(null)
            }
        var schemaCount = 0
        var tableCount = 0
        databases.forEachIndexed { dbIdx, db ->
            checkCanceled(jobId)
            val dbLabel = db ?: ""
            if (dialect.supportsMultiDatabase()) {
                repo.updateItemProgress(item.id, "正在同步库 $dbLabel(${dbIdx + 1}/${databases.size})")
            }
            val schemas = synced(jobId) { metadataService.listSchemas(dsId, db, unfiltered = true, refresh = true) }
            schemaCount += schemas.size
            schemas.forEachIndexed { schemaIdx, schema ->
                checkCanceled(jobId)
                repo.updateItemProgress(item.id, "正在同步 schema $schema(${schemaIdx + 1}/${schemas.size})")
                val tables = synced(jobId) { metadataService.listTables(dsId, db, schema, refresh = true) }
                synced(jobId) { metadataService.listSchemaColumns(dsId, db, schema, refresh = true) }
                tableCount += tables.size
                tables.forEachIndexed { tableIdx, table ->
                    checkCanceled(jobId)
                    val tableName = table.name ?: ""
                    repo.updateItemProgress(item.id, "正在同步表 $tableName(${tableIdx + 1}/${tables.size})")
                    synced(jobId) { metadataService.listTableColumns(dsId, db, schema, tableName, refresh = true) }
                    synced(jobId) { metadataService.listTableIndexes(dsId, db, schema, tableName, refresh = true) }
                }
                // 字段总数最后刷:表清单/单表字段/整库字段清单的覆盖刷新都会按粒度失效该缓存
                synced(jobId) { metadataService.countColumns(dsId, db, schema, refresh = true) }
            }
            // 每库结束刷库概览(表数量/体积/最近扫描)
            synced(jobId) { metadataService.listSchemaStats(dsId, db, refresh = true) }
        }
        val dbCount = if (dialect.supportsMultiDatabase()) databases.size else 0
        return Triple(dbCount, schemaCount, tableCount)
    }

    /** 取消检查点:任务已取消则抛出,中断当前数据源的同步 */
    private fun checkCanceled(jobId: Long) {
        if (repo.isCanceled(jobId)) throw SyncCanceledException()
    }

    /**
     * 执行一次 refresh 调用并核验结果真的来自回源:连接中途断开且有缓存时浏览路径会静默降级
     * 返回缓存(不抛错),同步场景必须把这种情况判为失败,避免陈旧缓存被当成同步成果
     */
    private fun <T> synced(jobId: Long, fetch: () -> T): T {
        checkCanceled(jobId)
        val result = fetch()
        if (metadataService.consumeCacheFallback()) {
            throw IllegalStateException("数据源连接失败(同步中途断开),本地保留原有缓存")
        }
        return result
    }

    companion object {
        /** 任务执行线程池的线程序号(线程命名 meta-sync-N) */
        private val THREAD_IDX = AtomicInteger()
    }
}
