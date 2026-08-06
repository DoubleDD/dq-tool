package com.example.dq.repository;

import com.example.dq.model.ChunkRecord;
import com.example.dq.model.ScanColumnView;
import com.example.dq.model.ScanJobEvent;
import com.example.dq.model.ScanStatus;
import com.example.dq.model.ScanTableView;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

@Repository
public class ScanRepository {

    private final JdbcTemplate jdbc;

    public ScanRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    // ---------- scan_job ----------

    public record JobRow(long id, long datasourceId, String dbName, String schemaName, ScanStatus status,
                         boolean forceFull, String nullRulesJson, int totalTables, int doneTables,
                         String error, LocalDateTime createdAt, LocalDateTime startedAt, LocalDateTime finishedAt) {
    }

    private final RowMapper<JobRow> jobMapper = (rs, i) -> new JobRow(
            rs.getLong("id"), rs.getLong("datasource_id"), rs.getString("db_name"), rs.getString("schema_name"),
            ScanStatus.valueOf(rs.getString("status")), rs.getBoolean("force_full"),
            rs.getString("null_rules"), rs.getInt("total_tables"), rs.getInt("done_tables"),
            rs.getString("error"),
            ts(rs, "created_at"), ts(rs, "started_at"), ts(rs, "finished_at"));

    private static LocalDateTime ts(java.sql.ResultSet rs, String col) throws java.sql.SQLException {
        Timestamp t = rs.getTimestamp(col);
        return t == null ? null : t.toLocalDateTime();
    }

    public long insertJob(long datasourceId, String dbName, String schema, boolean forceFull, String nullRulesJson, int totalTables) {
        KeyHolder kh = new GeneratedKeyHolder();
        jdbc.update(conn -> {
            PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO scan_job(datasource_id, db_name, schema_name, status, force_full, null_rules, total_tables) "
                            + "VALUES (?,?,?,'PENDING',?,?,?)", new String[]{"ID"});
            ps.setLong(1, datasourceId);
            ps.setString(2, dbName);
            ps.setString(3, schema);
            ps.setBoolean(4, forceFull);
            ps.setString(5, nullRulesJson);
            ps.setInt(6, totalTables);
            return ps;
        }, kh);
        long jobId = Objects.requireNonNull(kh.getKey()).longValue();
        insertJobEvent(jobId, ScanStatus.PENDING);
        return jobId;
    }

    public Optional<JobRow> findJob(long jobId) {
        return jdbc.query("SELECT * FROM scan_job WHERE id=?", jobMapper, jobId).stream().findFirst();
    }

    public List<JobRow> listJobs(Long datasourceId, String dbName, String schema) {
        StringBuilder sql = new StringBuilder("SELECT * FROM scan_job WHERE 1=1");
        List<Object> args = new ArrayList<>();
        if (datasourceId != null) {
            sql.append(" AND datasource_id=?");
            args.add(datasourceId);
        }
        if (dbName != null && !dbName.isBlank()) {
            sql.append(" AND db_name=?");
            args.add(dbName);
        }
        if (schema != null && !schema.isBlank()) {
            sql.append(" AND schema_name=?");
            args.add(schema);
        }
        sql.append(" ORDER BY id DESC");
        return jdbc.query(sql.toString(), jobMapper, args.toArray());
    }

    /**
     * 每个 schema 最近一次扫描任务(按 id 最大),供库列表页展示"最近扫描"。
     * dbName 为空时只匹配 db_name 为 NULL 的任务(与 ScanRequest.database 的落库口径一致)。
     */
    public Map<String, JobRow> latestJobsBySchema(long datasourceId, String dbName) {
        String dbCond = (dbName != null && !dbName.isBlank()) ? "db_name=?" : "db_name IS NULL";
        List<Object> args = new ArrayList<>();
        args.add(datasourceId);
        if (dbName != null && !dbName.isBlank()) args.add(dbName);
        String sql = "SELECT * FROM scan_job WHERE datasource_id=? AND " + dbCond
                + " AND id IN (SELECT MAX(id) FROM scan_job WHERE datasource_id=? AND " + dbCond
                + " GROUP BY schema_name)";
        args.add(datasourceId);
        if (dbName != null && !dbName.isBlank()) args.add(dbName);
        Map<String, JobRow> latest = new HashMap<>();
        for (JobRow row : jdbc.query(sql, jobMapper, args.toArray())) {
            latest.put(row.schemaName(), row);
        }
        return latest;
    }

    /**
     * 每张表最近一次 DONE 扫描的信息:任务 id、该表的完成时间、统计行数、扫描时记录的大小快照、是否采样。
     * totalRows 仅对非采样表是 COUNT(*) 精确值;采样表只是采样行数,展示层不应据此替换估算值。
     */
    public record LatestScan(long jobId, LocalDateTime finishedAt, Long totalRows, Long sizeBytes, boolean sampled) {
    }

    /** 运行中任务里每张未完成表的进度:任务 id、表级状态(PENDING/RUNNING)、分段进度 */
    public record RunningScan(long jobId, String status, int doneChunks, int totalChunks) {
    }

    /**
     * 运行中任务里每张未完成表的分段进度,供表列表页"扫描中显示进度"。
     * 只取 j.status='RUNNING' 的任务内 t.status 为 PENDING/RUNNING 的表;dbName 口径与 latestJobsBySchema 一致。
     */
    public Map<String, RunningScan> runningScansByTable(long datasourceId, String dbName, String schemaName) {
        String dbCond = (dbName != null && !dbName.isBlank()) ? "j.db_name=?" : "j.db_name IS NULL";
        List<Object> args = new ArrayList<>();
        args.add(datasourceId);
        if (dbName != null && !dbName.isBlank()) args.add(dbName);
        args.add(schemaName);
        String sql = "SELECT t.table_name, t.job_id, t.status, t.done_chunks, t.total_chunks FROM scan_table t "
                + "JOIN scan_job j ON t.job_id=j.id "
                + "WHERE j.datasource_id=? AND " + dbCond + " AND j.schema_name=? AND j.status='RUNNING' "
                + "AND t.status IN ('PENDING','RUNNING') ORDER BY t.job_id";
        Map<String, RunningScan> result = new HashMap<>();
        jdbc.query(sql, (org.springframework.jdbc.core.RowCallbackHandler)
                rs -> result.put(rs.getString(1),
                        new RunningScan(rs.getLong(2), rs.getString(3), rs.getInt(4), rs.getInt(5))), args.toArray());
        return result;
    }

    /**
     * 每张表最近一次扫描完成(表级 DONE)的任务 id、完成时间及该表统计行数/大小快照,
     * 供表列表页"点击表名直达最新结果"、"最近扫描时间"列,以及用扫描准确值覆盖元数据估算的行数/大小。
     * 同一表可能出现在多个任务里,按 job_id 升序遍历、后者覆盖前者,最终留下 job_id 最大者;
     * dbName 为空的口径与 latestJobsBySchema 一致。
     */
    public Map<String, LatestScan> latestDoneJobsByTable(long datasourceId, String dbName, String schemaName) {
        String dbCond = (dbName != null && !dbName.isBlank()) ? "j.db_name=?" : "j.db_name IS NULL";
        List<Object> args = new ArrayList<>();
        args.add(datasourceId);
        if (dbName != null && !dbName.isBlank()) args.add(dbName);
        args.add(schemaName);
        String sql = "SELECT t.table_name, t.job_id, t.finished_at, t.total_rows, t.size_bytes, t.sampled FROM scan_table t "
                + "JOIN scan_job j ON t.job_id=j.id "
                + "WHERE j.datasource_id=? AND " + dbCond + " AND j.schema_name=? AND t.status='DONE' "
                + "ORDER BY t.job_id";
        Map<String, LatestScan> result = new HashMap<>();
        jdbc.query(sql, (org.springframework.jdbc.core.RowCallbackHandler)
                rs -> {
                    long totalRows = rs.getLong("total_rows");
                    boolean totalNull = rs.wasNull(); // wasNull 只反映最近一次读取,需即时判断
                    long sizeBytes = rs.getLong("size_bytes");
                    boolean sizeNull = rs.wasNull();
                    result.put(rs.getString(1), new LatestScan(rs.getLong(2), ts(rs, "finished_at"),
                            totalNull ? null : totalRows, sizeNull ? null : sizeBytes, rs.getBoolean("sampled")));
                }, args.toArray());
        return result;
    }

    public void updateJobStatus(long jobId, ScanStatus status) {
        jdbc.update("UPDATE scan_job SET status=? WHERE id=?", status.name(), jobId);
    }

    public void markJobRunning(long jobId) {
        jdbc.update("UPDATE scan_job SET status='RUNNING', started_at=CURRENT_TIMESTAMP, finished_at=NULL, error=NULL WHERE id=?", jobId);
        insertJobEvent(jobId, ScanStatus.RUNNING);
    }

    public void finishJob(long jobId, ScanStatus status, String error) {
        jdbc.update("UPDATE scan_job SET status=?, finished_at=CURRENT_TIMESTAMP, error=? WHERE id=?",
                status.name(), error, jobId);
        insertJobEvent(jobId, status);
    }

    /** done_tables + 1,返回自增后的值 */
    public int incrementJobDoneTables(long jobId) {
        jdbc.update("UPDATE scan_job SET done_tables = done_tables + 1 WHERE id=?", jobId);
        return jdbc.queryForObject("SELECT done_tables FROM scan_job WHERE id=?", Integer.class, jobId);
    }

    public int markRunningJobsInterrupted() {
        // 先取出受影响任务,逐条补 INTERRUPTED 事件(重启恢复场景,量不大)
        List<Long> ids = jdbc.queryForList(
                "SELECT id FROM scan_job WHERE status IN ('PENDING','RUNNING')", Long.class);
        if (ids.isEmpty()) {
            return 0;
        }
        int n = jdbc.update("UPDATE scan_job SET status='INTERRUPTED' WHERE status IN ('PENDING','RUNNING')");
        ids.forEach(id -> insertJobEvent(id, ScanStatus.INTERRUPTED));
        return n;
    }

    // ---------- scan_job_event ----------

    private final RowMapper<ScanJobEvent> eventMapper = (rs, i) ->
            new ScanJobEvent(ScanStatus.valueOf(rs.getString("status")), ts(rs, "created_at"));

    public void insertJobEvent(long jobId, ScanStatus status) {
        jdbc.update("INSERT INTO scan_job_event(job_id, status) VALUES (?,?)", jobId, status.name());
    }

    public List<ScanJobEvent> listJobEvents(long jobId) {
        return jdbc.query("SELECT status, created_at FROM scan_job_event WHERE job_id=? ORDER BY id",
                eventMapper, jobId);
    }

    /** 批量取多个任务的事件并按任务分组,供列表接口避免逐任务查询 */
    public Map<Long, List<ScanJobEvent>> listJobEventsByJob(List<Long> jobIds) {
        if (jobIds.isEmpty()) {
            return Map.of();
        }
        String placeholders = String.join(", ", jobIds.stream().map(id -> "?").toList());
        Map<Long, List<ScanJobEvent>> result = new HashMap<>();
        jdbc.query("SELECT job_id, status, created_at FROM scan_job_event WHERE job_id IN (" + placeholders
                        + ") ORDER BY id",
                (org.springframework.jdbc.core.RowCallbackHandler) rs -> result
                        .computeIfAbsent(rs.getLong("job_id"), k -> new ArrayList<>())
                        .add(new ScanJobEvent(ScanStatus.valueOf(rs.getString("status")), ts(rs, "created_at"))),
                jobIds.toArray());
        return result;
    }

    /** 删除任务及其全部明细(表/分段/字段结果),由 service 层在事务中调用 */
    public void deleteJob(long jobId) {
        jdbc.update("DELETE FROM scan_chunk WHERE scan_table_id IN (SELECT id FROM scan_table WHERE job_id=?)", jobId);
        jdbc.update("DELETE FROM scan_column WHERE scan_table_id IN (SELECT id FROM scan_table WHERE job_id=?)", jobId);
        jdbc.update("DELETE FROM scan_table WHERE job_id=?", jobId);
        jdbc.update("DELETE FROM scan_job_event WHERE job_id=?", jobId);
        jdbc.update("DELETE FROM scan_job WHERE id=?", jobId);
    }

    // ---------- scan_table ----------

    private final RowMapper<ScanTableView> tableMapper = (rs, i) -> {
        long est = rs.getLong("est_rows");
        Long estRows = rs.wasNull() ? null : est;
        long size = rs.getLong("size_bytes");
        Long sizeBytes = rs.wasNull() ? null : size;
        long sr = rs.getLong("sample_rows");
        Long sampleRows = rs.wasNull() ? null : sr;
        long tr = rs.getLong("total_rows");
        Long totalRows = rs.wasNull() ? null : tr;
        return new ScanTableView(
                rs.getLong("id"), rs.getLong("job_id"), rs.getString("table_name"),
                ScanStatus.valueOf(rs.getString("status")), rs.getBoolean("sampled"),
                sampleRows, estRows, sizeBytes, rs.getString("comment"), rs.getString("storage_info"),
                rs.getString("chunk_key"),
                rs.getInt("total_chunks"), rs.getInt("done_chunks"), rs.getLong("scanned_rows"),
                totalRows, rs.getString("error"), ts(rs, "started_at"), ts(rs, "finished_at"));
    };

    public long insertScanTable(long jobId, String tableName, Long estRows, Long sizeBytes,
                                String comment, String storageInfo) {
        KeyHolder kh = new GeneratedKeyHolder();
        jdbc.update(conn -> {
            PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO scan_table(job_id, table_name, status, est_rows, size_bytes, comment, storage_info) "
                            + "VALUES (?,?,'PENDING',?,?,?,?)",
                    new String[]{"ID"});
            ps.setLong(1, jobId);
            ps.setString(2, tableName);
            if (estRows != null) ps.setLong(3, estRows); else ps.setNull(3, java.sql.Types.BIGINT);
            if (sizeBytes != null) ps.setLong(4, sizeBytes); else ps.setNull(4, java.sql.Types.BIGINT);
            ps.setString(5, comment);
            ps.setString(6, storageInfo);
            return ps;
        }, kh);
        return Objects.requireNonNull(kh.getKey()).longValue();
    }

    public Optional<ScanTableView> findScanTable(long scanTableId) {
        return jdbc.query("SELECT * FROM scan_table WHERE id=?", tableMapper, scanTableId).stream().findFirst();
    }

    public Optional<ScanTableView> findScanTableByName(long jobId, String tableName) {
        return jdbc.query("SELECT * FROM scan_table WHERE job_id=? AND table_name=?", tableMapper, jobId, tableName)
                .stream().findFirst();
    }

    public List<ScanTableView> listScanTables(long jobId) {
        return jdbc.query("SELECT * FROM scan_table WHERE job_id=? ORDER BY id", tableMapper, jobId);
    }

    /** 规划完成:写入分段键/采样标记/段数,置为 RUNNING */
    public void markTablePlanned(long scanTableId, String chunkKey, boolean sampled, Long sampleRows, int totalChunks) {
        jdbc.update("UPDATE scan_table SET status='RUNNING', chunk_key=?, sampled=?, sample_rows=?, total_chunks=?, "
                        + "started_at=CURRENT_TIMESTAMP WHERE id=?",
                chunkKey, sampled, sampleRows, totalChunks, scanTableId);
    }

    public void markTableRunning(long scanTableId) {
        jdbc.update("UPDATE scan_table SET status='RUNNING', started_at=CURRENT_TIMESTAMP, finished_at=NULL, error=NULL WHERE id=?",
                scanTableId);
    }

    public void finishTable(long scanTableId, ScanStatus status, Long totalRows, String error) {
        if (status == ScanStatus.DONE) {
            jdbc.update("UPDATE scan_table SET status='DONE', total_rows=?, finished_at=CURRENT_TIMESTAMP WHERE id=?",
                    totalRows, scanTableId);
        } else {
            jdbc.update("UPDATE scan_table SET status=?, error=?, finished_at=CURRENT_TIMESTAMP WHERE id=?",
                    status.name(), error, scanTableId);
        }
    }

    /** done_chunks+1 且 scanned_rows 累加,返回最新 done_chunks */
    public int recordChunkDone(long scanTableId, long rowCount) {
        jdbc.update("UPDATE scan_table SET done_chunks = done_chunks + 1, scanned_rows = scanned_rows + ? WHERE id=?",
                rowCount, scanTableId);
        Integer v = jdbc.queryForObject("SELECT done_chunks FROM scan_table WHERE id=?", Integer.class, scanTableId);
        return v == null ? 0 : v;
    }

    // ---------- scan_chunk ----------

    private final RowMapper<ChunkRecord> chunkMapper = (rs, i) -> new ChunkRecord(
            rs.getLong("id"), rs.getLong("scan_table_id"), rs.getInt("seq"),
            rs.getString("range_start"), rs.getString("range_end"), rs.getBoolean("null_chunk"),
            ScanStatus.valueOf(rs.getString("status")), rs.getLong("row_count"),
            rs.getString("col_stats"), rs.getInt("attempts"));

    public long insertChunk(long scanTableId, int seq, String rangeStart, String rangeEnd, boolean nullChunk) {
        KeyHolder kh = new GeneratedKeyHolder();
        jdbc.update(conn -> {
            PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO scan_chunk(scan_table_id, seq, range_start, range_end, null_chunk, status) "
                            + "VALUES (?,?,?,?,?,'PENDING')", new String[]{"ID"});
            ps.setLong(1, scanTableId);
            ps.setInt(2, seq);
            ps.setString(3, rangeStart);
            ps.setString(4, rangeEnd);
            ps.setBoolean(5, nullChunk);
            return ps;
        }, kh);
        return Objects.requireNonNull(kh.getKey()).longValue();
    }

    public Optional<ChunkRecord> findChunk(long chunkId) {
        return jdbc.query("SELECT * FROM scan_chunk WHERE id=?", chunkMapper, chunkId).stream().findFirst();
    }

    public List<ChunkRecord> listChunks(long scanTableId) {
        return jdbc.query("SELECT * FROM scan_chunk WHERE scan_table_id=? ORDER BY seq", chunkMapper, scanTableId);
    }

    public List<ChunkRecord> listDoneChunks(long scanTableId) {
        return jdbc.query("SELECT * FROM scan_chunk WHERE scan_table_id=? AND status='DONE' ORDER BY seq",
                chunkMapper, scanTableId);
    }

    public void markChunkRunning(long chunkId) {
        jdbc.update("UPDATE scan_chunk SET status='RUNNING', attempts = attempts + 1, error=NULL WHERE id=?", chunkId);
    }

    public void markChunkDone(long chunkId, long rowCount, String colStatsJson) {
        jdbc.update("UPDATE scan_chunk SET status='DONE', row_count=?, col_stats=? WHERE id=?",
                rowCount, colStatsJson, chunkId);
    }

    public void markChunkStatus(long chunkId, ScanStatus status, String error) {
        jdbc.update("UPDATE scan_chunk SET status=?, error=? WHERE id=?", status.name(), error, chunkId);
    }

    public void deleteChunks(long scanTableId) {
        jdbc.update("DELETE FROM scan_chunk WHERE scan_table_id=?", scanTableId);
    }

    /** 续扫:把未完成的段重置为 PENDING,返回重置数量 */
    public int resetUnfinishedChunks(long scanTableId) {
        return jdbc.update("UPDATE scan_chunk SET status='PENDING', error=NULL "
                + "WHERE scan_table_id=? AND status IN ('PENDING','RUNNING','FAILED','CANCELED')", scanTableId);
    }

    public int cancelPendingChunksByJob(long jobId) {
        return jdbc.update("UPDATE scan_chunk SET status='CANCELED' WHERE status='PENDING' AND scan_table_id IN "
                + "(SELECT id FROM scan_table WHERE job_id=?)", jobId);
    }

    public int markRunningChunksCanceled() {
        return jdbc.update("UPDATE scan_chunk SET status='CANCELED' WHERE status='RUNNING'");
    }

    public List<ChunkRecord> listRunningChunksByJob(long jobId) {
        return jdbc.query("SELECT c.* FROM scan_chunk c JOIN scan_table t ON c.scan_table_id = t.id "
                + "WHERE t.job_id=? AND c.status='RUNNING'", chunkMapper, jobId);
    }

    // ---------- scan_column ----------

    public void insertScanColumn(long scanTableId, ScanColumnView col) {
        jdbc.update("INSERT INTO scan_column(scan_table_id, column_name, column_type, column_comment, nullable, "
                        + "default_value, key_label, total_rows, null_count, empty_count, rule_hit_count) "
                        + "VALUES (?,?,?,?,?,?,?,?,?,?,?)",
                scanTableId, col.columnName(), col.columnType(), col.columnComment(), col.nullable(),
                col.defaultValue(), col.keyLabel(), col.totalRows(),
                col.nullCount(), col.emptyCount(), col.ruleHitCount());
    }

    public List<ScanColumnView> listScanColumns(long scanTableId) {
        return jdbc.query("SELECT * FROM scan_column WHERE scan_table_id=? ORDER BY id",
                (rs, i) -> ScanColumnView.of(
                        rs.getString("column_name"), rs.getString("column_type"),
                        rs.getString("column_comment"),
                        rs.getObject("nullable") == null ? null : rs.getBoolean("nullable"),
                        rs.getString("default_value"), rs.getString("key_label"),
                        rs.getLong("total_rows"), rs.getLong("null_count"),
                        rs.getLong("empty_count"), rs.getLong("rule_hit_count")),
                scanTableId);
    }

    public void deleteScanColumns(long scanTableId) {
        jdbc.update("DELETE FROM scan_column WHERE scan_table_id=?", scanTableId);
    }
}
