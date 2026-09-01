package com.example.dq.service

import com.example.dq.config.AppConfig
import com.example.dq.model.DatasourceCheckResult
import com.example.dq.model.DiagnosticsReport
import com.example.dq.model.EnvInfo
import com.example.dq.model.ExportFailureItem
import com.example.dq.model.LogErrorItem
import com.example.dq.model.ScanFailureItem
import com.example.dq.model.ScanStatus
import com.example.dq.model.TableError
import com.example.dq.model.TestConnectionRequest
import com.example.dq.repository.DataSourceRepository
import com.example.dq.repository.ReportExportRepository
import com.example.dq.repository.ScanRepository
import java.lang.management.ManagementFactory
import java.nio.file.Files
import java.time.LocalDateTime
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicInteger
import kotlin.io.path.extension
import kotlin.io.path.fileSize
import kotlin.streams.asSequence

/**
 * 系统诊断(排错中心)聚合服务:一页看全运行环境、授权、AI 配置、最近失败记录与错误日志,
 * 并可对所有已存数据源做连通性实测,供用户自助排错或导出报告发给运维。
 *
 * 口径说明:
 * - 数据源实测复用 [DataSourceService.testConnection](可经一次性 SSH 隧道),单条 30s 超时兜底;
 * - AI 只报配置完整性,不实测连通(实测会记一次用量,实测入口在设置页);
 * - 秘密字段不出报告:数据源清单走 [DataSourceService.list](密码置 null),实测在内存中单独取解密配置。
 */
class DiagnosticsService(
    private val config: AppConfig,
    private val dataSourceService: DataSourceService,
    private val dataSourceRepo: DataSourceRepository,
    private val licenseService: LicenseService,
    private val aiConfigService: AiConfigService,
    private val scanRepo: ScanRepository,
    private val reportExportRepo: ReportExportRepository,
) {

    /** 诊断概览(快路径,全本地聚合);recentLogErrors 由 server 侧内存日志缓冲映射传入 */
    fun overview(recentLogErrors: List<LogErrorItem>): DiagnosticsReport {
        val start = System.nanoTime()
        return DiagnosticsReport(
            env = envInfo(),
            license = runCatching { licenseService.status() }.getOrNull(),
            aiConfigured = aiConfigService.findConfig() != null,
            scanFailures = scanFailures(),
            exportFailures = exportFailures(),
            recentLogErrors = recentLogErrors,
            generatedAt = LocalDateTime.now(),
            durationMs = (System.nanoTime() - start) / 1_000_000,
        )
    }

    /** 全部已存数据源连通性实测(慢路径,用户手动触发);单数据源失败/超时不影响其他 */
    fun checkDatasources(): List<DatasourceCheckResult> {
        val list = dataSourceService.list().mapNotNull { it.id }
        if (list.isEmpty()) return emptyList()
        val idx = AtomicInteger()
        val pool = Executors.newFixedThreadPool(minOf(4, list.size)) { r ->
            Thread(r, "dq-diag-check-" + idx.incrementAndGet()).apply { isDaemon = true }
        }
        try {
            val futures = list.associateWith { id ->
                pool.submit(Callable { checkOne(id) })
            }
            return list.map { id ->
                try {
                    futures.getValue(id).get(40, TimeUnit.SECONDS)
                } catch (e: TimeoutException) {
                    DatasourceCheckResult(id, null, null, null, false, false, null, "检测超时", 40_000)
                } catch (e: Exception) {
                    DatasourceCheckResult(id, null, null, null, false, false, null, e.message, 0)
                }
            }
        } finally {
            pool.shutdownNow()
        }
    }

    /** 实测单个数据源:get(id) 取解密配置映射为测试请求(字段 1:1 对应,含 SSH 秘密,仅内存使用) */
    private fun checkOne(id: Long): DatasourceCheckResult {
        val ds = runCatching { dataSourceService.get(id) }.getOrNull()
            ?: return DatasourceCheckResult(id, null, null, null, false, false, null, "数据源不存在: $id", 0)
        val start = System.nanoTime()
        return try {
            val mode = dataSourceService.testConnection(TestConnectionRequest(
                jdbcUrl = ds.jdbcUrl, username = ds.username, password = ds.password,
                sshEnabled = ds.sshEnabled, sshHost = ds.sshHost, sshPort = ds.sshPort,
                sshUsername = ds.sshUsername, sshAuthMethod = ds.sshAuthMethod,
                sshPassword = ds.sshPassword, sshPrivateKey = ds.sshPrivateKey, sshPassphrase = ds.sshPassphrase,
            ))
            DatasourceCheckResult(id, ds.name, ds.dbType?.name, ds.jdbcUrl, ds.sshEnabled == true,
                true, mode, null, (System.nanoTime() - start) / 1_000_000)
        } catch (e: Exception) {
            DatasourceCheckResult(id, ds.name, ds.dbType?.name, ds.jdbcUrl, ds.sshEnabled == true,
                false, null, e.message, (System.nanoTime() - start) / 1_000_000)
        }
    }

    private fun envInfo(): EnvInfo {
        val rt = Runtime.getRuntime()
        val dataDir = config.dataDir.toAbsolutePath()
        val dirFile = dataDir.toFile()
        val h2Size = runCatching {
            Files.list(dataDir).use { s ->
                s.asSequence().filter { it.extension == "mv.db" }.sumOf { it.fileSize() }
            }
        }.getOrDefault(0L)
        return EnvInfo(
            appVersion = config.appVersion.ifBlank { "dev" },
            javaVersion = System.getProperty("java.version") + " (" + System.getProperty("java.vendor") + ")",
            os = System.getProperty("os.name") + " " + System.getProperty("os.version") + " " + System.getProperty("os.arch"),
            processors = rt.availableProcessors(),
            jvmHeapMaxBytes = rt.maxMemory(),
            jvmHeapUsedBytes = rt.totalMemory() - rt.freeMemory(),
            uptimeMs = ManagementFactory.getRuntimeMXBean().uptime,
            dataDir = dataDir.toString(),
            diskFreeBytes = dirFile.usableSpace,
            h2SizeBytes = h2Size,
        )
    }

    /** 最近 10 个失败扫描任务,各带前 20 张失败表(表名+失败原因) */
    private fun scanFailures(): List<ScanFailureItem> =
        scanRepo.listRecentFailedJobs(10).map { job ->
            val failedTables = scanRepo.listScanTables(job.id)
                .filter { it.status == ScanStatus.FAILED }
                .take(20)
                .map { TableError(it.tableName, it.error) }
            ScanFailureItem(
                jobId = job.id,
                datasourceId = job.datasourceId,
                datasourceName = runCatching { dataSourceRepo.findById(job.datasourceId)?.name }.getOrNull(),
                dbName = job.dbName,
                schemaName = job.schemaName,
                error = job.error,
                finishedAt = job.finishedAt,
                failedTables = failedTables,
            )
        }

    /** 最近 10 个失败的 Word 数据调研报告导出任务(list 已按 id DESC 限 200,内存过滤即可) */
    private fun exportFailures(): List<ExportFailureItem> =
        reportExportRepo.list(null)
            .filter { it.status == "FAILED" }
            .take(10)
            .map {
                ExportFailureItem(it.id, it.datasourceId,
                    runCatching { dataSourceRepo.findById(it.datasourceId)?.name }.getOrNull(),
                    it.dbName, it.schemaNames, it.error, it.finishedAt)
            }
}
