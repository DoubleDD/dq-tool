package com.example.dq.ui.views

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.dq.model.ScanColumnView
import com.example.dq.model.ScanTableView
import com.example.dq.ui.AppEnv
import com.example.dq.ui.Screen
import com.example.dq.ui.TabsModel
import com.example.dq.ui.components.EmptyHint
import com.example.dq.ui.components.TableColumn
import com.example.dq.ui.components.formatDuration
import com.example.dq.ui.components.formatNumber
import com.example.dq.ui.theme.StatusDanger
import com.example.dq.ui.theme.StatusPrimary
import com.example.dq.ui.theme.StatusSuccess
import com.example.dq.ui.theme.StatusWarning
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.awt.FileDialog
import java.awt.Frame
import java.io.File
import kotlin.math.floor

/**
 * 字段统计页(平移自 web/src/views/TableColumns.vue):
 * 单表字段统计表格、有值率着色、只看空字段过滤、Excel 导出。
 */
@Composable
fun TableColumnsView(env: AppEnv, tabs: TabsModel, screen: Screen.TableColumns) {
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }

    var columns by remember { mutableStateOf<List<ScanColumnView>>(emptyList()) }
    var tableInfo by remember { mutableStateOf<ScanTableView?>(null) }
    var loading by remember { mutableStateOf(false) }
    var keyword by remember { mutableStateOf("") }
    var onlyEmpty by remember { mutableStateOf(false) }
    var exporting by remember { mutableStateOf(false) }
    var sortIndex by remember { mutableIntStateOf(-1) }
    var sortAsc by remember { mutableStateOf(true) }

    fun showMessage(msg: String) {
        scope.launch { snackbar.showSnackbar(msg) }
    }

    LaunchedEffect(screen) {
        loading = true
        try {
            val (cols, table) = withContext(Dispatchers.IO) {
                val c = env.scanService.getColumns(screen.jobId, screen.tableName)
                // 任务详情失败仅影响顶部汇总条,不阻塞字段表格
                val t = runCatching { env.scanService.getJob(screen.jobId) }.getOrNull()
                    ?.tables?.firstOrNull { it.tableName == screen.tableName }
                c to t
            }
            columns = cols
            tableInfo = table
        } catch (e: Exception) {
            showMessage(e.message ?: "加载字段统计失败")
        } finally {
            loading = false
        }
    }

    /** 空值数合计 = NULL + 空串 + 规则命中 */
    fun nullTotal(c: ScanColumnView): Long = c.nullCount + c.emptyCount + c.ruleHitCount

    // 空字段:有值数为 0 的字段
    val emptyColumns = columns.filter { it.valueCount == 0L }

    val filtered = run {
        var list = if (onlyEmpty) emptyColumns else columns
        val kw = keyword.trim().lowercase()
        if (kw.isNotEmpty()) {
            list = list.filter {
                (it.columnName ?: "").lowercase().contains(kw) ||
                    (it.columnComment ?: "").lowercase().contains(kw)
            }
        }
        list
    }

    // 排序 selector 与 columns 顺序对齐:
    // 序号/字段名/注释/类型/键/可空/默认值/空值数/总行数/NULL数/空串数/有值数/有值率
    val sortSelectors: List<((ScanColumnView) -> Comparable<*>?)?> = listOf(
        null,
        { it.columnName },
        { it.columnComment },
        { it.columnType },
        { it.keyLabel },
        { it.nullable },
        null,
        { nullTotal(it) },
        { it.totalRows },
        { it.nullCount },
        { it.emptyCount },
        { it.valueCount },
        { it.fillRate },
    )
    val sortedRows = applySort(filtered, sortSelectors, sortIndex, sortAsc)

    val tableColumns = listOf(
        TableColumn<ScanColumnView>("序号", width = 50.dp) { row ->
            Text((sortedRows.indexOf(row) + 1).toString(), fontSize = 13.sp)
        },
        TableColumn("字段名", width = 160.dp) { row ->
            Text(row.columnName ?: "-", fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        },
        TableColumn("注释", weight = 1f) { row ->
            if (!row.columnComment.isNullOrEmpty()) {
                Text(row.columnComment, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            } else {
                Text("-", fontSize = 13.sp, color = PlaceholderColor)
            }
        },
        TableColumn("类型", width = 140.dp) { row ->
            Text(row.columnType ?: "-", fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        },
        TableColumn("键", width = 60.dp) { row ->
            if (!row.keyLabel.isNullOrEmpty()) {
                // PK 主色,其他键(UNI)成功色
                val color = if (row.keyLabel == "PK") StatusPrimary else StatusSuccess
                Text(
                    row.keyLabel,
                    fontSize = 12.sp,
                    color = color,
                    modifier = Modifier
                        .background(color.copy(alpha = 0.12f), RoundedCornerShape(4.dp))
                        .padding(horizontal = 6.dp, vertical = 2.dp),
                )
            }
        },
        TableColumn("可空", width = 60.dp) { row ->
            Text(
                if (row.nullable == null) "-" else if (row.nullable) "是" else "否",
                fontSize = 13.sp,
            )
        },
        TableColumn("默认值", width = 100.dp) { row ->
            if (!row.defaultValue.isNullOrEmpty()) {
                Text(row.defaultValue, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            } else {
                Text("-", fontSize = 13.sp, color = PlaceholderColor)
            }
        },
        TableColumn("空值数(合计)", width = 110.dp) { row ->
            val total = nullTotal(row)
            Text(
                formatNumber(total),
                fontSize = 13.sp,
                color = if (total > 0) StatusWarning else Color.Unspecified,
            )
        },
        TableColumn("总行数", width = 100.dp) { row ->
            Text(formatNumber(row.totalRows), fontSize = 13.sp)
        },
        TableColumn("NULL 数", width = 100.dp) { row ->
            Text(formatNumber(row.nullCount), fontSize = 13.sp)
        },
        TableColumn("空串数", width = 100.dp) { row ->
            Text(formatNumber(row.emptyCount), fontSize = 13.sp)
        },
        TableColumn("有值数", width = 100.dp) { row ->
            Text(formatNumber(row.valueCount), fontSize = 13.sp)
        },
        TableColumn("有值率", width = 170.dp) { row ->
            // 有值率着色:>=95 绿,>=80 黄,否则红(与 Vue 版一致)
            val color = when {
                row.fillRate >= 95 -> StatusSuccess
                row.fillRate >= 80 -> StatusWarning
                else -> StatusDanger
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                LinearProgressIndicator(
                    progress = { (row.fillRate / 100.0).toFloat().coerceIn(0f, 1f) },
                    color = color,
                    modifier = Modifier.weight(1f).height(8.dp),
                )
                Text(formatRate(row.fillRate), fontSize = 11.sp, modifier = Modifier.padding(start = 6.dp))
            }
        },
    )

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().padding(16.dp)) {
            // 工具栏
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("字段统计 - ${screen.tableName}", fontSize = 16.sp, fontWeight = FontWeight.Medium)
                Spacer(Modifier.weight(1f))
                OutlinedButton(onClick = {
                    // 原路返回;无回退栈(直接打开)时兜底回任务详情
                    val tab = tabs.active()
                    if (!tabs.back(tab)) {
                        tabs.openScanDetail(screen.jobId, screen.tableName)
                    }
                }) { Text("返回") }
                Spacer(Modifier.width(8.dp))
                Button(
                    enabled = !exporting,
                    onClick = {
                        exporting = true
                        scope.launch {
                            try {
                                val path = withContext(Dispatchers.IO) {
                                    // AWT 文件保存对话框(会阻塞,放 IO 线程)
                                    val dialog = FileDialog(null as Frame?, "导出 Excel", FileDialog.SAVE)
                                    dialog.file = "dq-scan-${screen.jobId}.xlsx"
                                    dialog.isVisible = true
                                    val dir = dialog.directory
                                    val file = dialog.file
                                    if (dir == null || file == null) {
                                        null
                                    } else {
                                        val target = File(dir, file)
                                        target.outputStream().use { out ->
                                            env.exportService.export(screen.jobId, out)
                                        }
                                        target.absolutePath
                                    }
                                }
                                if (path != null) {
                                    showMessage("已导出到 $path")
                                }
                            } catch (e: Exception) {
                                showMessage(e.message ?: "导出失败")
                            } finally {
                                exporting = false
                            }
                        }
                    },
                ) { Text(if (exporting) "导出中..." else "导出 Excel") }
            }
            Spacer(Modifier.height(12.dp))

            // 搜索 / 过滤
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = keyword,
                    onValueChange = { keyword = it },
                    placeholder = { Text("按字段名或注释搜索", fontSize = 13.sp) },
                    singleLine = true,
                    modifier = Modifier.width(280.dp),
                )
                Spacer(Modifier.width(16.dp))
                Checkbox(checked = onlyEmpty, onCheckedChange = { onlyEmpty = it })
                Text("只看空字段(有值数为 0)", fontSize = 13.sp)
            }
            Spacer(Modifier.height(12.dp))

            // 表级汇总
            val info = tableInfo
            if (info != null || columns.isNotEmpty()) {
                Surface(
                    color = Color(0xFFF0F9EB),
                    shape = RoundedCornerShape(4.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Row(
                        Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (info != null) {
                            Text(
                                "总行数: ${formatNumber(info.totalRows ?: info.scannedRows)}",
                                fontSize = 13.sp,
                            )
                            Row(Modifier.padding(start = 24.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text("统计方式: ${if (info.sampled) "采样" else "全量"}", fontSize = 13.sp)
                                if (info.sampled) {
                                    // 采样表的空值数/有值率由样本按比例推算,标注"估算值"
                                    Text(
                                        "估算值",
                                        fontSize = 11.sp,
                                        color = StatusWarning,
                                        modifier = Modifier
                                            .padding(start = 6.dp)
                                            .background(StatusWarning.copy(alpha = 0.12f), RoundedCornerShape(4.dp))
                                            .padding(horizontal = 6.dp, vertical = 2.dp),
                                    )
                                }
                            }
                            if (info.sampled) {
                                Text(
                                    "采样行数: ${formatNumber(info.sampleRows)}",
                                    fontSize = 13.sp,
                                    modifier = Modifier.padding(start = 24.dp),
                                )
                            }
                            Text(
                                "耗时: ${formatDuration(info.startedAt, info.finishedAt)}",
                                fontSize = 13.sp,
                                modifier = Modifier.padding(start = 24.dp),
                            )
                        }
                        Text("字段数量: ${columns.size}", fontSize = 13.sp, modifier = Modifier.padding(start = 24.dp))
                        Row(Modifier.padding(start = 24.dp)) {
                            Text("空字段数量: ", fontSize = 13.sp)
                            Text(
                                "${emptyColumns.size}",
                                fontSize = 13.sp,
                                color = if (emptyColumns.isNotEmpty()) MaterialTheme.colorScheme.primary else Color.Unspecified,
                                modifier = if (emptyColumns.isNotEmpty()) {
                                    Modifier.clickable { onlyEmpty = true }
                                } else {
                                    Modifier
                                },
                            )
                        }
                    }
                }
                Spacer(Modifier.height(12.dp))
            }

            if (loading) {
                LinearProgressIndicator(Modifier.fillMaxWidth().height(2.dp))
            }

            if (!loading && sortedRows.isEmpty()) {
                EmptyHint("暂无数据")
            } else {
                SortableTable(
                    columns = tableColumns,
                    sortSelectors = sortSelectors,
                    sortIndex = sortIndex,
                    sortAsc = sortAsc,
                    onSort = { i ->
                        if (sortIndex == i) sortAsc = !sortAsc else { sortIndex = i; sortAsc = true }
                    },
                    rows = sortedRows,
                    modifier = Modifier.fillMaxWidth().weight(1f),
                )
            }
        }

        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter))
    }
}

/** 有值率展示:整数不带小数,否则保留两位并去掉多余的 0(对齐 el-progress 的百分比文案) */
private fun formatRate(rate: Double): String {
    if (rate == floor(rate)) {
        return "${rate.toLong()}%"
    }
    return "${"%.2f".format(rate).trimEnd('0').trimEnd('.')}%"
}
