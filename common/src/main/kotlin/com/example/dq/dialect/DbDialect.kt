package com.example.dq.dialect

import com.example.dq.model.ColumnMeta
import com.example.dq.model.DbType
import com.example.dq.model.NullRule
import com.example.dq.model.Range
import com.example.dq.model.TableStat

import java.sql.Connection
import java.sql.SQLException

/** 数据库方言:各库元数据查询与统计 SQL 的差异抽象 */
interface DbDialect {

    fun type(): DbType

    /** JDBC 驱动类名 */
    fun driverClassName(): String

    /** 标识符引号 */
    fun quote(identifier: String): String

    /** 探测数据库兼容模式(如 Kingbase 的 database_mode);无此概念的方言返回 null */
    @Throws(SQLException::class)
    fun detectDbMode(conn: Connection): String? = null

    /** 数据库列表;不支持多库选择的方言返回空 */
    @Throws(SQLException::class)
    fun listDatabases(conn: Connection): List<String> = emptyList()

    /** 是否多库方言(如 SQL Server 先选库再选 schema);false 时 listSchemas 的 schema 即用户眼中的「库」 */
    fun supportsMultiDatabase(): Boolean = false

    /** 切换连接的目标数据库;不支持多库选择的方言忽略 */
    @Throws(SQLException::class)
    fun useDatabase(conn: Connection, database: String?) {
    }

    /** 库/schema 列表 */
    @Throws(SQLException::class)
    fun listSchemas(conn: Connection): List<String>

    /** 各库/schema 的表数量(schema 名 → 表数);用于库列表页展示,单条聚合 SQL */
    @Throws(SQLException::class)
    fun countTablesBySchema(conn: Connection): Map<String, Int> = emptyMap()

    /** 各库/schema 的数据+索引总字节(schema 名 → 字节数);用于库列表页展示,单条聚合 SQL */
    @Throws(SQLException::class)
    fun sumSizeBySchema(conn: Connection): Map<String, Long> = emptyMap()

    /** 表列表:表名 + 估算行数 + 数据索引总字节 */
    @Throws(SQLException::class)
    fun listTables(conn: Connection, schema: String): List<TableStat>

    /** 指定库/schema 下所有基表的字段总数;用于表列表页汇总展示 */
    @Throws(SQLException::class)
    fun countColumns(conn: Connection, schema: String): Long

    /** 字段元数据 */
    @Throws(SQLException::class)
    fun listColumns(conn: Connection, schema: String, table: String): List<ColumnMeta>

    /** 选分段键:单列可比主键 > 主键首列 > 唯一索引首列;无则 null */
    fun pickChunkKey(cols: List<ColumnMeta>): ColumnMeta?

    /** 计算分段边界;无分段键时返回单段 Range.whole() */
    @Throws(SQLException::class)
    fun planChunks(conn: Connection, schema: String, table: String,
                   chunkKey: ColumnMeta?, estRows: Long, chunksPerTable: Int): List<Range>

    /**
     * 生成单条聚合统计 SQL。
     * 结果集第一列为 total,之后按 cols 顺序每列至多三列:null 数、(字符列)空串数、(命中规则列)规则命中数。
     * 列顺序与 [selectorLayout] 一致。
     *
     * @param range      分段,null 或 Range.whole() 表示全表
     * @param chunkKey   分段键(range 非空时非空)
     * @param sampled    是否采样
     * @param sampleRows 采样行数
     * @param estRows    估算行数(采样比例用,可空)
     */
    fun buildColumnStatsSql(schema: String, table: String, cols: List<ColumnMeta>,
                            range: Range?, chunkKey: ColumnMeta?, rules: List<NullRule>?,
                            sampled: Boolean, sampleRows: Long, estRows: Long?): String

    /** 统计 SQL 的选择项布局:每列依次包含哪些度量(null/empty/rule) */
    fun selectorLayout(cols: List<ColumnMeta>, rules: List<NullRule>?): List<BooleanArray>

    /** 抽样数据 SQL:取前 limit 行的指定列(空则全部列);不加 ORDER BY,任意 N 行即可(AI 自动打标的上下文用) */
    fun sampleRowsSql(schema: String, table: String, columns: List<String>, limit: Int): String
}
