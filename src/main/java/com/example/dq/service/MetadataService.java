package com.example.dq.service;

import com.example.dq.dialect.DbDialect;
import com.example.dq.dialect.DialectFactory;
import com.example.dq.model.DataSourceConfig;
import com.example.dq.model.SchemaStat;
import com.example.dq.model.TableStat;
import com.example.dq.repository.ScanRepository;
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

    public MetadataService(DataSourceService dataSourceService, DialectFactory dialectFactory,
                           ScanRepository scanRepository) {
        this.dataSourceService = dataSourceService;
        this.dialectFactory = dialectFactory;
        this.scanRepository = scanRepository;
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

    /** 表列表页:每张表最近一次 DONE 扫描的信息(任务 id + 完成时间),本地查询不连业务库 */
    public Map<String, ScanRepository.LatestScan> latestScanJobsByTable(long datasourceId, String database, String schema) {
        return scanRepository.latestDoneJobsByTable(datasourceId, database, schema);
    }

    /** 表列表页:运行中任务里每张未完成表的分段进度,本地查询不连业务库 */
    public Map<String, ScanRepository.RunningScan> runningScansByTable(long datasourceId, String database, String schema) {
        return scanRepository.runningScansByTable(datasourceId, database, schema);
    }

    /** 库列表页概览:schema 列表 + 表数量 + 各 schema 最近一次扫描 */
    public List<SchemaStat> listSchemaStats(long datasourceId, String database) throws SQLException {
        DataSourceConfig ds = dataSourceService.get(datasourceId);
        DbDialect dialect = dialectFactory.get(ds.getDbType());
        List<String> schemas;
        Map<String, Integer> counts;
        try (Connection conn = dataSourceService.getConnection(datasourceId)) {
            dialect.useDatabase(conn, dataSourceService.resolveDatabase(datasourceId, database));
            schemas = dialect.listSchemas(conn);
            counts = dialect.countTablesBySchema(conn);
        }
        Map<String, ScanRepository.JobRow> latest = scanRepository.latestJobsBySchema(datasourceId, database);
        List<SchemaStat> stats = new ArrayList<>(schemas.size());
        for (String schema : schemas) {
            ScanRepository.JobRow job = latest.get(schema);
            LocalDateTime scanAt = null;
            if (job != null) {
                scanAt = job.finishedAt() != null ? job.finishedAt()
                        : job.startedAt() != null ? job.startedAt() : job.createdAt();
            }
            stats.add(new SchemaStat(schema, counts.get(schema),
                    job == null ? null : job.status().name(), scanAt,
                    job == null ? null : job.id(),
                    job == null ? null : job.doneTables(),
                    job == null ? null : job.totalTables()));
        }
        return stats;
    }
}
