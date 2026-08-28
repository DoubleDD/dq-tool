package com.example.dq.service

import com.example.dq.model.DataSourceConfig
import com.example.dq.model.DbType
import com.example.dq.util.JdbcUrlRewriter
import org.apache.poi.ss.usermodel.DataFormatter
import org.apache.poi.xssf.usermodel.XSSFWorkbook
import java.io.InputStream

/**
 * 「表格批量导入数据源 + 抽样导出」的上传 Excel 解析(样例 docs/1导入数据.xlsx):
 * 单 sheet,表头 12 列(见 [HEADERS]),每行 = 一张要抽样的表。
 * 数据坑已由解析层抹平:数值型单元格(端口/口令)经 DataFormatter 取显示字符串、
 * 地址前导空格 trim、端口空/解析不出归一为 null(后续按方言默认端口)。
 */
object SampleTableExcelParser {

    /** 表头列名(顺序无关,按名定位;匹配前去空白) */
    private val HEADERS = listOf(
        "所属水利对象类别名称", "系统编号", "实际系统或模式描述", "数据库类型", "地址", "端口",
        "用户名", "口令", "数据库名称", "模式名称", "表英文名称", "表中文名称")

    /** 数据行必需列:缺失/为空的行收集为无效行(ROW_SKIPPED),不参与后续导入与导出 */
    private val REQUIRED_HEADERS = listOf("数据库类型", "地址", "数据库名称", "表英文名称")

    /** Excel 里的数据库类型写法 → DbType(大小写不敏感) */
    private val DB_TYPE_ALIASES = mapOf(
        "mysql" to DbType.MYSQL,
        "kingbase" to DbType.KINGBASE,
        "sqlserver" to DbType.SQLSERVER,
        "oracle" to DbType.ORACLE,
        "postgresql" to DbType.POSTGRESQL,
        "dm" to DbType.DM,
        "highgo" to DbType.HIGHGO,
        "oceanbase" to DbType.OCEANBASE,
    )

    /** 一条有效数据行(一张要抽样的表);字符串列已全部 trim,空串归一为 null */
    data class SampleTableRow(
        val seq: Int,                    // 数据行序(首个数据行为 1)
        val category: String?,           // 所属水利对象类别名称
        val sysNo: String?,
        val sysDesc: String?,            // 实际系统或模式描述
        val dbType: DbType,
        val host: String,
        val port: Int?,                  // 空/解析不出为 null,用 effectivePort 取生效端口
        val username: String?,
        val password: String?,           // 仅内存使用,不落 sample_export_item 表
        val databaseName: String,
        val schemaName: String?,
        val tableName: String,
        val tableCnName: String?,
    ) {
        /** 生效端口:显式端口优先,缺省按方言默认 */
        val effectivePort: Int get() = port ?: JdbcUrlRewriter.defaultPort(dbType)

        /** 数据源身份 key:同 key 的多行共享一个数据源;DM 的 URL 不带库名,身份不含库名(与 keyOfExisting 对齐) */
        val dsKey: String
            get() = dsKey(dbType, host, effectivePort, username,
                if (dbType == DbType.DM) "" else databaseName)

        /** 按方言拼 JDBC URL(数据库名原样放入,未做转义;与 Navicat 导入的拼法口径一致) */
        val jdbcUrl: String get() = buildJdbcUrl(dbType, host, effectivePort, databaseName)

        /** 数据源显示名:「实际系统或模式描述(数据库名称)」,描述为空时只用库名 */
        val displayName: String
            get() = if (sysDesc.isNullOrBlank()) databaseName else "$sysDesc($databaseName)"
    }

    /** 一条无效数据行(缺必需列/类型无法识别);保留原始信息供 ds_report 说明 */
    data class SkippedRow(
        val seq: Int,
        val reason: String,              // 如「缺少必需列: 数据库类型、表英文名称」
        val sysDesc: String?,
        val host: String?,
        val port: Int?,
        val databaseName: String?,
        val tableName: String?,
    )

    data class ParseResult(
        val rows: List<SampleTableRow>,
        val skipped: List<SkippedRow>,
    )

    /**
     * 解析上传的 xlsx:第一个 sheet,首行为表头,按表头名(去空白)定位列。
     * 缺必需列表头抛 IllegalArgumentException(列出缺哪些);全空行跳过;
     * 缺必需列值/类型无法识别的行收集进 [ParseResult.skipped],不中断整批。
     */
    fun parse(input: InputStream): ParseResult {
        val fmt = DataFormatter()
        XSSFWorkbook(input).use { wb ->
            val sheet = wb.getSheetAt(0) ?: throw IllegalArgumentException("Excel 中没有工作表")
            val headerRow = sheet.getRow(sheet.firstRowNum)
                ?: throw IllegalArgumentException("Excel 中没有表头行")
            // 表头名(去空白) → 列下标;可选列缺失按空处理,必需列缺失直接报错
            val colIndex = HashMap<String, Int>()
            for (cell in headerRow) {
                val name = fmt.formatCellValue(cell)?.trim().orEmpty()
                if (name in HEADERS && !colIndex.containsKey(name)) {
                    colIndex[name] = cell.columnIndex
                }
            }
            val missingHeaders = REQUIRED_HEADERS.filter { it !in colIndex }
            if (missingHeaders.isNotEmpty()) {
                throw IllegalArgumentException("Excel 缺少必需列: ${missingHeaders.joinToString("、")}")
            }

            fun text(row: org.apache.poi.ss.usermodel.Row, header: String): String =
                colIndex[header]?.let { idx -> fmt.formatCellValue(row.getCell(idx))?.trim() }.orEmpty()

            val rows = ArrayList<SampleTableRow>()
            val skipped = ArrayList<SkippedRow>()
            for (r in (headerRow.rowNum + 1)..sheet.lastRowNum) {
                val row = sheet.getRow(r) ?: continue
                val values = HEADERS.associateWith { text(row, it) }
                if (values.values.all { it.isEmpty() }) {
                    continue // 全空行
                }
                val seq = rows.size + skipped.size + 1
                val dbTypeText = values.getValue("数据库类型")
                val host = values.getValue("地址")
                val databaseName = values.getValue("数据库名称")
                val tableName = values.getValue("表英文名称")
                val sysDesc = values.getValue("实际系统或模式描述").ifEmpty { null }
                val port = values.getValue("端口").toIntOrNull()

                val missing = buildList {
                    if (dbTypeText.isEmpty()) add("数据库类型")
                    if (host.isEmpty()) add("地址")
                    if (databaseName.isEmpty()) add("数据库名称")
                    if (tableName.isEmpty()) add("表英文名称")
                }
                if (missing.isNotEmpty()) {
                    skipped.add(SkippedRow(seq, "缺少必需列: ${missing.joinToString("、")}",
                        sysDesc, host.ifEmpty { null }, port, databaseName.ifEmpty { null }, tableName.ifEmpty { null }))
                    continue
                }
                val dbType = DB_TYPE_ALIASES[dbTypeText.lowercase()]
                if (dbType == null) {
                    skipped.add(SkippedRow(seq, "数据库类型无法识别: $dbTypeText",
                        sysDesc, host.ifEmpty { null }, port, databaseName.ifEmpty { null }, tableName.ifEmpty { null }))
                    continue
                }
                rows.add(SampleTableRow(
                    seq = seq,
                    category = values.getValue("所属水利对象类别名称").ifEmpty { null },
                    sysNo = values.getValue("系统编号").ifEmpty { null },
                    sysDesc = sysDesc,
                    dbType = dbType,
                    host = host,
                    port = port,
                    username = values.getValue("用户名").ifEmpty { null },
                    password = values.getValue("口令").ifEmpty { null },
                    databaseName = databaseName,
                    schemaName = values.getValue("模式名称").ifEmpty { null },
                    tableName = tableName,
                    tableCnName = values.getValue("表中文名称").ifEmpty { null },
                ))
            }
            return ParseResult(rows, skipped)
        }
    }

    /** 按方言拼 JDBC URL;dm 的 URL 不带库名(驱动按实例连接,库名在连接后选择) */
    fun buildJdbcUrl(dbType: DbType, host: String, port: Int, database: String): String = when (dbType) {
        DbType.MYSQL -> "jdbc:mysql://$host:$port/$database"
        DbType.KINGBASE -> "jdbc:kingbase8://$host:$port/$database"
        DbType.POSTGRESQL -> "jdbc:postgresql://$host:$port/$database"
        DbType.HIGHGO -> "jdbc:highgo://$host:$port/$database"
        DbType.SQLSERVER -> "jdbc:sqlserver://$host:$port;databaseName=$database"
        DbType.ORACLE -> "jdbc:oracle:thin:@//$host:$port/$database" // 库名作服务名
        DbType.DM -> "jdbc:dm://$host:$port"
        DbType.OCEANBASE -> "jdbc:oceanbase://$host:$port/$database"
    }

    /** 数据源身份 key:type|host小写|port|username或空|database;两侧(Excel 行/已存数据源)口径必须一致 */
    fun dsKey(dbType: DbType, host: String, port: Int, username: String?, database: String): String =
        "${dbType.name}|${host.trim().lowercase()}|$port|${username.orEmpty()}|$database"

    /**
     * 从已存数据源的 jdbcUrl 反解身份 key(host/port 经 JdbcUrlRewriter,库名按方言从 URL 提取);
     * URL 解析不出或提取不到库名时返回 null——该数据源不参与批量导入的去重匹配
     */
    fun keyOfExisting(ds: DataSourceConfig): String? {
        val type = ds.dbType ?: return null
        val url = ds.jdbcUrl ?: return null
        val (host, port) = try {
            JdbcUrlRewriter.extractHostPort(url)
        } catch (e: IllegalArgumentException) {
            return null
        }
        val database = extractDatabase(type, url) ?: return null
        return dsKey(type, host, port, ds.username, database)
    }

    /** 生成导入模版 xlsx:表头 + 一行示例数据(前端「下载模版」按钮) */
    fun writeTemplate(out: java.io.OutputStream) {
        XSSFWorkbook().use { wb ->
            val sheet = wb.createSheet("导入数据")
            val head = sheet.createRow(0)
            HEADERS.forEachIndexed { i, h ->
                head.createCell(i).setCellValue(h)
                sheet.setColumnWidth(i, 18 * 256)
            }
            val example = listOf(
                "01流域", "101", "示例系统", "mysql", "192.168.1.10", "3306",
                "root", "password", "demo_db", "demo_db", "demo_table", "示例表")
            val row = sheet.createRow(1)
            example.forEachIndexed { i, v -> row.createCell(i).setCellValue(v) }
            wb.write(out)
        }
    }

    /** 从 jdbcUrl 提取库名:普通形态取 `://h:p/` 后到 ? 前的路径段;sqlserver 取 databaseName 参数;oracle 取 @// 后服务名;dm 无库名恒空串 */
    private fun extractDatabase(type: DbType, url: String): String? = when (type) {
        DbType.DM -> ""
        DbType.SQLSERVER ->
            Regex("""databaseName=([^;]+)""", RegexOption.IGNORE_CASE).find(url)?.groupValues?.get(1)
        DbType.ORACLE ->
            Regex("""@//[^/]+/([^?;]+)""").find(url)?.groupValues?.get(1)
        else ->
            Regex("""://[^/]+/([^?;]+)""").find(url)?.groupValues?.get(1)
    }
}
