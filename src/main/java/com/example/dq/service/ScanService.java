package com.example.dq.service;

import com.example.dq.config.DqProperties;
import com.example.dq.dialect.DbDialect;
import com.example.dq.dialect.DialectFactory;
import com.example.dq.model.ChunkRecord;
import com.example.dq.model.ColumnMeta;
import com.example.dq.model.DataSourceConfig;
import com.example.dq.model.NullRule;
import com.example.dq.model.Range;
import com.example.dq.model.ScanColumnView;
import com.example.dq.model.ScanJobEvent;
import com.example.dq.model.ScanJobView;
import com.example.dq.model.ScanRequest;
import com.example.dq.model.ScanStatus;
import com.example.dq.model.ScanTableView;
import com.example.dq.model.TableStat;
import com.example.dq.repository.DataSourceRepository;
import com.example.dq.repository.ScanRepository;
import com.example.dq.repository.SchemaStatRepository;
import com.example.dq.scan.ChunkRunner;
import com.example.dq.scan.ScanExecutor;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Connection;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/** 扫描任务生命周期:创建/规划/进度/取消/断点续扫 */
@Service
public class ScanService {

    private static final Logger log = LoggerFactory.getLogger(ScanService.class);

    private final ScanRepository repo;
    private final DataSourceRepository dsRepo;
    private final SchemaStatRepository schemaStatRepo;
    private final DataSourceService dataSourceService;
    private final DialectFactory dialectFactory;
    private final DqProperties props;
    private final ScanExecutor executor;
    private final ChunkRunner chunkRunner;
    private final ObjectMapper objectMapper;

    public ScanService(ScanRepository repo, DataSourceRepository dsRepo, SchemaStatRepository schemaStatRepo,
                       DataSourceService dataSourceService,
                       DialectFactory dialectFactory, DqProperties props, ScanExecutor executor,
                       ChunkRunner chunkRunner, ObjectMapper objectMapper) {
        this.repo = repo;
        this.dsRepo = dsRepo;
        this.schemaStatRepo = schemaStatRepo;
        this.dataSourceService = dataSourceService;
        this.dialectFactory = dialectFactory;
        this.props = props;
        this.executor = executor;
        this.chunkRunner = chunkRunner;
        this.objectMapper = objectMapper;
    }

    /** 发起扫描,立即返回 jobId */
    public long createScan(ScanRequest req) throws Exception {
        DataSourceConfig ds = dataSourceService.get(req.datasourceId());
        DbDialect dialect = dialectFactory.get(ds.getDbType());

        List<TableStat> all;
        try (Connection conn = dataSourceService.getConnection(req.datasourceId())) {
            dialect.useDatabase(conn, dataSourceService.resolveDatabase(req.datasourceId(), req.database()));
            all = dialect.listTables(conn, req.schema());
        }
        // 顺带刷新库列表缓存:all 是该 schema 的全量表清单,聚合计数与体积即可,零额外查询
        schemaStatRepo.upsert(req.datasourceId(), req.database(),
                new SchemaStatRepository.CachedStat(req.schema(), all.size(), sumSize(all)));
        List<TableStat> targets = all;
        if (req.tables() != null && !req.tables().isEmpty()) {
            Set<String> wanted = new HashSet<>(req.tables());
            targets = all.stream().filter(t -> wanted.contains(t.name())).toList();
            if (targets.isEmpty()) {
                throw new IllegalArgumentException("选中的表在库中不存在");
            }
        }
        // 表大小上限:超过上限的表直接不纳入任务;大小未知的表(null)不参与过滤
        if (req.maxTableSizeBytes() != null) {
            targets = targets.stream()
                    .filter(t -> t.sizeBytes() == null || t.sizeBytes() <= req.maxTableSizeBytes())
                    .toList();
            if (targets.isEmpty()) {
                throw new IllegalArgumentException("没有不超过大小上限的表可扫描");
            }
        }
        if (targets.isEmpty()) {
            throw new IllegalArgumentException("该库下没有表");
        }

        String rulesJson = objectMapper.writeValueAsString(
                req.nullRules() == null ? List.of() : req.nullRules());
        long jobId = repo.insertJob(req.datasourceId(), req.database(), req.schema(), req.forceFull(), rulesJson, targets.size());
        List<Long> scanTableIds = new ArrayList<>();
        for (TableStat t : targets) {
            scanTableIds.add(repo.insertScanTable(jobId, t.name(), t.estRows(), t.sizeBytes(),
                    t.comment(), t.storageInfo()));
        }
        repo.markJobRunning(jobId);
        scanTableIds.forEach(id -> executor.submit(() -> planTable(id)));
        return jobId;
    }

    /** 表级规划:取字段、选分段键、判定采样、计算分段、入队 */
    void planTable(long scanTableId) {
        ScanTableView table = repo.findScanTable(scanTableId).orElse(null);
        if (table == null) {
            return;
        }
        ScanRepository.JobRow job = repo.findJob(table.jobId()).orElse(null);
        if (job == null || job.status() != ScanStatus.RUNNING) {
            return;
        }
        try {
            DataSourceConfig ds = dataSourceService.get(job.datasourceId());
            DbDialect dialect = dialectFactory.get(ds.getDbType());
            List<ColumnMeta> cols;
            List<Range> ranges;
            try (Connection conn = dataSourceService.getConnection(job.datasourceId())) {
                dialect.useDatabase(conn, dataSourceService.resolveDatabase(job.datasourceId(), job.dbName()));
                cols = dialect.listColumns(conn, job.schemaName(), table.tableName());
                if (cols.isEmpty()) {
                    chunkRunner.failTable(scanTableId, "表不存在或没有字段");
                    return;
                }
                ColumnMeta chunkKey = dialect.pickChunkKey(cols);
                boolean sampled = !job.forceFull() && overThreshold(ds, table);
                ranges = sampled
                        ? List.of(Range.whole())
                        : dialect.planChunks(conn, job.schemaName(), table.tableName(),
                                chunkKey, table.estRows() != null ? table.estRows() : 0,
                                props.getScan().getChunksPerTable());
                repo.markTablePlanned(scanTableId, chunkKey != null ? chunkKey.name() : null,
                        sampled, sampled ? props.getScan().getSampleRows() : null, ranges.size());
            }
            List<Long> chunkIds = new ArrayList<>();
            for (int i = 0; i < ranges.size(); i++) {
                Range r = ranges.get(i);
                chunkIds.add(repo.insertChunk(scanTableId, i, r.start(), r.end(), r.nullChunk()));
            }
            chunkIds.forEach(id -> executor.submit(() -> chunkRunner.run(id)));
        } catch (Exception e) {
            log.warn("表规划失败 scanTableId={}: {}", scanTableId, e.getMessage());
            chunkRunner.failTable(scanTableId, "规划失败: " + e.getMessage());
        }
    }

    private boolean overThreshold(DataSourceConfig ds, ScanTableView table) {
        long rowThreshold = ds.getRowThreshold() != null ? ds.getRowThreshold() : props.getScan().getRowThreshold();
        long sizeThreshold = ds.getSizeThresholdBytes() != null ? ds.getSizeThresholdBytes()
                : props.getScan().getSizeThresholdBytes();
        if (table.estRows() != null && table.estRows() > rowThreshold) {
            return true;
        }
        return table.sizeBytes() != null && table.sizeBytes() > sizeThreshold;
    }

    /** 表清单的数据+索引总字节;全部为 null(方言不支持)时返回 null */
    private static Long sumSize(List<TableStat> tables) {
        boolean any = tables.stream().anyMatch(t -> t.sizeBytes() != null);
        if (!any) {
            return null;
        }
        return tables.stream().mapToLong(t -> t.sizeBytes() == null ? 0 : t.sizeBytes()).sum();
    }

    // ---------- 查询 ----------

    public List<ScanJobView> listJobs(Long datasourceId, String dbName, String schemaName) {
        Map<Long, String> dsNames = dsRepo.findAll().stream()
                .collect(Collectors.toMap(DataSourceConfig::getId, DataSourceConfig::getName));
        List<ScanRepository.JobRow> jobs = repo.listJobs(datasourceId, dbName, schemaName);
        Map<Long, List<ScanJobEvent>> eventsByJob =
                repo.listJobEventsByJob(jobs.stream().map(ScanRepository.JobRow::id).toList());
        return jobs.stream()
                .map(j -> toView(j, dsNames.get(j.datasourceId()), null,
                        eventsByJob.getOrDefault(j.id(), List.of())))
                .toList();
    }

    public ScanJobView getJob(long jobId) {
        ScanRepository.JobRow job = repo.findJob(jobId)
                .orElseThrow(() -> new IllegalArgumentException("任务不存在: " + jobId));
        String dsName = dsRepo.findById(job.datasourceId()).map(DataSourceConfig::getName).orElse(null);
        return toView(job, dsName, repo.listScanTables(jobId), repo.listJobEvents(jobId));
    }

    public List<ScanColumnView> getColumns(long jobId, String tableName) {
        ScanTableView table = repo.findScanTableByName(jobId, tableName)
                .orElseThrow(() -> new IllegalArgumentException("任务中不存在该表: " + tableName));
        return repo.listScanColumns(table.id());
    }

    public ScanTableView getTable(long jobId, String tableName) {
        return repo.findScanTableByName(jobId, tableName)
                .orElseThrow(() -> new IllegalArgumentException("任务中不存在该表: " + tableName));
    }

    private ScanJobView toView(ScanRepository.JobRow j, String dsName, List<ScanTableView> tables,
                               List<ScanJobEvent> events) {
        List<NullRule> rules = List.of();
        if (j.nullRulesJson() != null && !j.nullRulesJson().isBlank()) {
            try {
                rules = objectMapper.readValue(j.nullRulesJson(),
                        new com.fasterxml.jackson.core.type.TypeReference<List<NullRule>>() {});
            } catch (Exception e) {
                log.warn("任务空值规则解析失败,按空规则处理 jobId={}: {}", j.id(), e.getMessage());
            }
        }
        com.example.dq.model.DbType dbType = dsRepo.findById(j.datasourceId())
                .map(DataSourceConfig::getDbType).orElse(null);
        return new ScanJobView(j.id(), j.datasourceId(), dsName, dbType, j.dbName(), j.schemaName(), j.status(),
                j.forceFull(), rules, j.totalTables(), j.doneTables(), progress(j, tables), j.error(),
                j.createdAt(), j.startedAt(), j.finishedAt(), events, tables);
    }

    /** 任务总进度:按各表估算行数加权 */
    private double progress(ScanRepository.JobRow j, List<ScanTableView> tables) {
        if (j.status() == ScanStatus.DONE) {
            return 100.0;
        }
        List<ScanTableView> ts = tables != null ? tables : repo.listScanTables(j.id());
        if (ts.isEmpty()) {
            return 0.0;
        }
        double sumWeight = 0;
        double sumDone = 0;
        for (ScanTableView t : ts) {
            double weight = t.estRows() != null && t.estRows() > 0 ? t.estRows() : 1;
            double fraction = switch (t.status()) {
                case DONE -> 1.0;
                case FAILED, CANCELED -> 1.0; // 终态表不再前进,按完成计入避免进度条卡住
                default -> t.totalChunks() > 0 ? (double) t.doneChunks() / t.totalChunks() : 0.0;
            };
            sumWeight += weight;
            sumDone += weight * fraction;
        }
        return sumWeight > 0 ? sumDone * 100.0 / sumWeight : 0.0;
    }

    // ---------- 取消 / 断点续扫 ----------

    /** 删除任务:进行中(PENDING/RUNNING)的任务需先取消,删除会清掉全部分段与字段统计 */
    @Transactional
    public void delete(long jobId) {
        ScanRepository.JobRow job = repo.findJob(jobId)
                .orElseThrow(() -> new IllegalArgumentException("任务不存在: " + jobId));
        if (job.status() == ScanStatus.PENDING || job.status() == ScanStatus.RUNNING) {
            throw new IllegalStateException("任务正在进行中,请先取消再删除");
        }
        repo.deleteJob(jobId);
    }

    public void cancel(long jobId) {
        ScanRepository.JobRow job = repo.findJob(jobId)
                .orElseThrow(() -> new IllegalArgumentException("任务不存在: " + jobId));
        if (job.status() != ScanStatus.RUNNING && job.status() != ScanStatus.PENDING) {
            return;
        }
        repo.updateJobStatus(jobId, ScanStatus.CANCELED);
        repo.cancelPendingChunksByJob(jobId);
        for (ChunkRecord c : repo.listRunningChunksByJob(jobId)) {
            executor.cancelStatement(c.id());
        }
        for (ScanTableView t : repo.listScanTables(jobId)) {
            if (t.status() == ScanStatus.PENDING || t.status() == ScanStatus.RUNNING) {
                repo.finishTable(t.id(), ScanStatus.CANCELED, null, null);
            }
        }
        repo.finishJob(jobId, ScanStatus.CANCELED, null);
    }

    /** 断点续扫:校验结构未变,未完成的段重新入队 */
    public void resume(long jobId) {
        ScanRepository.JobRow job = repo.findJob(jobId)
                .orElseThrow(() -> new IllegalArgumentException("任务不存在: " + jobId));
        if (job.status() != ScanStatus.CANCELED && job.status() != ScanStatus.INTERRUPTED
                && job.status() != ScanStatus.FAILED) {
            throw new IllegalStateException("只有已取消/已中断/失败的任务才能续扫,当前状态: " + job.status());
        }
        DataSourceConfig ds = dataSourceService.get(job.datasourceId());
        DbDialect dialect = dialectFactory.get(ds.getDbType());

        repo.markJobRunning(jobId);
        for (ScanTableView t : repo.listScanTables(jobId)) {
            if (t.status() == ScanStatus.DONE) {
                continue;
            }
            try (Connection conn = dataSourceService.getConnection(job.datasourceId())) {
                dialect.useDatabase(conn, dataSourceService.resolveDatabase(job.datasourceId(), job.dbName()));
                List<ColumnMeta> cols = dialect.listColumns(conn, job.schemaName(), t.tableName());
                if (cols.isEmpty()) {
                    throw new IllegalStateException("表 " + t.tableName() + " 已不存在,无法续扫,请重新发起扫描");
                }
                ColumnMeta chunkKey = dialect.pickChunkKey(cols);
                String newKey = chunkKey != null ? chunkKey.name() : null;
                if (t.totalChunks() > 0 && !java.util.Objects.equals(newKey, t.chunkKey())) {
                    throw new IllegalStateException(
                            "表 " + t.tableName() + " 的分段键已变化(" + t.chunkKey() + " -> " + newKey + "),请重新发起扫描");
                }
            } catch (IllegalStateException e) {
                repo.finishJob(jobId, ScanStatus.FAILED, e.getMessage());
                throw e;
            } catch (Exception e) {
                repo.finishJob(jobId, ScanStatus.FAILED, e.getMessage());
                throw new IllegalStateException("续扫校验失败: " + e.getMessage(), e);
            }

            if (t.totalChunks() == 0) {
                // 规划未完成,重新规划
                repo.deleteChunks(t.id());
                executor.submit(() -> planTable(t.id()));
            } else {
                repo.resetUnfinishedChunks(t.id());
                repo.markTableRunning(t.id());
                for (ChunkRecord c : repo.listChunks(t.id())) {
                    if (c.status() == ScanStatus.PENDING) {
                        executor.submit(() -> chunkRunner.run(c.id()));
                    }
                }
            }
        }

        // 极端情况:上次中断发生在最后一张表完成之后、任务收尾之前
        List<ScanTableView> after = repo.listScanTables(jobId);
        if (after.stream().allMatch(t -> t.status() == ScanStatus.DONE)) {
            repo.finishJob(jobId, ScanStatus.DONE, null);
        }
    }

    /** 应用重启恢复:运行中的任务标记为中断,可手动续扫 */
    public void recoverAfterRestart() {
        int jobs = repo.markRunningJobsInterrupted();
        int chunks = repo.markRunningChunksCanceled();
        if (jobs > 0) {
            log.info("检测到 {} 个未完成任务已标记为 INTERRUPTED({} 个运行中分段已重置),可通过续扫继续", jobs, chunks);
        }
    }
}
