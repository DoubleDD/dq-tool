package com.example.dq.service;

import com.example.dq.dialect.DbDialect;
import com.example.dq.dialect.DialectFactory;
import com.example.dq.model.DataSourceConfig;
import com.example.dq.model.SchemaStat;
import com.example.dq.model.TableStat;
import com.example.dq.repository.ScanRepository;
import com.example.dq.repository.SchemaStatRepository;
import org.springframework.stereotype.Service;

import java.sql.Connection;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** 库/表元数据查询(同步、快速路径) */
@Service
public class MetadataService {

    private final DataSourceService dataSourceService;
    private final DialectFactory dialectFactory;
    private final ScanRepository scanRepository;
    private final SchemaStatRepository schemaStatRepo;

    public MetadataService(DataSourceService dataSourceService, DialectFactory dialectFactory,
                           ScanRepository scanRepository, SchemaStatRepository schemaStatRepo) {
        this.dataSourceService = dataSourceService;
        this.dialectFactory = dialectFactory;
        this.scanRepository = scanRepository;
        this.schemaStatRepo = schemaStatRepo;
    }

    public List<String> listDatabases(long datasourceId) throws SQLException {
        DataSourceConfig ds = dataSourceService.get(datasourceId);
        DbDialect dialect = dialectFactory.get(ds.getDbType());
        try (Connection conn = dataSourceService.getConnection(datasourceId)) {
            return dialect.listDatabases(conn);
        }
    }

    public List<String> listSchemas(long datasourceId, String database) throws SQLException {
        DataSourceConfig ds = dataSourceService.get(datasourceId);
        DbDialect dialect = dialectFactory.get(ds.getDbType());
        try (Connection conn = dataSourceService.getConnection(datasourceId)) {
            dialect.useDatabase(conn, dataSourceService.resolveDatabase(datasourceId, database));
            return dialect.listSchemas(conn);
        }
    }

    public List<TableStat> listTables(long datasourceId, String database, String schema) throws SQLException {
        DataSourceConfig ds = dataSourceService.get(datasourceId);
        DbDialect dialect = dialectFactory.get(ds.getDbType());
        try (Connection conn = dataSourceService.getConnection(datasourceId)) {
            dialect.useDatabase(conn, dataSourceService.resolveDatabase(datasourceId, database));
            return dialect.listTables(conn, schema);
        }
    }

    /** 表列表页汇总:指定 schema 下所有基表的字段总数 */
    public long countColumns(long datasourceId, String database, String schema) throws SQLException {
        DataSourceConfig ds = dataSourceService.get(datasourceId);
        DbDialect dialect = dialectFactory.get(ds.getDbType());
        try (Connection conn = dataSourceService.getConnection(datasourceId)) {
            dialect.useDatabase(conn, dataSourceService.resolveDatabase(datasourceId, database));
            return dialect.countColumns(conn, schema);
        }
    }

    /** 表列表页:每张表最近一次 DONE 扫描的信息(任务 id + 完成时间),本地查询不连业务库 */
    public Map<String, ScanRepository.LatestScan> latestScanJobsByTable(long datasourceId, String database, String schema) {
        return scanRepository.latestDoneJobsByTable(datasourceId, database, schema);
    }

    /** 表列表页:运行中任务里每张未完成表的分段进度,本地查询不连业务库 */
    public Map<String, ScanRepository.RunningScan> runningScansByTable(long datasourceId, String database, String schema) {
        return scanRepository.runningScansByTable(datasourceId, database, schema);
    }

    /**
     * 库列表页概览:schema 列表 + 表数量 + 占用空间 + 各 schema 最近一次扫描。
     * schema 列表/表数量/占用空间走本地缓存(schema_stat):首次访问从业务库元数据拉取并落库,
     * 之后只读缓存,由扫描创建时按 schema 刷新;最近扫描信息本就来自本地 H2,不参与缓存。
     */
    public List<SchemaStat> listSchemaStats(long datasourceId, String database) throws SQLException {
        List<SchemaStatRepository.CachedStat> cached = schemaStatRepo.findAll(datasourceId, database);
        if (cached.isEmpty()) {
            cached = fetchAndCache(datasourceId, database);
        }
        Map<String, ScanRepository.JobRow> latest = scanRepository.latestJobsBySchema(datasourceId, database);
        List<SchemaStat> stats = new ArrayList<>(cached.size());
        for (SchemaStatRepository.CachedStat c : cached) {
            ScanRepository.JobRow job = latest.get(c.schemaName());
            LocalDateTime scanAt = null;
            if (job != null) {
                scanAt = job.finishedAt() != null ? job.finishedAt()
                        : job.startedAt() != null ? job.startedAt() : job.createdAt();
            }
            // 表数量为 0 时体积不是未知而是 0(聚合 SQL 对无表 schema 不产生分组,缓存为 null)
            Long sizeBytes = c.sizeBytes() != null ? c.sizeBytes()
                    : c.tableCount() != null && c.tableCount() == 0 ? 0L : null;
            stats.add(new SchemaStat(c.schemaName(), c.tableCount(), sizeBytes,
                    job == null ? null : job.status().name(), scanAt,
                    job == null ? null : job.id(),
                    job == null ? null : job.doneTables(),
                    job == null ? null : job.totalTables()));
        }
        return stats;
    }

    /** 首次访问:从业务库元数据拉取 schema 列表/表数量/占用空间并整体落缓存 */
    private List<SchemaStatRepository.CachedStat> fetchAndCache(long datasourceId, String database)
            throws SQLException {
        DataSourceConfig ds = dataSourceService.get(datasourceId);
        DbDialect dialect = dialectFactory.get(ds.getDbType());
        List<SchemaStatRepository.CachedStat> stats = new ArrayList<>();
        try (Connection conn = dataSourceService.getConnection(datasourceId)) {
            dialect.useDatabase(conn, dataSourceService.resolveDatabase(datasourceId, database));
            Map<String, Integer> counts = dialect.countTablesBySchema(conn);
            Map<String, Long> sizes = dialect.sumSizeBySchema(conn);
            for (String schema : dialect.listSchemas(conn)) {
                stats.add(new SchemaStatRepository.CachedStat(schema, counts.get(schema), sizes.get(schema)));
            }
        }
        schemaStatRepo.replaceAll(datasourceId, database, stats);
        return stats;
    }
}
