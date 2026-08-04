package com.example.dq.service;

import com.example.dq.dialect.DbDialect;
import com.example.dq.dialect.DialectFactory;
import com.example.dq.model.DataSourceConfig;
import com.example.dq.model.TableStat;
import org.springframework.stereotype.Service;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;

/** 库/表元数据查询(同步、快速路径) */
@Service
public class MetadataService {

    private final DataSourceService dataSourceService;
    private final DialectFactory dialectFactory;

    public MetadataService(DataSourceService dataSourceService, DialectFactory dialectFactory) {
        this.dataSourceService = dataSourceService;
        this.dialectFactory = dialectFactory;
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
}
