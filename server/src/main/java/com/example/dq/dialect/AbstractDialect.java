package com.example.dq.dialect;

import com.example.dq.model.ColumnMeta;
import com.example.dq.model.NullRule;
import com.example.dq.model.Range;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.math.MathContext;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Pattern;

/** 各方言的通用实现:元数据读取、分段规划、统计 SQL 模板 */
public abstract class AbstractDialect implements DbDialect {

    private static final Logger log = LoggerFactory.getLogger(AbstractDialect.class);

    private static final Pattern NUMERIC_LITERAL = Pattern.compile("-?\\d+(\\.\\d+)?");
    /** 每条统计 SQL 最多包含的列数(防 SQL 过长) */
    static final int MAX_COLS_PER_SQL = 80;

    /** MySQL 系用 catalog 定位库;PG/DM 系用 schema */
    protected boolean catalogBased() {
        return false;
    }

    /** 执行 "分组键, COUNT(*)" 两列聚合查询,供 countTablesBySchema 各实现复用 */
    protected Map<String, Integer> queryCountByGroup(Connection conn, String sql) throws SQLException {
        Map<String, Integer> counts = new LinkedHashMap<>();
        try (Statement st = conn.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            while (rs.next()) {
                counts.put(rs.getString(1), rs.getInt(2));
            }
        }
        return counts;
    }

    /** 执行 "分组键, SUM(字节)" 两列聚合查询,供 sumSizeBySchema 各实现复用 */
    protected Map<String, Long> queryLongByGroup(Connection conn, String sql) throws SQLException {
        Map<String, Long> sums = new LinkedHashMap<>();
        try (Statement st = conn.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            while (rs.next()) {
                sums.put(rs.getString(1), rs.getLong(2));
            }
        }
        return sums;
    }

    @Override
    public List<ColumnMeta> listColumns(Connection conn, String schema, String table) throws SQLException {
        DatabaseMetaData meta = conn.getMetaData();
        String catalog = catalogBased() ? schema : null;
        String schemaPattern = catalogBased() ? null : schema;

        // 主键列及序号
        Map<String, Integer> pk = new LinkedHashMap<>();
        try (ResultSet rs = meta.getPrimaryKeys(catalog, schemaPattern, table)) {
            while (rs.next()) {
                pk.put(rs.getString("COLUMN_NAME"), (int) rs.getShort("KEY_SEQ"));
            }
        }

        // 唯一索引首列
        Map<String, String> uniqueFirst = new HashMap<>(); // indexName -> firstColumn
        Map<String, Integer> uniqueOrd = new HashMap<>();
        try (ResultSet rs = meta.getIndexInfo(catalog, schemaPattern, table, true, false)) {
            while (rs.next()) {
                String idx = rs.getString("INDEX_NAME");
                String col = rs.getString("COLUMN_NAME");
                if (idx == null || col == null) continue;
                int ord = rs.getInt("ORDINAL_POSITION");
                Integer prev = uniqueOrd.get(idx);
                if (prev == null || ord < prev) {
                    uniqueOrd.put(idx, ord);
                    uniqueFirst.put(idx, col);
                }
            }
        } catch (SQLException e) {
            // 部分驱动不支持 getIndexInfo,忽略,仅影响分段键选择
            log.debug("读取索引元数据失败,唯一键分段键可能不可用 table={}: {}", table, e.getMessage());
        }

        List<ColumnMeta> cols = new ArrayList<>();
        try (ResultSet rs = meta.getColumns(catalog, schemaPattern, table, null)) {
            while (rs.next()) {
                String name = rs.getString("COLUMN_NAME");
                String typeName = rs.getString("TYPE_NAME");
                int jdbcType = rs.getInt("DATA_TYPE");
                Integer pkSeq = pk.get(name);
                boolean uniqueFirstCol = uniqueFirst.containsValue(name);
                boolean nullable = rs.getInt("NULLABLE") != DatabaseMetaData.columnNoNulls;
                String defaultValue = rs.getString("COLUMN_DEF");
                String comment = rs.getString("REMARKS");
                int size = rs.getInt("COLUMN_SIZE");
                int digits = 0;
                try {
                    digits = rs.getInt("DECIMAL_DIGITS");
                } catch (SQLException e) {
                    // 部分驱动不支持 DECIMAL_DIGITS
                    log.debug("读取 DECIMAL_DIGITS 失败,按 0 处理 column={}: {}", name, e.getMessage());
                }
                cols.add(new ColumnMeta(name, typeName, displayType(typeName, size, digits), jdbcType,
                        nullable, defaultValue, comment == null ? "" : comment,
                        pkSeq != null, pkSeq != null ? pkSeq : 0, uniqueFirstCol));
            }
        }
        return cols;
    }

    /** 统计指定库/schema 下所有基表的字段总数;只算基表(与 listTables 一致),不含视图 */
    @Override
    public long countColumns(Connection conn, String schema) throws SQLException {
        DatabaseMetaData meta = conn.getMetaData();
        String catalog = catalogBased() ? schema : null;
        String schemaPattern = catalogBased() ? null : schema;
        Set<String> baseTables = new HashSet<>();
        try (ResultSet rs = meta.getTables(catalog, schemaPattern, null, new String[]{"TABLE"})) {
            while (rs.next()) {
                baseTables.add(rs.getString("TABLE_NAME"));
            }
        }
        long count = 0;
        try (ResultSet rs = meta.getColumns(catalog, schemaPattern, null, null)) {
            while (rs.next()) {
                if (baseTables.contains(rs.getString("TABLE_NAME"))) {
                    count++;
                }
            }
        }
        return count;
    }

    /** 由类型名+长度+小数位拼展示类型:varchar(50)、decimal(10,2)、bigint */
    protected String displayType(String typeName, int size, int decimalDigits) {
        if (typeName == null) {
            return "";
        }
        if (decimalDigits > 0) {
            return typeName + "(" + size + "," + decimalDigits + ")";
        }
        if (size > 0 && isCharOrBinaryType(typeName)) {
            return typeName + "(" + size + ")";
        }
        return typeName;
    }

    private boolean isCharOrBinaryType(String typeName) {
        String t = typeName.toLowerCase();
        return t.contains("char") || t.contains("text") || t.contains("clob")
                || t.contains("binary") || t.contains("blob") || t.contains("varchar");
    }

    @Override
    public ColumnMeta pickChunkKey(List<ColumnMeta> cols) {
        List<ColumnMeta> pkCols = cols.stream().filter(ColumnMeta::primaryKey)
                .sorted((a, b) -> Integer.compare(a.pkSeq(), b.pkSeq())).toList();
        if (pkCols.size() == 1 && pkCols.get(0).isComparable()) {
            return pkCols.get(0);
        }
        if (!pkCols.isEmpty() && pkCols.get(0).isComparable()) {
            return pkCols.get(0);
        }
        return cols.stream().filter(c -> c.uniqueIndexFirst() && c.isComparable()).findFirst().orElse(null);
    }

    @Override
    public List<Range> planChunks(Connection conn, String schema, String table,
                                  ColumnMeta chunkKey, long estRows, int chunksPerTable) throws SQLException {
        if (chunkKey == null || estRows <= 0) {
            return List.of(Range.whole());
        }
        String qTable = qualifiedTable(schema, table);
        String qKey = quote(chunkKey.name());

        List<Range> ranges = new ArrayList<>();
        if (chunkKey.isNumeric()) {
            try (Statement st = conn.createStatement();
                 ResultSet rs = st.executeQuery("SELECT MIN(" + qKey + "), MAX(" + qKey + ") FROM " + qTable)) {
                if (rs.next() && rs.getObject(1) != null) {
                    BigDecimal min = rs.getBigDecimal(1);
                    BigDecimal max = rs.getBigDecimal(2);
                    ranges.addAll(splitNumeric(min, max, chunksPerTable));
                }
            }
        } else {
            // 非数值键:按估算行数步进取边界值
            long step = Math.max(1, estRows / chunksPerTable);
            String prev = null;
            for (int i = 1; i < chunksPerTable; i++) {
                long offset = step * i;
                String boundary = null;
                try (Statement st = conn.createStatement();
                     ResultSet rs = st.executeQuery(boundaryQuery(conn, qTable, qKey, offset))) {
                    if (rs.next()) {
                        boundary = rs.getString(1);
                    }
                }
                if (boundary == null) {
                    break; // 没那么多行了
                }
                if (prev == null || !prev.equals(boundary)) {
                    ranges.add(new Range(prev, boundary, false));
                    prev = boundary;
                }
            }
            if (!ranges.isEmpty()) {
                ranges.add(new Range(prev, null, false));
            }
        }

        if (ranges.isEmpty()) {
            ranges.add(Range.whole());
        }

        // 分段键为 NULL 的行需要补充分段
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery(nullChunkProbeQuery(conn, qTable, qKey))) {
            if (rs.next()) {
                ranges.add(new Range(null, null, true));
            }
        }
        return ranges;
    }

    private List<Range> splitNumeric(BigDecimal min, BigDecimal max, int n) {
        List<Range> ranges = new ArrayList<>();
        if (min.compareTo(max) >= 0) {
            ranges.add(new Range(min.toPlainString(), null, false));
            return ranges;
        }
        BigDecimal step = max.subtract(min).divide(BigDecimal.valueOf(n), MathContext.DECIMAL64);
        if (step.compareTo(BigDecimal.ONE) < 0 && min.stripTrailingZeros().scale() <= 0) {
            step = BigDecimal.ONE; // 整型键保证每段至少跨 1
        }
        BigDecimal cur = min;
        for (int i = 0; i < n; i++) {
            BigDecimal next = cur.add(step);
            boolean last = i == n - 1 || next.compareTo(max) >= 0;
            ranges.add(new Range(cur.toPlainString(), last ? null : next.toPlainString(), false));
            if (last) {
                break;
            }
            cur = next;
        }
        return ranges;
    }

    @Override
    public List<boolean[]> selectorLayout(List<ColumnMeta> cols, List<NullRule> rules) {
        List<boolean[]> layout = new ArrayList<>(cols.size());
        for (ColumnMeta c : cols) {
            boolean hasRule = rules != null && rules.stream().anyMatch(r -> r.matches(c.name()));
            layout.add(new boolean[]{c.isCharacter(), hasRule}); // null 计数恒有
        }
        return layout;
    }

    @Override
    public String buildColumnStatsSql(String schema, String table, List<ColumnMeta> cols,
                                      Range range, ColumnMeta chunkKey, List<NullRule> rules,
                                      boolean sampled, long sampleRows, Long estRows) {
        StringBuilder select = new StringBuilder("SELECT COUNT(*) AS total");
        for (int i = 0; i < cols.size(); i++) {
            ColumnMeta c = cols.get(i);
            String q = quote(c.name());
            select.append(", SUM(CASE WHEN ").append(q).append(" IS NULL THEN 1 ELSE 0 END) AS c").append(i).append("_null");
            if (c.isCharacter()) {
                select.append(", SUM(CASE WHEN TRIM(").append(q).append(") = '' THEN 1 ELSE 0 END) AS c").append(i).append("_empty");
            }
            if (rules != null) {
                List<String> values = rules.stream().filter(r -> r.matches(c.name()))
                        .flatMap(r -> r.values().stream()).distinct().toList();
                if (!values.isEmpty()) {
                    select.append(", SUM(CASE WHEN ").append(q).append(" IN (");
                    for (int j = 0; j < values.size(); j++) {
                        if (j > 0) select.append(", ");
                        select.append(literal(values.get(j), c));
                    }
                    select.append(") THEN 1 ELSE 0 END) AS c").append(i).append("_rule");
                }
            }
        }

        String from = sampled
                ? sampledFrom(qualifiedTable(schema, table), sampleRows, estRows)
                : qualifiedTable(schema, table);

        StringBuilder sql = new StringBuilder(select).append(" FROM ").append(from);

        List<String> predicates = new ArrayList<>();
        if (range != null && chunkKey != null && !(range.start() == null && range.end() == null && !range.nullChunk())) {
            String qKey = quote(chunkKey.name());
            if (range.nullChunk()) {
                predicates.add(qKey + " IS NULL");
            } else {
                if (range.start() != null) {
                    predicates.add(qKey + " >= " + literal(range.start(), chunkKey));
                }
                if (range.end() != null) {
                    predicates.add(qKey + " < " + literal(range.end(), chunkKey));
                }
            }
        }
        if (!predicates.isEmpty()) {
            sql.append(" WHERE ").append(String.join(" AND ", predicates));
        }
        if (sampled) {
            sql.append(sampledLimit(sampleRows));
        }
        return sql.toString();
    }

    /** 采样 FROM 子句;默认不特殊处理(配合 sampledLimit 退化为 LIMIT 采样,结果有偏) */
    protected String sampledFrom(String qualifiedTable, long sampleRows, Long estRows) {
        return qualifiedTable;
    }

    /** 采样 LIMIT 后缀;已用 TABLESAMPLE 的方言返回空串 */
    protected String sampledLimit(long sampleRows) {
        return limitClause(sampleRows);
    }

    /** LIMIT 子句方言 */
    protected String limitClause(long n) {
        return " LIMIT " + n;
    }

    /** 边界值查询的分页后缀(带 ORDER BY 的查询) */
    protected String boundarySuffix(long offset) {
        return " LIMIT 1 OFFSET " + offset;
    }

    /** 非数值分段键的边界值查询:取按键排序后第 offset 行的值 */
    protected String boundaryQuery(Connection conn, String qTable, String qKey, long offset) throws SQLException {
        return "SELECT " + qKey + " FROM " + qTable
                + " WHERE " + qKey + " IS NOT NULL ORDER BY " + qKey
                + boundarySuffix(offset);
    }

    /** 分段键 NULL 行探测查询 */
    protected String nullChunkProbeQuery(Connection conn, String qTable, String qKey) throws SQLException {
        return "SELECT 1 FROM " + qTable + " WHERE " + qKey + " IS NULL"
                + " ORDER BY " + qKey + limitClause(1);
    }

    /** 字面量渲染:数值列且值合法时原样输出,否则按字符串转义 */
    protected String literal(String value, ColumnMeta col) {
        if (col.isNumeric() && value != null && NUMERIC_LITERAL.matcher(value).matches()) {
            return value;
        }
        return quoteString(value);
    }

    protected String quoteString(String value) {
        return "'" + value.replace("'", "''") + "'";
    }

    protected String qualifiedTable(String schema, String table) {
        return quote(schema) + "." + quote(table);
    }
}
