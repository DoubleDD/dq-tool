package com.example.dq.repository;

import com.example.dq.model.ChunkRecord;
import com.example.dq.model.ScanColumnView;
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
import java.util.List;
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
        return Objects.requireNonNull(kh.getKey()).longValue();
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

    public void updateJobStatus(long jobId, ScanStatus status) {
        jdbc.update("UPDATE scan_job SET status=? WHERE id=?", status.name(), jobId);
    }

    public void markJobRunning(long jobId) {
        jdbc.update("UPDATE scan_job SET status='RUNNING', started_at=CURRENT_TIMESTAMP, finished_at=NULL, error=NULL WHERE id=?", jobId);
    }

    public void finishJob(long jobId, ScanStatus status, String error) {
        jdbc.update("UPDATE scan_job SET status=?, finished_at=CURRENT_TIMESTAMP, error=? WHERE id=?",
                status.name(), error, jobId);
    }

    /** done_tables + 1,返回自增后的值 */
    public int incrementJobDoneTables(long jobId) {
        jdbc.update("UPDATE scan_job SET done_tables = done_tables + 1 WHERE id=?", jobId);
        return jdbc.queryForObject("SELECT done_tables FROM scan_job WHERE id=?", Integer.class, jobId);
    }

    public int markRunningJobsInterrupted() {
        return jdbc.update("UPDATE scan_job SET status='INTERRUPTED' WHERE status IN ('PENDING','RUNNING')");
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
