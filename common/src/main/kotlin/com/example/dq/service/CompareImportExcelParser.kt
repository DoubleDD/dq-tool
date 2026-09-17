package com.example.dq.service

import com.example.dq.model.DbType
import org.apache.poi.ss.usermodel.DataFormatter
import org.apache.poi.xssf.usermodel.XSSFWorkbook
import java.io.InputStream

/**
 * 「比对任务批量导入」的上传 Excel 解析(口径照 [SampleTableExcelParser]:
 * XSSFWorkbook + DataFormatter 取显示字符串、表头按名去空白定位、全空行跳过、非法行进 SkippedRow)。
 * 单文件多 sheet,**一个 sheet = 一个比对任务**:按「是否基准表」列定位基准行(=「是」,位置不限,
 * 不要求首行),其余行为对比表;表头 15 列(见 [HEADERS],顺序不限,客户提供的模板格式)。
 * **口令只活在内存**,批次表不落口令列。
 *
 * sheet 级规则:「是否基准表=是」恰好一行(零行/多行整体跳过)、基准行的对象编码/名称字段必填;
 * 违反规则的 sheet 整体进 [ParseResult.skippedSheets](不阻塞其他 sheet)。
 * 「是」行因缺必需列/端口非法/类型无法识别被行级校验丢掉时,sheet 级原因带出该行的具体原因
 * (「基准行无效: 缺少必需列: 数据库名称」),不再一律报「缺少基准行」而掩盖真实问题。
 * 「所属水利对象类别名称」为自由文本(仅基准行取值落任务 object_category,对比行忽略),
 * 不做内置列表校验;「是否纳入采集范围」是人工采集功能的列,比对导入不读(只参与表头定位与全空行判定)。
 */
object CompareImportExcelParser {

    /** 表头列名(顺序无关,按名定位;匹配前去空白);「是否纳入采集范围」不消费,仅为模板列齐全保留 */
    private val HEADERS = listOf(
        "所属水利对象类别名称", "实际系统或模式描述", "数据库类型", "IP地址", "端口",
        "用户名", "口令", "数据库名称", "模式名称", "表中文名称", "表英文名称",
        "是否基准表", "对象编码字段", "对象名称字段", "是否纳入采集范围")

    /** 必需列表头:缺失直接报错(列出缺哪些) */
    private val REQUIRED_HEADERS = listOf("数据库类型", "IP地址", "端口", "数据库名称", "表英文名称", "是否基准表")

    const val BASE_YES = "是"
    const val BASE_NO = "否"

    /**
     * 一条有效数据行(基准/对比各一张表);字符串列已全部 trim,空串归一为 null。
     * [password] 仅内存使用,不落库
     */
    data class ImportRow(
        val seq: Int,                    // 数据行序(首个数据行为 1)
        val base: Boolean,               // true=基准行(是否基准表=是),false=对比行
        val objectCategory: String?,     // 所属水利对象类别名称原值(自由文本;仅基准行有意义)
        val dsDisplay: String?,          // 「实际系统或模式描述」列:展示/命名/校验辅助
        val dbType: DbType,              // 「数据库类型」列(取值口径同 SampleTableExcelParser,大小写不敏感)
        val host: String,
        val port: Int,
        val databaseName: String,
        val schemaName: String?,
        val tableName: String,
        val tableCnName: String?,        // 表中文名称:解析留档(供展示/报告),导入流程不消费
        val codeField: String?,          // 对象编码字段:基准行必填(= keyField);对比行可空,填了直接锁进映射
        val nameField: String?,          // 对象名称字段:基准行必填(= displayField);对比行可空,同上
        val username: String?,           // 可选:仅数据源不存在需新建时必填
        val password: String?,
    ) {
        /** 数据源定位 key:类型|host小写|port|库名(不含用户名,比对导入按「类型+地址+端口+库」匹配) */
        val dsKey: String get() = DatasourceKeyMatcher.locationKey(dbType, host, port, databaseName)

        /** 数据源显示名:「实际系统或模式描述(数据库名称)」,描述为空时只用库名(与抽样导出 SampleTableRow.displayName 同口径) */
        val dsDisplayName: String
            get() = if (dsDisplay.isNullOrBlank()) databaseName else "$dsDisplay($databaseName)"
    }

    /** 一条无效数据行(缺必需列/端口、类型或是否基准表无法识别);保留原始信息供报告说明 */
    data class SkippedRow(
        val sheetName: String,
        val seq: Int,
        val reason: String,
    )

    /** 一个被整体跳过的工作表(基准行规则不满足) */
    data class SkippedSheet(
        val sheetName: String,
        val reason: String,
    )

    /** 一个有效工作表 = 一个待建比对任务 */
    data class SheetTask(
        val sheetName: String,
        /** sheet 序号(0 起,sheet 名为空/非法时任务名回退用它) */
        val sheetIndex: Int,
        val base: ImportRow,
        val targets: List<ImportRow>,
        /** 所属水利对象类别名称(基准行原值 trim,空为 null;自由文本不校验) */
        val objectCategory: String?,
    )

    data class ParseResult(
        val sheets: List<SheetTask>,
        val skippedSheets: List<SkippedSheet>,
        val skippedRows: List<SkippedRow>,
    )

    /**
     * 解析上传的 xlsx:逐 sheet,首行为表头,按表头名(去空白)定位列。
     * 缺必需列表头抛 IllegalArgumentException(列出缺哪些,带 sheet 名);
     * 没有任何有效 sheet 时也抛错(由调用方决定批次置 FAILED)
     */
    fun parse(input: InputStream): ParseResult {
        val fmt = DataFormatter()
        XSSFWorkbook(input).use { wb ->
            if (wb.numberOfSheets == 0) throw IllegalArgumentException("Excel 中没有工作表")
            val sheets = ArrayList<SheetTask>()
            val skippedSheets = ArrayList<SkippedSheet>()
            val skippedRows = ArrayList<SkippedRow>()
            for (si in 0 until wb.numberOfSheets) {
                parseSheet(wb.getSheetAt(si), si, fmt, sheets, skippedSheets, skippedRows)
            }
            return ParseResult(sheets, skippedSheets, skippedRows)
        }
    }

    private fun parseSheet(
        sheet: org.apache.poi.ss.usermodel.Sheet, sheetIndex: Int, fmt: DataFormatter,
        sheets: MutableList<SheetTask>, skippedSheets: MutableList<SkippedSheet>,
        skippedRows: MutableList<SkippedRow>,
    ) {
        val sheetName = sheet.sheetName ?: ""
        val headerRow = sheet.getRow(sheet.firstRowNum)
        if (headerRow == null) {
            skippedSheets.add(SkippedSheet(sheetName, "没有表头行"))
            return
        }
        // 表头名(去空白) → 列下标;必需列缺失整个 sheet 报错(表头都缺,说明不是按模版填的)
        val colIndex = HashMap<String, Int>()
        for (cell in headerRow) {
            val name = fmt.formatCellValue(cell)?.trim().orEmpty()
            if (name in HEADERS && !colIndex.containsKey(name)) {
                colIndex[name] = cell.columnIndex
            }
        }
        val missingHeaders = REQUIRED_HEADERS.filter { it !in colIndex }
        if (missingHeaders.isNotEmpty()) {
            throw IllegalArgumentException("工作表「$sheetName」缺少必需列: ${missingHeaders.joinToString("、")}")
        }

        fun text(row: org.apache.poi.ss.usermodel.Row, header: String): String =
            colIndex[header]?.let { idx -> fmt.formatCellValue(row.getCell(idx))?.trim() }.orEmpty()

        var base: ImportRow? = null
        var baseInvalid: String? = null       // 基准行自身不合法(缺身份字段)的原因
        var baseCount = 0                     // 「是否基准表=是」的行数(恰好一行才有效)
        var baseDropped: String? = null       // 「是」行被行级校验丢掉的原因(否则 sheet 只报「缺少基准行」,掩盖真实的缺列/类型问题)
        var targetDropped: String? = null     // 「否」行被行级校验丢掉的原因(补充「没有有效对比行」的说明)
        var dataSeq = 0                       // sheet 内数据行序(首个数据行为 1)
        val targets = ArrayList<ImportRow>()
        for (r in (headerRow.rowNum + 1)..sheet.lastRowNum) {
            val row = sheet.getRow(r) ?: continue
            val values = HEADERS.associateWith { text(row, it) }
            if (values.values.all { it.isEmpty() }) continue // 全空行
            val seq = ++dataSeq
            // 丢行统一入口:记 SkippedRow,并按行角色(是/否/未知)把首个原因带到 sheet 级说明
            val dropRow: (String, Boolean?) -> Unit = { reason, baseFlag ->
                skippedRows.add(SkippedRow(sheetName, seq, reason))
                if (baseFlag == true && baseDropped == null) baseDropped = reason
                if (baseFlag == false && targetDropped == null) targetDropped = reason
            }
            val baseText = values.getValue("是否基准表")
            val isBase = when (baseText) {
                BASE_YES -> true
                BASE_NO -> false
                else -> {
                    dropRow("「是否基准表」无法识别: $baseText(应为「是」或「否」)", null)
                    continue
                }
            }
            val dbTypeText = values.getValue("数据库类型")
            val host = values.getValue("IP地址")
            val portText = values.getValue("端口")
            val port = portText.toIntOrNull()
            val databaseName = values.getValue("数据库名称")
            val tableName = values.getValue("表英文名称")
            val missing = buildList {
                if (dbTypeText.isEmpty()) add("数据库类型")
                if (host.isEmpty()) add("IP地址")
                if (portText.isEmpty()) add("端口")
                if (databaseName.isEmpty()) add("数据库名称")
                if (tableName.isEmpty()) add("表英文名称")
            }
            if (missing.isNotEmpty()) {
                dropRow("缺少必需列: ${missing.joinToString("、")}", isBase)
                continue
            }
            if (port == null) {
                dropRow("端口无法识别: $portText", isBase)
                continue
            }
            val dbType = SampleTableExcelParser.DB_TYPE_ALIASES[dbTypeText.lowercase()]
            if (dbType == null) {
                dropRow("数据库类型无法识别: $dbTypeText", isBase)
                continue
            }
            val importRow = ImportRow(
                seq = seq, base = isBase,
                objectCategory = values.getValue("所属水利对象类别名称").ifEmpty { null },
                dsDisplay = values.getValue("实际系统或模式描述").ifEmpty { null },
                dbType = dbType,
                host = host, port = port, databaseName = databaseName,
                schemaName = values.getValue("模式名称").ifEmpty { null },
                tableName = tableName,
                tableCnName = values.getValue("表中文名称").ifEmpty { null },
                codeField = values.getValue("对象编码字段").ifEmpty { null },
                nameField = values.getValue("对象名称字段").ifEmpty { null },
                username = values.getValue("用户名").ifEmpty { null },
                password = values.getValue("口令").ifEmpty { null },
            )
            if (isBase) {
                baseCount++
                if (base == null) {
                    base = importRow
                    // 基准行身份字段必填(= 任务 keyField/displayField)
                    baseInvalid = buildList {
                        if (importRow.codeField == null) add("对象编码字段")
                        if (importRow.nameField == null) add("对象名称字段")
                    }.takeIf { it.isNotEmpty() }?.let { "基准行缺少必需列: ${it.joinToString("、")}" }
                }
            } else {
                targets.add(importRow)
            }
        }

        val baseRow = base
        when {
            baseCount > 1 -> skippedSheets.add(SkippedSheet(sheetName, "基准行有且仅有一行(「是否基准表」恰需一行「是」)"))
            baseRow == null -> skippedSheets.add(SkippedSheet(sheetName,
                baseDropped?.let { "基准行无效: $it" } ?: "缺少基准行(「是否基准表」恰需一行「是」)"))
            baseInvalid != null -> skippedSheets.add(SkippedSheet(sheetName, baseInvalid!!))
            targets.isEmpty() -> skippedSheets.add(SkippedSheet(sheetName,
                targetDropped?.let { "没有有效对比行($it)" } ?: "没有有效对比行"))
            else -> sheets.add(SheetTask(sheetName, sheetIndex, baseRow, targets, baseRow.objectCategory))
        }
    }

    /** 生成导入模版 xlsx:表头 + 基准/对比两行示例(前端「下载模版」按钮) */
    fun writeTemplate(out: java.io.OutputStream) {
        XSSFWorkbook().use { wb ->
            val sheet = wb.createSheet("示例任务")
            val head = sheet.createRow(0)
            HEADERS.forEachIndexed { i, h ->
                head.createCell(i).setCellValue(h)
                sheet.setColumnWidth(i, 18 * 256)
            }
            val baseRow = sheet.createRow(1)
            listOf("供(取)水量监测点", "水资源监控平台-汇集库", "mysql", "192.168.1.10", "3306",
                "root", "password", "base_db", "base_db", "取用水监测点基本信息表", "wr_mp_b",
                "是", "res_code", "res_name", "是")
                .forEachIndexed { i, v -> baseRow.createCell(i).setCellValue(v) }
            val targetRow = sheet.createRow(2)
            listOf("供(取)水量监测点", "小水电生态泄流系统-用户库", "mysql", "192.168.1.11", "3306",
                "root", "password", "vendor_db", "vendor_db", "取用水监测点基本信息表", "wr_mp_b",
                "否", "", "", "是")
                .forEachIndexed { i, v -> targetRow.createCell(i).setCellValue(v) }
            wb.write(out)
        }
    }
}
