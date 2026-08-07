package com.example.dq.dialect

import com.example.dq.model.ColumnMeta
import com.example.dq.model.NullRule
import com.example.dq.model.Range

import java.math.BigDecimal
import java.math.MathContext
import java.sql.Connection
import java.sql.DatabaseMetaData
import java.sql.SQLException
import java.util.regex.Pattern

/** 各方言的通用实现:元数据读取、分段规划、统计 SQL 模板 */
abstract class AbstractDialect : DbDialect {

    companion object {
        private val NUMERIC_LITERAL: Pattern = Pattern.compile("-?\\d+(\\.\\d+)?")

        /** 每条统计 SQL 最多包含的列数(防 SQL 过长) */
        internal const val MAX_COLS_PER_SQL = 80
    }

    /** MySQL 系用 catalog 定位库;PG/DM 系用 schema */
    protected open fun catalogBased(): Boolean = false

    /** 执行 "分组键, COUNT(*)" 两列聚合查询,供 countTablesBySchema 各实现复用 */
    @Throws(SQLException::class)
    protected fun queryCountByGroup(conn: Connection, sql: String): MutableMap<String, Int> {
        val counts = LinkedHashMap<String, Int>()
        conn.createStatement().use { st ->
            st.executeQuery(sql).use { rs ->
                while (rs.next()) {
                    counts[rs.getString(1)] = rs.getInt(2)
                }
            }
        }
        return counts
    }

    /** 执行 "分组键, SUM(字节)" 两列聚合查询,供 sumSizeBySchema 各实现复用 */
    @Throws(SQLException::class)
    protected fun queryLongByGroup(conn: Connection, sql: String): MutableMap<String, Long> {
        val sums = LinkedHashMap<String, Long>()
        conn.createStatement().use { st ->
            st.executeQuery(sql).use { rs ->
                while (rs.next()) {
                    sums[rs.getString(1)] = rs.getLong(2)
                }
            }
        }
        return sums
    }

    @Throws(SQLException::class)
    override fun listColumns(conn: Connection, schema: String, table: String): List<ColumnMeta> {
        val meta = conn.metaData
        val catalog = if (catalogBased()) schema else null
        val schemaPattern = if (catalogBased()) null else schema

        // 主键列及序号
        val pk = LinkedHashMap<String, Int>()
        meta.getPrimaryKeys(catalog, schemaPattern, table).use { rs ->
            while (rs.next()) {
                pk[rs.getString("COLUMN_NAME")] = rs.getShort("KEY_SEQ").toInt()
            }
        }

        // 唯一索引首列
        val uniqueFirst = HashMap<String, String>() // indexName -> firstColumn
        val uniqueOrd = HashMap<String, Int>()
        try {
            meta.getIndexInfo(catalog, schemaPattern, table, true, false).use { rs ->
                while (rs.next()) {
                    val idx = rs.getString("INDEX_NAME")
                    val col = rs.getString("COLUMN_NAME")
                    if (idx == null || col == null) continue
                    val ord = rs.getInt("ORDINAL_POSITION")
                    val prev = uniqueOrd[idx]
                    if (prev == null || ord < prev) {
                        uniqueOrd[idx] = ord
                        uniqueFirst[idx] = col
                    }
                }
            }
        } catch (ignored: SQLException) {
            // 部分驱动不支持 getIndexInfo,忽略,仅影响分段键选择
        }

        val cols = ArrayList<ColumnMeta>()
        meta.getColumns(catalog, schemaPattern, table, null).use { rs ->
            while (rs.next()) {
                val name = rs.getString("COLUMN_NAME")
                val typeName = rs.getString("TYPE_NAME")
                val jdbcType = rs.getInt("DATA_TYPE")
                val pkSeq = pk[name]
                val uniqueFirstCol = uniqueFirst.containsValue(name)
                val nullable = rs.getInt("NULLABLE") != DatabaseMetaData.columnNoNulls
                val defaultValue = rs.getString("COLUMN_DEF")
                val comment = rs.getString("REMARKS")
                val size = rs.getInt("COLUMN_SIZE")
                var digits = 0
                try {
                    digits = rs.getInt("DECIMAL_DIGITS")
                } catch (ignored: SQLException) {
                    // 部分驱动不支持 DECIMAL_DIGITS
                }
                cols.add(ColumnMeta(name, typeName, displayType(typeName, size, digits), jdbcType,
                        nullable, defaultValue, comment ?: "",
                        pkSeq != null, pkSeq ?: 0, uniqueFirstCol))
            }
        }
        return cols
    }

    /** 统计指定库/schema 下所有基表的字段总数;只算基表(与 listTables 一致),不含视图 */
    @Throws(SQLException::class)
    override fun countColumns(conn: Connection, schema: String): Long {
        val meta = conn.metaData
        val catalog = if (catalogBased()) schema else null
        val schemaPattern = if (catalogBased()) null else schema
        val baseTables = HashSet<String>()
        meta.getTables(catalog, schemaPattern, null, arrayOf("TABLE")).use { rs ->
            while (rs.next()) {
                baseTables.add(rs.getString("TABLE_NAME"))
            }
        }
        var count = 0L
        meta.getColumns(catalog, schemaPattern, null, null).use { rs ->
            while (rs.next()) {
                if (baseTables.contains(rs.getString("TABLE_NAME"))) {
                    count++
                }
            }
        }
        return count
    }

    /** 由类型名+长度+小数位拼展示类型:varchar(50)、decimal(10,2)、bigint */
    protected open fun displayType(typeName: String?, size: Int, decimalDigits: Int): String {
        if (typeName == null) {
            return ""
        }
        if (decimalDigits > 0) {
            return "$typeName($size,$decimalDigits)"
        }
        if (size > 0 && isCharOrBinaryType(typeName)) {
            return "$typeName($size)"
        }
        return typeName
    }

    private fun isCharOrBinaryType(typeName: String): Boolean {
        val t = typeName.lowercase()
        return t.contains("char") || t.contains("text") || t.contains("clob")
                || t.contains("binary") || t.contains("blob") || t.contains("varchar")
    }

    override fun pickChunkKey(cols: List<ColumnMeta>): ColumnMeta? {
        val pkCols = cols.filter { it.primaryKey }.sortedBy { it.pkSeq }
        if (pkCols.size == 1 && pkCols[0].isComparable()) {
            return pkCols[0]
        }
        if (pkCols.isNotEmpty() && pkCols[0].isComparable()) {
            return pkCols[0]
        }
        return cols.firstOrNull { it.uniqueIndexFirst && it.isComparable() }
    }

    @Throws(SQLException::class)
    override fun planChunks(conn: Connection, schema: String, table: String,
                            chunkKey: ColumnMeta?, estRows: Long, chunksPerTable: Int): List<Range> {
        if (chunkKey == null || estRows <= 0) {
            return listOf(Range.whole())
        }
        val qTable = qualifiedTable(schema, table)
        val qKey = quote(chunkKey.name)

        val ranges = ArrayList<Range>()
        if (chunkKey.isNumeric()) {
            conn.createStatement().use { st ->
                st.executeQuery("SELECT MIN($qKey), MAX($qKey) FROM $qTable").use { rs ->
                    if (rs.next() && rs.getObject(1) != null) {
                        val min = rs.getBigDecimal(1)
                        val max = rs.getBigDecimal(2)
                        ranges.addAll(splitNumeric(min, max, chunksPerTable))
                    }
                }
            }
        } else {
            // 非数值键:按估算行数步进取边界值
            val step = maxOf(1L, estRows / chunksPerTable)
            var prev: String? = null
            for (i in 1 until chunksPerTable) {
                val offset = step * i
                var boundary: String? = null
                conn.createStatement().use { st ->
                    st.executeQuery(boundaryQuery(conn, qTable, qKey, offset)).use { rs ->
                        if (rs.next()) {
                            boundary = rs.getString(1)
                        }
                    }
                }
                if (boundary == null) {
                    break // 没那么多行了
                }
                if (prev == null || prev != boundary) {
                    ranges.add(Range(prev, boundary, false))
                    prev = boundary
                }
            }
            if (ranges.isNotEmpty()) {
                ranges.add(Range(prev, null, false))
            }
        }

        if (ranges.isEmpty()) {
            ranges.add(Range.whole())
        }

        // 分段键为 NULL 的行需要补充分段
        conn.createStatement().use { st ->
            st.executeQuery(nullChunkProbeQuery(conn, qTable, qKey)).use { rs ->
                if (rs.next()) {
                    ranges.add(Range(null, null, true))
                }
            }
        }
        return ranges
    }

    private fun splitNumeric(min: BigDecimal, max: BigDecimal, n: Int): List<Range> {
        val ranges = ArrayList<Range>()
        if (min >= max) {
            ranges.add(Range(min.toPlainString(), null, false))
            return ranges
        }
        var step = max.subtract(min).divide(BigDecimal.valueOf(n.toLong()), MathContext.DECIMAL64)
        if (step < BigDecimal.ONE && min.stripTrailingZeros().scale() <= 0) {
            step = BigDecimal.ONE // 整型键保证每段至少跨 1
        }
        var cur = min
        for (i in 0 until n) {
            val next = cur.add(step)
            val last = i == n - 1 || next >= max
            ranges.add(Range(cur.toPlainString(), if (last) null else next.toPlainString(), false))
            if (last) {
                break
            }
            cur = next
        }
        return ranges
    }

    override fun selectorLayout(cols: List<ColumnMeta>, rules: List<NullRule>?): List<BooleanArray> {
        val layout = ArrayList<BooleanArray>(cols.size)
        for (c in cols) {
            val hasRule = rules != null && rules.any { it.matches(c.name) }
            layout.add(booleanArrayOf(c.isCharacter(), hasRule)) // null 计数恒有
        }
        return layout
    }

    override fun buildColumnStatsSql(schema: String, table: String, cols: List<ColumnMeta>,
                                     range: Range?, chunkKey: ColumnMeta?, rules: List<NullRule>?,
                                     sampled: Boolean, sampleRows: Long, estRows: Long?): String {
        val select = StringBuilder("SELECT COUNT(*) AS total")
        for (i in cols.indices) {
            val c = cols[i]
            val q = quote(c.name)
            select.append(", SUM(CASE WHEN ").append(q).append(" IS NULL THEN 1 ELSE 0 END) AS c").append(i).append("_null")
            if (c.isCharacter()) {
                select.append(", SUM(CASE WHEN TRIM(").append(q).append(") = '' THEN 1 ELSE 0 END) AS c").append(i).append("_empty")
            }
            if (rules != null) {
                val values = rules.filter { it.matches(c.name) }
                        .flatMap { it.values }.distinct()
                if (values.isNotEmpty()) {
                    select.append(", SUM(CASE WHEN ").append(q).append(" IN (")
                    for (j in values.indices) {
                        if (j > 0) select.append(", ")
                        select.append(literal(values[j], c))
                    }
                    select.append(") THEN 1 ELSE 0 END) AS c").append(i).append("_rule")
                }
            }
        }

        val from = if (sampled)
            sampledFrom(qualifiedTable(schema, table), sampleRows, estRows)
        else
            qualifiedTable(schema, table)

        val sql = StringBuilder(select).append(" FROM ").append(from)

        val predicates = ArrayList<String>()
        if (range != null && chunkKey != null && !(range.start == null && range.end == null && !range.nullChunk)) {
            val qKey = quote(chunkKey.name)
            if (range.nullChunk) {
                predicates.add("$qKey IS NULL")
            } else {
                if (range.start != null) {
                    predicates.add(qKey + " >= " + literal(range.start, chunkKey))
                }
                if (range.end != null) {
                    predicates.add(qKey + " < " + literal(range.end, chunkKey))
                }
            }
        }
        if (predicates.isNotEmpty()) {
            sql.append(" WHERE ").append(predicates.joinToString(" AND "))
        }
        if (sampled) {
            sql.append(sampledLimit(sampleRows))
        }
        return sql.toString()
    }

    /** 采样 FROM 子句;默认不特殊处理(配合 sampledLimit 退化为 LIMIT 采样,结果有偏) */
    protected open fun sampledFrom(qualifiedTable: String, sampleRows: Long, estRows: Long?): String {
        return qualifiedTable
    }

    /** 采样 LIMIT 后缀;已用 TABLESAMPLE 的方言返回空串 */
    protected open fun sampledLimit(sampleRows: Long): String {
        return limitClause(sampleRows)
    }

    override fun sampleRowsSql(schema: String, table: String, columns: List<String>, limit: Int): String {
        val cols = if (columns.isEmpty()) "*" else columns.joinToString(", ") { quote(it) }
        val from = if (schema.isBlank()) quote(table) else qualifiedTable(schema, table)
        return "SELECT $cols FROM $from" + limitClause(limit.toLong())
    }

    /** LIMIT 子句方言 */
    protected open fun limitClause(n: Long): String {
        return " LIMIT $n"
    }

    /** 边界值查询的分页后缀(带 ORDER BY 的查询) */
    protected open fun boundarySuffix(offset: Long): String {
        return " LIMIT 1 OFFSET $offset"
    }

    /** 非数值分段键的边界值查询:取按键排序后第 offset 行的值 */
    @Throws(SQLException::class)
    protected open fun boundaryQuery(conn: Connection, qTable: String, qKey: String, offset: Long): String {
        return "SELECT " + qKey + " FROM " + qTable +
                " WHERE " + qKey + " IS NOT NULL ORDER BY " + qKey +
                boundarySuffix(offset)
    }

    /** 分段键 NULL 行探测查询 */
    @Throws(SQLException::class)
    protected open fun nullChunkProbeQuery(conn: Connection, qTable: String, qKey: String): String {
        return "SELECT 1 FROM " + qTable + " WHERE " + qKey + " IS NULL" +
                " ORDER BY " + qKey + limitClause(1)
    }

    /** 字面量渲染:数值列且值合法时原样输出,否则按字符串转义 */
    protected open fun literal(value: String?, col: ColumnMeta): String {
        if (col.isNumeric() && value != null && NUMERIC_LITERAL.matcher(value).matches()) {
            return value
        }
        return quoteString(value!!)
    }

    protected open fun quoteString(value: String): String {
        return "'" + value.replace("'", "''") + "'"
    }

    protected open fun qualifiedTable(schema: String, table: String): String {
        return quote(schema) + "." + quote(table)
    }
}
