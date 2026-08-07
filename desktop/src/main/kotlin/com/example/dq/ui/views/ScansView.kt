package com.example.dq.ui.views

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.dq.model.ScanJobView
import com.example.dq.model.ScanStatus
import com.example.dq.env.ServiceEnv
import com.example.dq.ui.Screen
import com.example.dq.ui.Tab
import com.example.dq.ui.TabsModel
import com.example.dq.ui.components.BannerLevel
import com.example.dq.ui.components.ConfirmDialog
import com.example.dq.ui.components.DataTable
import com.example.dq.ui.components.EmptyHint
import com.example.dq.ui.components.InlineBanner
import com.example.dq.ui.components.JobTimeline
import com.example.dq.ui.components.LinearProgress
import com.example.dq.ui.components.StatusTag
import com.example.dq.ui.components.TableColumn
import com.example.dq.ui.components.formatDateTime
import com.example.dq.ui.components.formatDuration
import com.example.dq.ui.theme.LocalStatusColors
import java.awt.FileDialog
import java.awt.Frame
import java.io.File
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.ui.component.Link
import org.jetbrains.jewel.ui.component.OutlinedButton
import org.jetbrains.jewel.ui.component.Text

/** 扫描记录页(对应 Vue 版 Scans.vue):任务历史表格 + 进度 + 状态时间线 + 查看/续扫/导出/删除 */
@Composable
fun ScansView(env: ServiceEnv, tabs: TabsModel, tab: Tab, screen: Screen.Scans) {
    val schema = screen.schema
    val db = screen.db ?: ""
    var jobs by remember { mutableStateOf<List<ScanJobView>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    /** 页面级消息条:文案 + 提示级别(对应 Vue 版 ElMessage 的成功/错误反馈) */
    var message by remember { mutableStateOf<Pair<String, BannerLevel>?>(null) }
    var deleteTarget by remember { mutableStateOf<ScanJobView?>(null) }
    val scope = rememberCoroutineScope()

    suspend fun load() {
        loading = true
        try {
            jobs = withContext(Dispatchers.IO) {
                env.scanService.listJobs(screen.dsId, db.ifBlank { null }, schema.ifBlank { null })
            }
        } catch (e: Exception) {
            message = "加载扫描记录失败: ${e.message}" to BannerLevel.Error
        } finally {
            loading = false
        }
    }

    LaunchedEffect(Unit) {
        load()
        // 有进行中的任务时每 2 秒自动刷新(对应 Vue 版的轮询);切走页签时组合取消,轮询自动停止
        while (isActive) {
            delay(2000)
            if (jobs.any { it.status == ScanStatus.PENDING || it.status == ScanStatus.RUNNING }) load()
        }
    }

    fun resume(job: ScanJobView) {
        scope.launch {
            try {
                withContext(Dispatchers.IO) { env.scanService.resume(job.id) }
                message = "已继续扫描" to BannerLevel.Info
            } catch (e: Exception) {
                message = "续扫失败: ${e.message}" to BannerLevel.Error
            }
            load()
        }
    }

    /** 导出扫描结果 xlsx(对应 Vue 版 ExportButton,桌面版用系统保存对话框选路径) */
    fun export(job: ScanJobView) {
        scope.launch {
            try {
                val dialog = FileDialog(null as Frame?, "导出扫描结果", FileDialog.SAVE)
                dialog.file = "scan-job-${job.id}.xlsx"
                dialog.isVisible = true
                val dir = dialog.directory
                val file = dialog.file
                if (dir != null && file != null) {
                    val target = File(dir, file)
                    withContext(Dispatchers.IO) {
                        target.outputStream().use { env.exportService.export(job.id, it) }
                    }
                    message = "已导出到 ${target.absolutePath}" to BannerLevel.Info
                }
            } catch (e: Exception) {
                message = "导出失败: ${e.message}" to BannerLevel.Error
            }
        }
    }

    val rows = jobs.mapIndexed { i, j -> ScanJobRow(i + 1, j) }

    Column(Modifier.fillMaxSize().padding(20.dp)) {
        // 工具栏
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                "扫描记录" + if (schema.isNotBlank()) " - ${if (db.isNotBlank()) "$db.$schema" else schema}" else "",
                fontSize = 16.sp, modifier = Modifier.weight(1f),
            )
            OutlinedButton(onClick = { if (!tabs.back(tab)) tabs.activeKey.value = "home" }) { Text("返回") }
            Spacer(Modifier.width(8.dp))
            OutlinedButton(onClick = { scope.launch { load() } }) { Text("刷新") }
        }
        // 页面级消息条(替代原临时文字提示):常驻条件渲染,可手动关闭
        message?.let { (msg, level) ->
            InlineBanner(level, msg, modifier = Modifier.padding(top = 8.dp), onClose = { message = null })
        }

        if (loading) LinearProgress(null, Modifier.fillMaxWidth().padding(top = 8.dp))
        if (!loading && rows.isEmpty()) EmptyHint("暂无数据")

        val columns = buildList {
            add(TableColumn<ScanJobRow>("序号", width = 60.dp, content = { row ->
                Text("${row.index}", fontSize = 13.sp)
            }))
            add(TableColumn<ScanJobRow>("任务ID", width = 90.dp, content = { row ->
                Text("${row.job.id}", fontSize = 13.sp)
            }))
            // 未按库过滤时展示数据源与库/Schema 两列(对应 Vue 版 v-if="!schema")
            if (schema.isBlank()) {
                add(TableColumn<ScanJobRow>("数据源", weight = 1.2f, content = { row ->
                    Text(row.job.datasourceName ?: "-", fontSize = 13.sp)
                }))
                add(TableColumn<ScanJobRow>("库/Schema", weight = 1.2f, content = { row ->
                    val j = row.job
                    Text(if (j.dbName != null) "${j.dbName}.${j.schemaName}" else j.schemaName ?: "-", fontSize = 13.sp)
                }))
            }
            add(TableColumn<ScanJobRow>("进度", width = 180.dp, content = { row ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    // 共用进度条不支持自定义颜色,失败任务不再标红(与原 Material 版的行为差异)
                    LinearProgress(
                        (row.job.progressPercent / 100).toFloat().coerceIn(0f, 1f),
                        modifier = Modifier.weight(1f),
                    )
                    Spacer(Modifier.width(6.dp))
                    Text("${row.job.progressPercent.roundToInt()}%", fontSize = 12.sp)
                }
            }))
            add(TableColumn<ScanJobRow>("表(完成/总数)", width = 120.dp, content = { row ->
                Text("${row.job.doneTables}/${row.job.totalTables}", fontSize = 13.sp)
            }))
            add(TableColumn<ScanJobRow>("状态", width = 110.dp, content = { row ->
                JobTimeline(row.job.events) { StatusTag(row.job.status) }
            }))
            add(TableColumn<ScanJobRow>("创建时间", weight = 1.4f, content = { row ->
                Text(formatDateTime(row.job.createdAt), fontSize = 13.sp)
            }))
            add(TableColumn<ScanJobRow>("开始时间", weight = 1.4f, content = { row ->
                Text(formatDateTime(row.job.startedAt), fontSize = 13.sp)
            }))
            add(TableColumn<ScanJobRow>("完成时间", weight = 1.4f, content = { row ->
                Text(formatDateTime(row.job.finishedAt), fontSize = 13.sp)
            }))
            add(TableColumn<ScanJobRow>("耗时", width = 110.dp, content = { row ->
                Text(formatDuration(row.job.startedAt, row.job.finishedAt), fontSize = 13.sp)
            }))
            add(TableColumn<ScanJobRow>("操作", width = 330.dp, content = { row ->
                val job = row.job
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Link("查看", onClick = {
                        val label = if (job.dbName != null) "${job.dbName}.${job.schemaName}" else job.schemaName ?: ""
                        tabs.openScanDetail(job.id, label)
                    })
                    if (job.status == ScanStatus.CANCELED || job.status == ScanStatus.INTERRUPTED
                        || job.status == ScanStatus.FAILED
                    ) {
                        Link("继续扫描", onClick = { resume(job) }, modifier = Modifier.padding(start = 8.dp))
                    }
                    Link("导出", onClick = { export(job) }, modifier = Modifier.padding(start = 8.dp))
                    if (job.status != ScanStatus.PENDING && job.status != ScanStatus.RUNNING) {
                        Link(
                            "删除",
                            onClick = { deleteTarget = job },
                            modifier = Modifier.padding(start = 8.dp),
                            textStyle = JewelTheme.defaultTextStyle.copy(color = LocalStatusColors.current.danger),
                        )
                    }
                }
            }))
        }
        DataTable(columns, rows, rowKey = { it.job.id }, modifier = Modifier.padding(top = 16.dp))
    }

    deleteTarget?.let { job ->
        ConfirmDialog(
            title = "删除确认",
            message = "确定删除任务 #${job.id} 的扫描记录吗?",
            onConfirm = {
                deleteTarget = null
                scope.launch {
                    try {
                        withContext(Dispatchers.IO) { env.scanService.delete(job.id) }
                        message = "删除成功" to BannerLevel.Info
                    } catch (e: Exception) {
                        message = "删除失败: ${e.message}" to BannerLevel.Error
                    }
                    load()
                }
            },
            onDismiss = { deleteTarget = null },
        )
    }
}

/** 表格行:序号 + 任务视图 */
private class ScanJobRow(val index: Int, val job: ScanJobView)
