package com.example.dq.dialect;

import com.example.dq.model.ColumnMeta;
import com.example.dq.model.DbType;
import com.example.dq.model.NullRule;
import com.example.dq.model.Range;
import com.example.dq.model.TableStat;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;

/** 数据库方言:各库元数据查询与统计 SQL 的差异抽象 */
public interface DbDialect {

    DbType type();

    /** JDBC 驱动类名 */
    String driverClassName();

    /** 标识符引号 */
    String quote(String identifier);

    /** 探测数据库兼容模式(如 Kingbase 的 database_mode);无此概念的方言返回 null */
    default String detectDbMode(Connection conn) throws SQLException {
        return null;
    }

    /** 数据库列表;不支持多库选择的方言返回空 */
    default List<String> listDatabases(Connection conn) throws SQLException {
        return List.of();
    }

    /** 切换连接的目标数据库;不支持多库选择的方言忽略 */
    default void useDatabase(Connection conn, String database) throws SQLException {
    }

    /** 库/schema 列表 */
    List<String> listSchemas(Connection conn) throws SQLException;

    /** 各库/schema 的表数量(schema 名 → 表数);用于库列表页展示,单条聚合 SQL */
    default java.util.Map<String, Integer> countTablesBySchema(Connection conn) throws SQLException {
        return java.util.Map.of();
    }

    /** 各库/schema 的数据+索引总字节(schema 名 → 字节数);用于库列表页展示,单条聚合 SQL */
    default java.util.Map<String, Long> sumSizeBySchema(Connection conn) throws SQLException {
        return java.util.Map.of();
    }

    /** 表列表:表名 + 估算行数 + 数据索引总字节 */
    List<TableStat> listTables(Connection conn, String schema) throws SQLException;

    /** 指定库/schema 下所有基表的字段总数;用于表列表页汇总展示 */
    long countColumns(Connection conn, String schema) throws SQLException;

    /** 字段元数据 */
    List<ColumnMeta> listColumns(Connection conn, String schema, String table) throws SQLException;

    /** 选分段键:单列可比主键 > 主键首列 > 唯一索引首列;无则 null */
    ColumnMeta pickChunkKey(List<ColumnMeta> cols);

    /** 计算分段边界;无分段键时返回单段 Range.whole() */
    List<Range> planChunks(Connection conn, String schema, String table,
                           ColumnMeta chunkKey, long estRows, int chunksPerTable) throws SQLException;

    /**
     * 生成单条聚合统计 SQL。
     * 结果集第一列为 total,之后按 cols 顺序每列至多三列:null 数、(字符列)空串数、(命中规则列)规则命中数。
     * 列顺序与 {@link #selectorLayout} 一致。
     *
     * @param range      分段,null 或 Range.whole() 表示全表
     * @param chunkKey   分段键(range 非空时非空)
     * @param sampled    是否采样
     * @param sampleRows 采样行数
     * @param estRows    估算行数(采样比例用,可空)
     */
    String buildColumnStatsSql(String schema, String table, List<ColumnMeta> cols,
                               Range range, ColumnMeta chunkKey, List<NullRule> rules,
                               boolean sampled, long sampleRows, Long estRows);

    /** 统计 SQL 的选择项布局:每列依次包含哪些度量(null/empty/rule) */
    List<boolean[]> selectorLayout(List<ColumnMeta> cols, List<NullRule> rules);
}
