package com.example.dq.scan;

import com.example.dq.config.DqProperties;
import com.example.dq.dialect.DbDialect;
import com.example.dq.dialect.DialectFactory;
import com.example.dq.model.ChunkRecord;
import com.example.dq.model.ColChunkStat;
import com.example.dq.model.ColumnMeta;
import com.example.dq.model.DataSourceConfig;
import com.example.dq.model.NullRule;
import com.example.dq.model.Range;
import com.example.dq.model.ScanColumnView;
import com.example.dq.model.ScanStatus;
import com.example.dq.model.ScanTableView;
import com.example.dq.repository.ScanRepository;
import com.example.dq.service.DataSourceService;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** 单个分段的统计执行 + 结果落库 + 表/任务完成推进 */
@Component
public class ChunkRunner {

    private static final Logger log = LoggerFactory.getLogger(ChunkRunner.class);
    /** 每条统计 SQL 包含的最大列数,与 AbstractDialect.MAX_COLS_PER_SQL 对应 */
    private static final int COL_BATCH = 80;
    private static final int MAX_ATTEMPTS = 2;

    private final ScanRepository repo;
    private final DataSourceService dataSourceService;
    private final DialectFactory dialectFactory;
    private final DqProperties props;
    private final ScanExecutor executor;
    private final ObjectMapper objectMapper;
    /** scanTableId / jobId 的完成判定需要串行化 */
    private final Map<Long, Object> locks = new ConcurrentHashMap<>();

    public ChunkRunner(ScanRepository repo, DataSourceService dataSourceService,
                       DialectFactory dialectFactory, DqProperties props,
                       ScanExecutor executor, ObjectMapper objectMapper) {
        this.repo = repo;
        this.dataSourceService = dataSourceService;
        this.dialectFactory = dialectFactory;
        this.props = props;
        this.executor = executor;
        this.objectMapper = objectMapper;
    }

    public void run(long chunkId) {
        run(chunkId, false);
    }

    private void run(long chunkId, boolean retried) {
        ChunkRecord chunk = repo.findChunk(chunkId).orElse(null);
        if (chunk == null || chunk.status() != ScanStatus.PENDING) {
            return;
        }
        ScanTableView table = repo.findScanTable(chunk.scanTableId()).orElse(null);
        if (table == null) {
            return;
        }
        ScanRepository.JobRow job = repo.findJob(table.jobId()).orElse(null);
        if (job == null || job.status() != ScanStatus.RUNNING) {
            return;
        }

        DataSourceConfig ds = dataSourceService.get(job.datasourceId());
        DbDialect dialect = dialectFactory.get(ds.getDbType());
        repo.markChunkRunning(chunkId);
        try (Connection conn = dataSourceService.getConnection(job.datasourceId())) {
            dialect.useDatabase(conn, dataSourceService.resolveDatabase(job.datasourceId(), job.dbName()));
            List<ColumnMeta> cols = dialect.listColumns(conn, job.schemaName(), table.tableName());
            ColumnMeta chunkKey = table.chunkKey() == null ? null
                    : cols.stream().filter(c -> c.name().equals(table.chunkKey())).findFirst().orElse(null);
            List<NullRule> rules = parseRules(job.nullRulesJson());
            Range range = chunk.toRange();

            long total = 0;
            Map<String, long[]> acc = new LinkedHashMap<>(); // column -> [null, empty, ruleHit]
            for (List<ColumnMeta> batch : partition(cols, COL_BATCH)) {
                String sql = dialect.buildColumnStatsSql(job.schemaName(), table.tableName(), batch,
                        range, chunkKey, rules, table.sampled(),
                        table.sampleRows() != null ? table.sampleRows() : props.getScan().getSampleRows(),
                        table.estRows());
                try (Statement stmt = conn.createStatement()) {
                    stmt.setQueryTimeout(props.getScan().getStatementTimeoutSeconds());
                    executor.registerStatement(chunkId, stmt);
                    try (ResultSet rs = stmt.executeQuery(sql)) {
                        if (rs.next()) {
                            total = rs.getLong(1);
                            parseBatch(rs, batch, dialect.selectorLayout(batch, rules), acc);
                        }
                    } finally {
                        executor.unregisterStatement(chunkId);
                    }
                }
            }

            // 执行期间任务被取消:结果作废
            if (repo.findJob(job.id()).map(j -> j.status() != ScanStatus.RUNNING).orElse(true)) {
                repo.markChunkStatus(chunkId, ScanStatus.CANCELED, null);
                return;
            }

            List<ColChunkStat> stats = new ArrayList<>();
            acc.forEach((col, v) -> stats.add(new ColChunkStat(col, v[0], v[1], v[2])));
            repo.markChunkDone(chunkId, total, objectMapper.writeValueAsString(stats));
            afterChunkDone(table.id(), total, conn);
        } catch (Exception e) {
            executor.unregisterStatement(chunkId);
            ScanStatus jobStatus = repo.findJob(job.id()).map(ScanRepository.JobRow::status).orElse(null);
            if (jobStatus != ScanStatus.RUNNING) {
                repo.markChunkStatus(chunkId, ScanStatus.CANCELED, null);
                return;
            }
            log.warn("分段执行失败 chunkId={} table={}: {}", chunkId, table.tableName(), e.getMessage(), e);
            if (!retried && chunk.attempts() < MAX_ATTEMPTS) {
                repo.markChunkStatus(chunkId, ScanStatus.PENDING, null);
                run(chunkId, true);
            } else {
                String msg = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
                repo.markChunkStatus(chunkId, ScanStatus.FAILED, msg);
                failTable(table.id(), msg);
            }
        }
    }

    private void parseBatch(ResultSet rs, List<ColumnMeta> batch, List<boolean[]> layout,
                            Map<String, long[]> acc) throws Exception {
        int idx = 2; // 第 1 列是 total
        for (int i = 0; i < batch.size(); i++) {
            ColumnMeta col = batch.get(i);
            long nullCount = rs.getLong(idx++);
            long emptyCount = layout.get(i)[0] ? rs.getLong(idx++) : 0;
            long ruleHit = layout.get(i)[1] ? rs.getLong(idx++) : 0;
            long[] v = acc.computeIfAbsent(col.name(), k -> new long[3]);
            v[0] += nullCount;
            v[1] += emptyCount;
            v[2] += ruleHit;
        }
    }

    /** 分段完成后推进表级进度;全部完成则聚合产出字段级结果(复用当前连接,避免嵌套借连接打满池) */
    private void afterChunkDone(long scanTableId, long rowCount, Connection conn) {
        synchronized (lock(scanTableId)) {
            int done = repo.recordChunkDone(scanTableId, rowCount);
            ScanTableView table = repo.findScanTable(scanTableId).orElse(null);
            if (table == null || done < table.totalChunks()) {
                return;
            }
            finalizeTable(table, conn);
        }
    }

    private void finalizeTable(ScanTableView table, Connection conn) {
        try {
            ScanRepository.JobRow job = repo.findJob(table.jobId()).orElseThrow();
            DataSourceConfig ds = dataSourceService.get(job.datasourceId());
            DbDialect dialect = dialectFactory.get(ds.getDbType());

            // 聚合所有分段的字段计数
            Map<String, long[]> acc = new LinkedHashMap<>();
            long totalRows = 0;
            for (ChunkRecord c : repo.listDoneChunks(table.id())) {
                totalRows += c.rowCount();
                List<ColChunkStat> stats = objectMapper.readValue(
                        c.colStatsJson(), new TypeReference<List<ColChunkStat>>() {});
                for (ColChunkStat s : stats) {
                    long[] v = acc.computeIfAbsent(s.column(), k -> new long[3]);
                    v[0] += s.nullCount();
                    v[1] += s.emptyCount();
                    v[2] += s.ruleHitCount();
                }
            }

            // 补列的展示元数据(复用分段执行的连接)
            Map<String, ColumnMeta> metas = new LinkedHashMap<>();
            for (ColumnMeta c : dialect.listColumns(conn, job.schemaName(), table.tableName())) {
                metas.put(c.name(), c);
            }

            repo.deleteScanColumns(table.id());
            long finalTotal = totalRows;
            acc.forEach((col, v) -> {
                ColumnMeta m = metas.get(col);
                repo.insertScanColumn(table.id(), ScanColumnView.of(
                        col,
                        m != null ? m.displayType() : "",
                        m != null ? m.comment() : "",
                        m != null ? m.nullable() : null,
                        m != null ? m.defaultValue() : null,
                        m != null ? m.keyLabel() : "",
                        finalTotal, v[0], v[1], v[2]));
            });
            repo.finishTable(table.id(), ScanStatus.DONE, totalRows, null);
            checkJobCompletion(table.jobId());
        } catch (Exception e) {
            log.error("表结果聚合失败 scanTableId={}", table.id(), e);
            repo.finishTable(table.id(), ScanStatus.FAILED, null, "结果聚合失败: " + e.getMessage());
            checkJobCompletion(table.jobId());
        }
    }

    /** 表失败(分段重试耗尽或规划失败时调用) */
    public void failTable(long scanTableId, String message) {
        synchronized (lock(scanTableId)) {
            ScanTableView table = repo.findScanTable(scanTableId).orElse(null);
            if (table == null || table.status() == ScanStatus.DONE || table.status() == ScanStatus.FAILED) {
                return;
            }
            repo.finishTable(scanTableId, ScanStatus.FAILED, null, message);
            checkJobCompletion(table.jobId());
        }
    }

    private void checkJobCompletion(long jobId) {
        synchronized (lock(-jobId - 1)) {
            int doneTables = repo.incrementJobDoneTables(jobId);
            ScanRepository.JobRow job = repo.findJob(jobId).orElse(null);
            if (job == null || doneTables < job.totalTables()) {
                return;
            }
            List<ScanTableView> tables = repo.listScanTables(jobId);
            long failed = tables.stream().filter(t -> t.status() == ScanStatus.FAILED).count();
            if (failed > 0) {
                repo.finishJob(jobId, ScanStatus.FAILED, failed + " 张表统计失败");
            } else {
                repo.finishJob(jobId, ScanStatus.DONE, null);
            }
        }
    }

    private Object lock(long id) {
        return locks.computeIfAbsent(id, k -> new Object());
    }

    private List<NullRule> parseRules(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<List<NullRule>>() {});
        } catch (Exception e) {
            log.warn("空值规则解析失败,按空规则处理: {}", e.getMessage());
            return List.of();
        }
    }

    private static <T> List<List<T>> partition(List<T> list, int size) {
        List<List<T>> out = new ArrayList<>();
        for (int i = 0; i < list.size(); i += size) {
            out.add(list.subList(i, Math.min(i + size, list.size())));
        }
        return out;
    }
}
