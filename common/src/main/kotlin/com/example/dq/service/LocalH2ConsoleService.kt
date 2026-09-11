package com.example.dq.service

import com.example.dq.model.SchemaColumn
import com.example.dq.model.TableStat
import org.h2.command.Parser
import org.h2.engine.SessionLocal
import org.h2.jdbc.JdbcConnection
import java.sql.Connection
import java.sql.SQLException
import javax.sql.DataSource

/**
 * 本地 H2 库(应用自身的 dqconfig 配置库)只读查询:SQL 控制台「本地 H2 库(只读)」入口专用。
 *
 * 复用应用主库连接池(借出后会话级切 schema,归还前恢复,防池化连接串库),
 * 结果集口径与 [SqlConsoleService] 完全一致(同一条 collectResult)。
 *
 * 只读保证是**语句级**的——H2 无会话级只读可借:`Connection.setReadOnly` 是空实现
 * (`isReadOnly()` 仍返回 false、写入照常成功)、`SET READONLY` 不支持、
 * `ACCESS_MODE_DATA=r` 只在首次打开库时生效(对已由应用以读写模式打开的库无效),以上均已实测。
 * 因此三重把关:
 * 1) 首关键字白名单(SELECT / WITH / EXPLAIN / SHOW / VALUES / TABLE);
 * 2) 用 H2 自身解析器判定语句只读性(挡住白名单看不出的写语句,如 `EXPLAIN ANALYZE DELETE`、
 *    会被真实执行的 `EXPLAIN PLAN FOR DELETE`,以及 `SELECT 1; DELETE …` 这类拼接写语句),
 *    判不出(内部结构变化)按不安全处理即拒绝,而不是放行;
 * 3) 校验在 `Statement.execute` 之前完成,不过校验的语句根本不会下发到库(库本身仍以读写模式打开,
 *    因为它就是应用自己的库)。
 * 注:H2 解析器属内部 API(h2 版本固定在 gradle/libs.versions.toml),升级 H2 时必须回归本类单测。
 */
class LocalH2ConsoleService(
    private val dataSource: DataSource,
    private val systemSettingsService: SystemSettingsService,
) {

    /** 执行只读查询;空白 SQL/非查询语句抛 IllegalArgumentException(壳层 400),执行错误抛 SQLException(壳层 502);
     *  schema 为选中的目标 schema(可空=库默认 schema) */
    @Throws(SQLException::class)
    fun execute(sql: String, schema: String? = null): SqlConsoleService.SqlExecuteResult {
        val trimmed = sql.trim()
        if (trimmed.isEmpty()) {
            throw IllegalArgumentException("SQL 不能为空")
        }
        val target = schema?.trim()?.takeIf { it.isNotEmpty() }
        val start = System.currentTimeMillis()
        dataSource.connection.use { conn ->
            // 先切 schema 再校验:H2 解析语句时会解析未限定名,H2 解析器只在目标 schema 下才认得未限定表名
            val prevSchema = if (target != null) switchSchema(conn, target) else null
            try {
                assertQueryOnly(conn, trimmed)
                conn.createStatement().use { stmt ->
                    // 与分段扫描/数据预览同口径的单条 SQL 超时(系统设置可改,回落配置文件默认值)
                    stmt.queryTimeout = systemSettingsService.scanSettings().statementTimeoutSeconds
                    stmt.execute(trimmed)
                    // 查询类语句正常必带结果集;兜底按更新语句返回(不掩盖异常情况)
                    val rs = stmt.resultSet
                    return if (rs == null) {
                        SqlConsoleService.updateResult(stmt.updateCount, System.currentTimeMillis() - start)
                    } else {
                        rs.use { SqlConsoleService.collectResult(it, System.currentTimeMillis() - start) }
                    }
                }
            } finally {
                // 归还池化连接前恢复会话默认 schema,防后续借用方(应用自身查询)串库
                if (prevSchema != null) runCatching { conn.schema = prevSchema }
            }
        }
    }

    /** 本地 H2 库 schema 清单(PUBLIC / INFORMATION_SCHEMA 等),供控制台库下拉 */
    @Throws(SQLException::class)
    fun listSchemas(): List<String> =
        dataSource.connection.use { conn ->
            val schemas = ArrayList<String>()
            conn.metaData.schemas.use { rs ->
                while (rs.next()) rs.getString("TABLE_SCHEM")?.let { schemas.add(it) }
            }
            schemas.distinct().sorted()
        }

    /** 指定 schema 的表/视图清单(表名 + 表注释),供控制台表名补全;schema 空=库默认 schema */
    @Throws(SQLException::class)
    fun listTables(schema: String?): List<TableStat> =
        dataSource.connection.use { conn ->
            val target = resolveSchema(conn, schema)
            val tables = ArrayList<TableStat>()
            conn.metaData.getTables(null, target, "%", arrayOf("TABLE", "VIEW")).use { rs ->
                while (rs.next()) {
                    // schemaPattern 是 LIKE 模式(含下划线的 schema 名会多匹配),按精确名再过滤一次
                    if (!sameSchema(rs.getString("TABLE_SCHEM"), target)) continue
                    tables.add(
                        TableStat(rs.getString("TABLE_NAME"), null, null, rs.getString("REMARKS") ?: "", "")
                    )
                }
            }
            tables
        }

    /** 指定 schema 的整库字段清单(所属表/字段名/展示类型/备注),供控制台字段补全 */
    @Throws(SQLException::class)
    fun listColumns(schema: String?): List<SchemaColumn> =
        dataSource.connection.use { conn ->
            val target = resolveSchema(conn, schema)
            val columns = ArrayList<SchemaColumn>()
            conn.metaData.getColumns(null, target, "%", "%").use { rs ->
                while (rs.next()) {
                    if (!sameSchema(rs.getString("TABLE_SCHEM"), target)) continue
                    columns.add(
                        SchemaColumn(
                            table = rs.getString("TABLE_NAME"),
                            name = rs.getString("COLUMN_NAME"),
                            type = displayType(
                                rs.getString("TYPE_NAME"),
                                rs.getInt("COLUMN_SIZE"),
                                rs.getInt("DECIMAL_DIGITS"),
                            ),
                            comment = rs.getString("REMARKS") ?: "",
                        )
                    )
                }
            }
            columns
        }

    /**
     * 只读校验收口:首关键字白名单 + H2 解析器只读判定,任一不过即抛 IllegalArgumentException(400)。
     * 解析不出只读性时按不安全处理(拒绝执行),避免只读保证在 H2 内部结构变化后静默失效
     */
    private fun assertQueryOnly(conn: Connection, sql: String) {
        val keyword = firstKeyword(sql)
        if (keyword !in READ_ONLY_KEYWORDS) {
            throw if (keyword.isEmpty()) {
                IllegalArgumentException(READ_ONLY_HINT)
            } else {
                IllegalArgumentException("$READ_ONLY_HINT;不支持 $keyword 语句")
            }
        }
        val session = h2Session(conn)
        val readOnly = if (session == null) {
            // 拿不到 H2 会话(包装类型变化等)按不安全处理:拒绝执行,不让只读保证静默失效
            false
        } else {
            try {
                Parser(session).prepareCommand(sql).isReadOnly
            } catch (e: SQLException) {
                // SQL 语法错误:与执行期同口径抛 SQLException(壳层 502)
                throw e
            } catch (e: RuntimeException) {
                // 解析期异常(H2 的 DbException 等)转 SQLException,同样不落到语句执行
                throw SQLException(e.message, e)
            }
        }
        if (!readOnly) {
            throw IllegalArgumentException("$READ_ONLY_HINT;该语句会修改数据,已拒绝执行")
        }
    }

    /** 取 H2 内部会话:连接池借出的是 Hikari 代理,H2 解析器要的是原始连接,故先 unwrap;取不到返回 null */
    private fun h2Session(conn: Connection): SessionLocal? =
        runCatching { conn.unwrap(JdbcConnection::class.java).session as? SessionLocal }.getOrNull()

    /** 语句首关键字(跳过前导空白与 `--` `//` `/* */` 注释),取不到返回空串 */
    private fun firstKeyword(sql: String): String {
        var i = 0
        while (i < sql.length) {
            if (sql[i].isWhitespace()) {
                i++
            } else if (sql.startsWith("--", i) || sql.startsWith("//", i)) {
                val nl = sql.indexOf('\n', i)
                i = if (nl < 0) sql.length else nl + 1
            } else if (sql.startsWith("/*", i)) {
                val end = sql.indexOf("*/", i + 2)
                i = if (end < 0) sql.length else end + 2
            } else {
                break
            }
        }
        return Regex("^[A-Za-z_]+").find(sql.substring(i))?.value?.uppercase() ?: ""
    }

    /** 会话级切到目标 schema 并返回切换前的值(供恢复);旧值获取失败返回 null(跳过恢复) */
    @Throws(SQLException::class)
    private fun switchSchema(conn: Connection, target: String): String? {
        val prev = runCatching { conn.schema }.getOrNull()
        conn.schema = target
        return prev?.takeIf { it != target }
    }

    /** 目标 schema:显式指定优先,否则取连接当前默认 schema;取不到(null)表示不过滤 */
    private fun resolveSchema(conn: Connection, schema: String?): String? =
        schema?.trim()?.takeIf { it.isNotEmpty() }
            ?: runCatching { conn.schema }.getOrNull()?.takeIf { it.isNotEmpty() }

    /** schema 名精确比对(schemaPattern 为 LIKE 模式,需再滤一层);expected 为 null 表示未限定 */
    private fun sameSchema(actual: String?, expected: String?): Boolean =
        expected == null || actual.equals(expected, ignoreCase = true)

    /** 展示类型:数值带精度标度、字符类带长度,其余只报类型名(JDBC 的 COLUMN_SIZE 对整型是位宽,
     *  而 TIMESTAMP 等类型的 DECIMAL_DIGITS 是小数秒精度而非标度,都不宜直接当类型参数展示) */
    private fun displayType(typeName: String, size: Int, scale: Int): String = when {
        typeName.isBlank() -> typeName
        scale > 0 && NUMERIC_TYPES.any { typeName.contains(it, ignoreCase = true) } -> "$typeName($size,$scale)"
        size > 0 && typeName.contains("CHAR", ignoreCase = true) -> "$typeName($size)"
        else -> typeName
    }

    companion object {
        /** 只读白名单:查询类语句的首关键字 */
        private val READ_ONLY_KEYWORDS = setOf("SELECT", "WITH", "EXPLAIN", "SHOW", "VALUES", "TABLE")

        /** 带精度标度的数值类型(H2 报 DECIMAL/NUMERIC) */
        private val NUMERIC_TYPES = listOf("DECIMAL", "NUMERIC")

        /** 只读拒绝提示:同一份文案供白名单与解析器两条拒绝路径使用 */
        private const val READ_ONLY_HINT =
            "本地 H2 库为只读,仅支持查询语句(SELECT / WITH / EXPLAIN / SHOW / VALUES / TABLE)"
    }
}
