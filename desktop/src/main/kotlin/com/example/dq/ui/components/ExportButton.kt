package com.example.dq.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.dq.env.ServiceEnv
import com.example.dq.ui.theme.LocalStatusColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.ui.Orientation
import org.jetbrains.jewel.ui.component.DefaultButton
import org.jetbrains.jewel.ui.component.Divider
import org.jetbrains.jewel.ui.component.CheckboxRow
import org.jetbrains.jewel.ui.component.OutlinedButton
import org.jetbrains.jewel.ui.component.Text
import java.awt.FileDialog
import java.awt.Frame
import java.io.File

// 与 ExportService.TABLE_DEFS / COLUMN_DEFS 的 key 一一对应,改动需同步
private val TABLE_COLS = listOf(
    "comment" to "注释",
    "storage" to "引擎/表空间",
    "totalRows" to "总行数",
    "sampled" to "是否采样",
    "sampleRows" to "采样行数",
    "fillRate" to "整体有值率%",
    "status" to "状态",
)

private val FIELD_COLS = listOf(
    "comment" to "注释",
    "type" to "类型",
    "key" to "键",
    "nullable" to "可空",
    "default" to "默认值",
    "totalRows" to "总行数",
    "nullCount" to "NULL数",
    "emptyCount" to "空串数",
    "ruleHitCount" to "规则命中数",
    "valueCount" to "有值数",
    "fillRate" to "有值率%",
)

private val SHEETS = listOf(
    "overview" to "概览",
    "tables" to "表列表",
    "fields" to "字段明细",
    "failed" to "异常表",
)

private val OVERVIEW_SAMPLE = listOf(
    "数据源" to "生产库 (MySQL)",
    "库/Schema" to "dqtest",
    "状态" to "DONE",
    "强制全量" to "否",
    "空值规则" to "默认(NULL + 空字符串)",
    "开始时间" to "2026-08-04 10:00:00",
    "结束时间" to "2026-08-04 10:05:30",
    "" to "",
    "统计总结" to "",
    "统计表数" to "120(完成 118,失败 2)",
    "空表数(0 行)" to "5",
    "空表率" to "4.17%",
    "字段总数" to "3,456",
    "空字段数(有值数为 0)" to "87",
    "空字段率" to "2.52%",
    "总数据行数" to "12,500,000(含采样估算)",
    "总占用空间" to "1.3 GB",
)

// 示例行按列 key 存值,保证预览时表头与表体始终对齐
private val TABLE_SAMPLE = listOf(
    mapOf(
        "name" to "user_order", "comment" to "订单表", "storage" to "InnoDB · 1.2 GB",
        "totalRows" to "12,500,000", "sampled" to "是(估算)", "sampleRows" to "1,000,000",
        "fillRate" to "87.32", "status" to "DONE",
    ),
    mapOf(
        "name" to "user_info", "comment" to "用户表", "storage" to "InnoDB · 64 MB",
        "totalRows" to "53,210", "sampled" to "否", "sampleRows" to "",
        "fillRate" to "95.10", "status" to "DONE",
    ),
)

private val FIELD_SAMPLE = listOf(
    mapOf(
        "table" to "user_order", "tableComment" to "订单表", "name" to "id",
        "comment" to "主键", "type" to "bigint(20)", "key" to "PK", "nullable" to "否",
        "default" to "", "totalRows" to "12,500,000", "nullCount" to "0", "emptyCount" to "0",
        "ruleHitCount" to "0", "valueCount" to "12,500,000", "fillRate" to "100",
    ),
    mapOf(
        "table" to "user_order", "tableComment" to "订单表", "name" to "mobile",
        "comment" to "手机号", "type" to "varchar(20)", "key" to "", "nullable" to "是",
        "default" to "", "totalRows" to "12,500,000", "nullCount" to "3,200", "emptyCount" to "150",
        "ruleHitCount" to "0", "valueCount" to "12,496,650", "fillRate" to "99.97",
    ),
)

/**
 * 导出 Excel 按钮(平移自 web/src/components/ExportButton.vue):
 * 弹出导出预览对话框,「表列表」「字段明细」页签内可勾选要导出的列,
 * 选保存路径后调用 ExportService 生成 xlsx。
 */
@Composable
fun ExportButton(env: ServiceEnv, jobId: Long, modifier: Modifier = Modifier) {
    var visible by remember { mutableStateOf(false) }
    var resultMsg by remember { mutableStateOf<String?>(null) }
    OutlinedButton(onClick = { visible = true }, modifier = modifier) {
        Text("导出 Excel", fontSize = 12.sp)
    }
    if (visible) {
        ExportDialog(
            env = env,
            jobId = jobId,
            onResult = { resultMsg = it },
            onDismiss = { visible = false },
        )
    }
    // 导出结果提示(成功/失败),「确定」「取消」都只是关闭
    resultMsg?.let { msg ->
        ConfirmDialog(
            title = "导出 Excel",
            message = msg,
            onConfirm = { resultMsg = null },
            onDismiss = { resultMsg = null },
        )
    }
}

@Composable
private fun ExportDialog(env: ServiceEnv, jobId: Long, onResult: (String) -> Unit, onDismiss: () -> Unit) {
    // 默认打开「字段明细」页签;每次打开对话框状态都是全新的(相当于 Vue 的 open() 里 reset)
    var activeSheet by remember { mutableStateOf("fields") }
    var tableChecked by remember { mutableStateOf(TABLE_COLS.map { it.first }.toSet()) }
    var fieldChecked by remember { mutableStateOf(FIELD_COLS.map { it.first }.toSet()) }
    var exporting by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    fun reset() {
        tableChecked = TABLE_COLS.map { it.first }.toSet()
        fieldChecked = FIELD_COLS.map { it.first }.toSet()
    }

    fun doExport() {
        // AWT 文件保存对话框
        val fd = FileDialog(null as Frame?, "导出 Excel", FileDialog.SAVE)
        fd.file = "scan-$jobId.xlsx"
        fd.isVisible = true
        val dir = fd.directory ?: return
        val rawName = fd.file ?: return
        val fileName = if (rawName.endsWith(".xlsx")) rawName else "$rawName.xlsx"
        val outFile = File(dir, fileName)
        // 全选时传 null(默认全部列);一列不选时传空集,表示只留固定列
        val tableCols =
            if (tableChecked.size == TABLE_COLS.size) null else TABLE_COLS.map { it.first }.filter { it in tableChecked }
        val cols =
            if (fieldChecked.size == FIELD_COLS.size) null else FIELD_COLS.map { it.first }.filter { it in fieldChecked }
        exporting = true
        scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    outFile.outputStream().use { env.exportService.export(jobId, tableCols, cols, it) }
                }
                onResult("导出成功:${outFile.absolutePath}")
                onDismiss()
            } catch (e: Exception) {
                // 失败不关对话框,方便调整后重试
                onResult("导出失败:${e.message}")
            } finally {
                exporting = false
            }
        }
    }

    // JewelDialog 已带 20dp 内边距与纵向滚动,内层 Column 保持原有子项间距不变
    JewelDialog(onDismissRequest = onDismiss, width = 920.dp) {
        Column {
            Text("导出 Excel", fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(8.dp))
            Text(
                "导出文件结构预览(示例数据)。「表列表」「字段明细」页签内可勾选要导出的列," +
                    "下方表格实时预览最终样式;灰色固定列始终导出。",
                fontSize = 13.sp,
                color = JewelTheme.globalColors.text.disabled,
            )
            Spacer(Modifier.height(12.dp))

            // Excel 风格 sheet 页签
            Row {
                SHEETS.forEach { (key, name) ->
                    SheetTab(name, active = activeSheet == key) { activeSheet = key }
                }
            }
            Divider(Orientation.Horizontal, thickness = 2.dp, color = LocalStatusColors.current.primary)
            Spacer(Modifier.height(12.dp))

            when (activeSheet) {
                // 概览:固定 KV,不可配置
                "overview" -> OverviewPreview()
                // 表列表:勾选列 + 实时预览
                "tables" -> {
                    SectionTitle("表头设置")
                    ColChecks(TABLE_COLS, tableChecked) { tableChecked = it }
                    val visibleCols = TABLE_COLS.filter { it.first in tableChecked }
                    PreviewTable(
                        headers = listOf("表名") + visibleCols.map { it.second },
                        rows = TABLE_SAMPLE.map { r -> listOf(r["name"]!!) + visibleCols.map { r[it.first] ?: "" } },
                    )
                }
                // 字段明细:每张 DONE 的表一个 sheet,结构相同;勾选列 + 实时预览
                "fields" -> {
                    SheetNote("每张扫描完成的表生成一个 sheet(sheet 名 = 表名),结构相同,此处以 user_order 为例:")
                    SectionTitle("表头设置")
                    ColChecks(FIELD_COLS, fieldChecked) { fieldChecked = it }
                    val visibleCols = FIELD_COLS.filter { it.first in fieldChecked }
                    PreviewTable(
                        headers = listOf("表名", "表注释", "字段") + visibleCols.map { it.second },
                        rows = FIELD_SAMPLE.map { r ->
                            listOf(r["table"]!!, r["tableComment"]!!, r["name"]!!) +
                                visibleCols.map { r[it.first] ?: "" }
                        },
                    )
                }
                // 异常表:固定,仅有失败表时出现
                else -> {
                    SheetNote("仅当存在扫描失败的表时才会生成该 sheet。")
                    PreviewTable(
                        headers = listOf("表名", "错误信息"),
                        rows = listOf(listOf("pay_record", "Lock wait timeout exceeded; try restarting transaction")),
                    )
                }
            }

            Spacer(Modifier.height(16.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                OutlinedButton(onClick = { reset() }) { Text("重置") }
                OutlinedButton(onClick = onDismiss) { Text("取消") }
                DefaultButton(onClick = { doExport() }, enabled = !exporting) {
                    Text(if (exporting) "导出中…" else "导出")
                }
            }
        }
    }
}

/** sheet 页签(Excel 风格) */
@Composable
private fun SheetTab(name: String, active: Boolean, onClick: () -> Unit) {
    val primary = LocalStatusColors.current.primary
    Text(
        name,
        fontSize = 13.sp,
        color = if (active) Color.White else JewelTheme.globalColors.text.normal,
        modifier = Modifier
            .padding(end = 2.dp)
            .background(
                if (active) primary else JewelTheme.globalColors.panelBackground,
                RoundedCornerShape(topStart = 8.dp, topEnd = 8.dp),
            )
            .border(
                1.dp,
                if (active) primary else previewBorder,
                RoundedCornerShape(topStart = 8.dp, topEnd = 8.dp),
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 4.dp),
    )
}

@Composable
private fun SectionTitle(text: String) {
    Text(text, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(bottom = 8.dp))
}

@Composable
private fun SheetNote(text: String) {
    Text(text, fontSize = 12.sp, color = JewelTheme.globalColors.text.disabled, modifier = Modifier.padding(bottom = 8.dp))
}

/** 列勾选组(对应 el-checkbox-group) */
@Composable
private fun ColChecks(cols: List<Pair<String, String>>, checked: Set<String>, onChange: (Set<String>) -> Unit) {
    FlowRow(
        modifier = Modifier.padding(bottom = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        cols.forEach { (key, label) ->
            CheckboxRow(
                text = label,
                checked = key in checked,
                onCheckedChange = { on -> onChange(if (on) checked + key else checked - key) },
            )
        }
    }
}

private val previewBorder = Color(0xFFDCDFE6)
private val previewHeaderBg = Color(0xFFF0F2F5)

/** 预览表格(固定等宽单元格,横向可滚动) */
@Composable
private fun PreviewTable(headers: List<String>, rows: List<List<String>>, cellWidth: Dp = 120.dp) {
    Column(Modifier.horizontalScroll(rememberScrollState())) {
        Row {
            headers.forEach { PreviewCell(it, bold = true, bg = previewHeaderBg, width = cellWidth) }
        }
        rows.forEach { r ->
            Row {
                r.forEach { PreviewCell(it, bold = false, bg = Color.Unspecified, width = cellWidth) }
            }
        }
    }
}

/** 概览 sheet 预览:固定 KV 两列 */
@Composable
private fun OverviewPreview() {
    Column(Modifier.horizontalScroll(rememberScrollState())) {
        OVERVIEW_SAMPLE.forEach { (k, v) ->
            Row {
                PreviewCell(k, bold = false, bg = previewHeaderBg, width = 180.dp)
                PreviewCell(v, bold = false, bg = Color.Unspecified, width = 280.dp)
            }
        }
    }
}

@Composable
private fun PreviewCell(text: String, bold: Boolean, bg: Color, width: Dp) {
    Box(
        Modifier
            .width(width)
            .border(0.5.dp, previewBorder)
            .background(bg)
            .padding(horizontal = 10.dp, vertical = 4.dp),
    ) {
        Text(text, fontSize = 12.sp, fontWeight = if (bold) FontWeight.SemiBold else null, maxLines = 1)
    }
}
