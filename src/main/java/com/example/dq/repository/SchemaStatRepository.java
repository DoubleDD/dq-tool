package com.example.dq.repository;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;

/** 库列表页统计缓存(schema_stat 表):首访从业务库拉取落库,之后只读本地,扫描创建时按 schema 刷新 */
@Repository
public class SchemaStatRepository {

    private final JdbcTemplate jdbc;

    public SchemaStatRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 缓存行:schema 名 + 表数量 + 数据索引总字节(后两者可空,方言不支持时为 null) */
    public record CachedStat(String schemaName, Integer tableCount, Long sizeBytes) {
    }

    /** dbName 为空匹配 NULL,与 scan_job 的落库口径一致 */
    public List<CachedStat> findAll(long datasourceId, String dbName) {
        String sql = "SELECT schema_name, table_count, size_bytes FROM schema_stat WHERE datasource_id=? AND "
                + dbCond(dbName) + " ORDER BY schema_name";
        return jdbc.query(sql, (rs, i) -> {
            int count = rs.getInt(2);
            boolean countNull = rs.wasNull();
            long bytes = rs.getLong(3);
            boolean bytesNull = rs.wasNull();
            return new CachedStat(rs.getString(1), countNull ? null : count, bytesNull ? null : bytes);
        }, queryArgs(datasourceId, dbName));
    }

    /** 全量替换某数据源某库的缓存(首次从业务库拉取后整体写入) */
    public void replaceAll(long datasourceId, String dbName, List<CachedStat> stats) {
        jdbc.update("DELETE FROM schema_stat WHERE datasource_id=? AND " + dbCond(dbName),
                queryArgs(datasourceId, dbName));
        for (CachedStat s : stats) {
            insert(datasourceId, dbName, s);
        }
    }

    /** 单 schema 刷新(扫描创建时调用);缓存未初始化时也直接写入 */
    public void upsert(long datasourceId, String dbName, CachedStat stat) {
        jdbc.update("DELETE FROM schema_stat WHERE datasource_id=? AND " + dbCond(dbName) + " AND schema_name=?",
                queryArgs(datasourceId, dbName, stat.schemaName()));
        insert(datasourceId, dbName, stat);
    }

    /** 删除数据源时级联清理 */
    public void deleteByDatasource(long datasourceId) {
        jdbc.update("DELETE FROM schema_stat WHERE datasource_id=?", datasourceId);
    }

    private void insert(long datasourceId, String dbName, CachedStat s) {
        jdbc.update("INSERT INTO schema_stat(datasource_id, db_name, schema_name, table_count, size_bytes) "
                        + "VALUES (?,?,?,?,?)",
                datasourceId, dbName, s.schemaName(), s.tableCount(), s.sizeBytes());
    }

    private static String dbCond(String dbName) {
        return dbName != null && !dbName.isBlank() ? "db_name=?" : "db_name IS NULL";
    }

    /** 按 dbCond 是否带占位符组装参数 */
    private static Object[] queryArgs(long datasourceId, String dbName, Object... extra) {
        boolean withDb = dbName != null && !dbName.isBlank();
        Object[] args = new Object[(withDb ? 2 : 1) + extra.length];
        args[0] = datasourceId;
        if (withDb) {
            args[1] = dbName;
        }
        System.arraycopy(extra, 0, args, withDb ? 2 : 1, extra.length);
        return args;
    }
}
