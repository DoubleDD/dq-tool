package com.example.dq.ui.views

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.dq.model.ScanJobView
import com.example.dq.model.ScanStatus
import com.example.dq.model.ScanTableView
import com.example.dq.ui.AppEnv
import com.example.dq.ui.Screen
import com.example.dq.ui.Tab
import com.example.dq.ui.TabsModel
import com.example.dq.ui.components.ConfirmDialog
import com.example.dq.ui.components.DataTable
import com.example.dq.ui.components.DescriptionRow
import com.example.dq.ui.components.EmptyHint
import com.example.dq.ui.components.ExportButton
import com.example.dq.ui.components.StatusTag
import com.example.dq.ui.components.TableColumn
import com.example.dq.ui.components.formatDateTime
import com.example.dq.ui.components.formatDuration
import com.example.dq.ui.components.formatNumber
import com.example.dq.ui.components.statusText
import com.example.dq.ui.components.textColumn
import com.example.dq.ui.theme.StatusDanger
import com.example.dq.ui.theme.StatusWarning
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

/** 终态:停止主动拉取,仅等续扫等操作改变状态后恢复 */
private val TERMINAL = setOf(ScanStatus.DONE, ScanStatus.FAILED, ScanStatus.CANCELED, ScanStatus.INTERRUPTED)

/** 表级进度 0-100(按分段完成比例) */
private fun chunkPercent(t: ScanTableView): Int =
    if (t.totalChunks > 0) minOf(100, (t.doneChunks * 100.0 / t.totalChunks).roundToInt()) else 0

/** 表清单行:附带序号 */
private data class TableRow(val idx: Int, val table: ScanTableView)

/** 扫描详情页(平移自 web/src/views/ScanDetail.vue):任务描述 + 表级进度表 + 取消/续扫/导出 */
@Composable
fun ScanDetailView(env: AppEnv, tabs: TabsModel, tab: Tab, screen: Screen.ScanDetail) {
    var job by remember { mutableStateOf<ScanJobView?>(null) }
    var acting by remember { mutableStateOf(false) }
    var errorMsg by remember { mutableStateOf<String?>(null) }
    var confirmCancel by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    suspend fun fetchJob() {
        try {
            job = withContext(Dispatchers.IO) { env.scanService.getJob(screen.jobId) }
        } catch (e: Exception) {
            errorMsg = e.message
        }
    }

    // 轮询:非终态每 2 秒拉一次;终态后协程空转,续扫把状态改回 RUNNING 即自动恢复拉取
    LaunchedEffect(screen.jobId) {
        while (isActive) {
            val j = job
            if (j == null || j.status !in TERMINAL) fetchJob()
            delay(2000L)
        }
    }

    fun doCancel() = scope.launch {
        acting = true
        try {
            withContext(Dispatchers.IO) { env.scanService.cancel(screen.jobId) }
            fetchJob()
        } catch (e: Exception) {
            errorMsg = e.message
        } finally {
            acting = false
        }
    }

    fun doResume() = scope.launch {
        acting = true
        try {
            withContext(Dispatchers.IO) { env.scanService.resume(screen.jobId) }
            fetchJob()
        } catch (e: Exception) {
            errorMsg = e.message
        } finally {
            acting = false
        }
    }

    // 原路返回;无回退栈(页签直接打开)时兜底回该任务的扫描记录列表
    fun goBack() {
        if (!tabs.back(tab)) {
            val j = job ?: return
            tab.screen.value = Screen.Scans(j.datasourceId, j.datasourceName ?: "", j.dbName, j.schemaName ?: "")
        }
    }

    val j = job
    Column(Modifier.fillMaxSize().padding(16.dp)) {
        if (j == null) {
            EmptyHint(if (errorMsg != null) "加载失败" else "加载中…")
        } else {
            // 标题 + 操作按钮
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("扫描任务 #${j.id}", fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.weight(1f))
                TextButton(onClick = { goBack() }) { Text("返回") }
                if (j.status == ScanStatus.RUNNING) {
                    TextButton(onClick = { confirmCancel = true }, enabled = !acting) {
                        Text("取消", color = StatusDanger)
                    }
                }
                if (j.status == ScanStatus.CANCELED || j.status == ScanStatus.INTERRUPTED || j.status == ScanStatus.FAILED) {
                    Button(onClick = { doResume() }, enabled = !acting) { Text("继续扫描") }
                }
                ExportButton(env, screen.jobId)
            }

            // 任务描述(对应 el-descriptions)
            DescriptionRow(
                listOf(
                    "数据源" to (j.datasourceName ?: "-"),
                    "库" to if (!j.dbName.isNullOrEmpty()) "${j.dbName}.${j.schemaName}" else (j.schemaName ?: "-"),
                    "状态" to statusText(j.status),
                )
            )
            DescriptionRow(
                listOf(
                    "数据库类型" to (j.dbType?.name ?: "-"),
                    "强制全量" to if (j.forceFull) "是" else "否",
                    "创建时间" to formatDateTime(j.createdAt),
                )
            )
            // 空值规则
            Row(Modifier.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("空值规则:", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
                Spacer(Modifier.width(4.dp))
                val rules = j.nullRules.orEmpty()
                if (rules.isEmpty()) {
                    Text("无", fontSize = 13.sp)
                } else {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        rules.forEach { r ->
                            Text(
                                "${r.column ?: "*"}: ${r.values.joinToString(", ")}",
                                fontSize = 12.sp,
                                modifier = Modifier
                                    .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(4.dp))
                                    .padding(horizontal = 6.dp, vertical = 2.dp),
                            )
                        }
                    }
                }
            }
            j.error?.let { err ->
                Row(Modifier.padding(vertical = 4.dp)) {
                    Text("错误信息:", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
                    Text(err, color = StatusDanger, fontSize = 13.sp, modifier = Modifier.padding(start = 4.dp))
                }
            }

            // 总进度
            Spacer(Modifier.height(8.dp))
            Text(
                "总进度(${j.doneTables}/${j.totalTables} 表)",
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            LinearProgressIndicator(
                progress = { (j.progressPercent / 100.0).coerceIn(0.0, 1.0).toFloat() },
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                color = if (j.status == ScanStatus.FAILED) StatusDanger else MaterialTheme.colorScheme.primary,
            )
            // 采样/估算行数说明(对应 Vue 版列头 tooltip)
            Text(
                "采样:超过阈值(默认 100 万行或 10GB)的表只统计样本,结果为估算值;" +
                    "估算行数来自数据库元数据,不是 COUNT(*) 精确值,可能不准确",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))

            // 表级进度表
            val tableRows = j.tables.orEmpty().mapIndexed { i, t -> TableRow(i + 1, t) }
            val columns: List<TableColumn<TableRow>> = listOf(
                textColumn("序号", width = 50.dp) { it.idx.toString() },
                TableColumn<TableRow>("表名", weight = 1.4f) { row ->
                    if (row.table.status == ScanStatus.DONE) {
                        Text(
                            row.table.tableName,
                            fontSize = 13.sp,
                            color = MaterialTheme.colorScheme.primary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.clickable {
                                tabs.navigate(
                                    tab,
                                    "扫描 #${screen.jobId} · ${row.table.tableName}",
                                    Screen.TableColumns(screen.jobId, row.table.tableName),
                                )
                            },
                        )
                    } else {
                        Text(row.table.tableName, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                },
                TableColumn<TableRow>("进度", width = 190.dp) { row ->
                    when {
                        row.table.totalChunks > 0 -> Row(verticalAlignment = Alignment.CenterVertically) {
                            LinearProgressIndicator(
                                progress = { chunkPercent(row.table) / 100f },
                                modifier = Modifier.weight(1f).height(8.dp),
                            )
                            Text(
                                "${chunkPercent(row.table)}%",
                                fontSize = 11.sp,
                                modifier = Modifier.padding(start = 6.dp),
                            )
                        }
                        row.table.status == ScanStatus.RUNNING ->
                            Text("运行中", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        else -> Text("-", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                },
                TableColumn<TableRow>("状态", width = 80.dp) { StatusTag(it.table.status) },
                TableColumn<TableRow>("采样", width = 70.dp) { row ->
                    if (row.table.sampled) {
                        Text(
                            "采样",
                            fontSize = 12.sp,
                            color = StatusWarning,
                            modifier = Modifier
                                .background(StatusWarning.copy(alpha = 0.12f), RoundedCornerShape(4.dp))
                                .padding(horizontal = 6.dp, vertical = 2.dp),
                        )
                    } else {
                        Text("全量", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                },
                textColumn("已扫行数 / 估算行数", width = 180.dp) {
                    "${formatNumber(it.table.scannedRows)} / ${formatNumber(it.table.totalRows ?: it.table.estRows)}"
                },
                textColumn("耗时", width = 100.dp) { formatDuration(it.table.startedAt, it.table.finishedAt) },
                TableColumn<TableRow>("失败原因", weight = 1f) { row ->
                    Text(
                        row.table.error ?: "-",
                        fontSize = 12.sp,
                        color = if (row.table.error != null) StatusDanger else MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
            )
            DataTable(
                columns = columns,
                rows = tableRows,
                // DataTable 内部 LazyColumn 未配重,会超出表头高度,裁剪掉溢出部分
                modifier = Modifier.fillMaxWidth().weight(1f).clipToBounds(),
                rowKey = { it.table.id },
            )
        }
    }

    if (confirmCancel) {
        ConfirmDialog(
            title = "取消确认",
            text = "确定取消该扫描任务吗?",
            onConfirm = {
                confirmCancel = false
                doCancel()
            },
            onDismiss = { confirmCancel = false },
        )
    }
    errorMsg?.let { msg ->
        AlertDialog(
            onDismissRequest = { errorMsg = null },
            title = { Text("提示") },
            text = { Text(msg) },
            confirmButton = { TextButton(onClick = { errorMsg = null }) { Text("确定") } },
        )
    }
}
