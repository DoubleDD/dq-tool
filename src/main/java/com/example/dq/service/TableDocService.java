package com.example.dq.service;

import com.example.dq.dialect.DbDialect;
import com.example.dq.dialect.DialectFactory;
import com.example.dq.model.ColumnMeta;
import com.example.dq.model.DataSourceConfig;
import com.example.dq.model.TableDocView;
import com.example.dq.model.TableStat;
import com.example.dq.repository.TableDocRepository;
import org.springframework.stereotype.Service;

import java.sql.Connection;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/** AI 表说明:生成(实时查目标库元数据 → 大模型)与查询(本地 H2) */
@Service
public class TableDocService {

    private final TableDocRepository repository;
    private final AiConfigService aiConfigService;
    private final AiService aiService;
    private final DataSourceService dataSourceService;
    private final DialectFactory dialectFactory;

    public TableDocService(TableDocRepository repository, AiConfigService aiConfigService, AiService aiService,
                           DataSourceService dataSourceService, DialectFactory dialectFactory) {
        this.repository = repository;
        this.aiConfigService = aiConfigService;
        this.aiService = aiService;
        this.dataSourceService = dataSourceService;
        this.dialectFactory = dialectFactory;
    }

    /** 表列表页展示用:表名 -> 说明,本地查询不连业务库 */
    public Map<String, String> list(long datasourceId, String database, String schema) {
        return repository.findBySchema(datasourceId, normalizeDb(database), schema);
    }

    /** 生成单表说明并落库;库差异只经 dialect,无库特定分支 */
    public TableDocView generate(long datasourceId, String database, String schema, String table) throws SQLException {
        AiConfigService.Config config = aiConfigService.requireConfig();
        DataSourceConfig ds = dataSourceService.get(datasourceId);
        DbDialect dialect = dialectFactory.get(ds.getDbType());
        TableStat stat;
        List<ColumnMeta> columns;
        try (Connection conn = dataSourceService.getConnection(datasourceId)) {
            dialect.useDatabase(conn, dataSourceService.resolveDatabase(datasourceId, database));
            stat = dialect.listTables(conn, schema).stream()
                    .filter(t -> t.name().equals(table))
                    .findFirst()
                    .orElseThrow(() -> new IllegalArgumentException("表不存在:" + table));
            columns = dialect.listColumns(conn, schema, table);
        }
        String description = aiService.describeTable(config, stat, columns);
        repository.upsert(datasourceId, normalizeDb(database), schema, table, description, config.model());
        return new TableDocView(table, description, config.model(), LocalDateTime.now());
    }

    /** 手动编辑描述(只改文字,保留生成模型标记) */
    public TableDocView update(long datasourceId, String database, String schema, String table, String description) {
        if (description == null || description.isBlank()) {
            throw new IllegalArgumentException("描述不能为空");
        }
        repository.updateDescription(datasourceId, normalizeDb(database), schema, table, description.trim());
        return new TableDocView(table, description.trim(), null, LocalDateTime.now());
    }

    /** 无库概念的方言 db 为 null,统一存空串保证唯一键 */
    private static String normalizeDb(String database) {
        return database == null ? "" : database;
    }
}
