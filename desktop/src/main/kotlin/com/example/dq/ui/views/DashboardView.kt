package com.example.dq.ui.views

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.dq.model.ScanJobView
import com.example.dq.model.ScanStatus
import com.example.dq.env.ServiceEnv
import com.example.dq.ui.TabsModel
import com.example.dq.ui.components.BannerLevel
import com.example.dq.ui.components.ConfirmDialog
import com.example.dq.ui.components.DataTable
import com.example.dq.ui.components.EmptyHint
import com.example.dq.ui.components.ExportButton
import com.example.dq.ui.components.InlineBanner
import com.example.dq.ui.components.JobTimeline
import com.example.dq.ui.components.StatusTag
import com.example.dq.ui.components.TableColumn
import com.example.dq.ui.components.formatDateTime
import com.example.dq.ui.components.formatDuration
import com.example.dq.ui.components.textColumn
import com.example.dq.ui.theme.LocalStatusColors
import com.example.dq.ui.theme.floatingSurface
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.ui.component.OutlinedButton
import org.jetbrains.jewel.ui.component.Text
import kotlin.math.roundToInt

/** 库/Schema 展示标签:多库方言带库名前缀 */
private fun schemaLabel(j: ScanJobView): String =
    if (!j.dbName.isNullOrEmpty()) "${j.dbName}.${j.schemaName}" else (j.schemaName ?: "-")

/** 历史表格行:附带序号(对应 el-table 的 type="index" 列) */
private data class HistoryRow(val idx: Int, val job: ScanJobView)

/** 可续扫的状态 */
private fun resumable(status: ScanStatus?): Boolean =
    status == ScanStatus.CANCELED || status == ScanStatus.INTERRUPTED || status == ScanStatus.FAILED

/** 任务看板页(平移自 web/src/views/Dashboard.vue):进行中任务卡片 + 近期历史表格 */
@Composable
fun DashboardView(env: ServiceEnv, tabs: TabsModel) {
    var jobs by remember { mutableStateOf<List<ScanJobView>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var errorMsg by remember { mutableStateOf<String?>(null) }
    var confirmCancel by remember { mutableStateOf<ScanJobView?>(null) }
    var confirmDelete by remember { mutableStateOf<ScanJobView?>(null) }
    // 秒针:驱动卡片上"已耗时"显示每秒刷新
    var tick by remember { mutableLongStateOf(0L) }
    val scope = rememberCoroutineScope()

    suspend fun load() {
        try {
            jobs = withContext(Dispatchers.IO) { env.scanService.listJobs(null, null, null) }
        } catch (e: Exception) {
            errorMsg = e.message ?: "加载失败"
        }
        loading = false
    }

    fun doCancel(jobId: Long) = scope.launch {
        try {
            withContext(Dispatchers.IO) { env.scanService.cancel(jobId) }
            load()
        } catch (e: Exception) {
            errorMsg = e.message
        }
    }

    fun doResume(jobId: Long) = scope.launch {
        try {
            withContext(Dispatchers.IO) { env.scanService.resume(jobId) }
            load()
        } catch (e: Exception) {
            errorMsg = e.message
        }
    }

    fun doDelete(jobId: Long) = scope.launch {
        try {
            withContext(Dispatchers.IO) { env.scanService.delete(jobId) }
            load()
        } catch (e: Exception) {
            errorMsg = e.message
        }
    }

    // 轮询:有进行中任务时每 2 秒刷新;全部终态后停止(手动刷新仍可用)
    LaunchedEffect(Unit) {
        while (isActive) {
            load()
            if (jobs.none { it.status == ScanStatus.PENDING || it.status == ScanStatus.RUNNING }) break
            delay(2000L)
        }
    }
    LaunchedEffect(Unit) {
        while (isActive) {
            delay(1000L)
            tick++
        }
    }

    // 进行中:等待中/运行中;接口按 id 倒序,历史直接截取前 20 条
    val activeJobs = jobs.filter { it.status == ScanStatus.PENDING || it.status == ScanStatus.RUNNING }
    val historyJobs = jobs
        .filter { it.status != ScanStatus.PENDING && it.status != ScanStatus.RUNNING }
        .take(20)
        .mapIndexed { i, j -> HistoryRow(i + 1, j) }

    val statusColors = LocalStatusColors.current

    val historyColumns: List<TableColumn<HistoryRow>> = listOf(
        textColumn("序号", width = 50.dp) { it.idx.toString() },
        textColumn("任务ID", width = 70.dp) { it.job.id.toString() },
        textColumn("数据源", weight = 1f) { it.job.datasourceName ?: "-" },
        textColumn("库/Schema", weight = 1f) { schemaLabel(it.job) },
        textColumn("表(完成/总数)", width = 110.dp) { "${it.job.doneTables}/${it.job.totalTables}" },
        TableColumn<HistoryRow>("状态", width = 150.dp) { row ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                JobTimeline(row.job.events) { StatusTag(row.job.status) }
            }
        },
        textColumn("创建时间", width = 150.dp) { formatDateTime(it.job.createdAt) },
        textColumn("开始时间", width = 150.dp) { formatDateTime(it.job.startedAt) },
        textColumn("完成时间", width = 150.dp) { formatDateTime(it.job.finishedAt) },
        textColumn("耗时", width = 90.dp) { formatDuration(it.job.startedAt, it.job.finishedAt) },
        TableColumn<HistoryRow>("操作", width = 330.dp) { row ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                SmallTextButton("查看") { tabs.openScanDetail(row.job.id, schemaLabel(row.job)) }
                if (resumable(row.job.status)) {
                    SmallTextButton("继续扫描", color = statusColors.primary) { doResume(row.job.id) }
                }
                ExportButton(env, row.job.id)
                SmallTextButton("删除", color = statusColors.danger) { confirmDelete = row.job }
            }
        },
    )

    Column(Modifier.fillMaxSize().padding(20.dp)) {
        // 错误提示条(替代原 AlertDialog 弹窗):常驻条件渲染,可手动关闭
        errorMsg?.let { msg ->
            InlineBanner(
                level = BannerLevel.Error,
                message = msg,
                modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                onClose = { errorMsg = null },
            )
        }

        // 进行中的任务
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("进行中的任务", fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.weight(1f))
            OutlinedButton(onClick = { scope.launch { load() } }) { Text("刷新") }
        }
        when {
            loading && activeJobs.isEmpty() -> EmptyHint("加载中…")
            activeJobs.isEmpty() -> EmptyHint("当前没有正在进行的扫描任务")
            else -> FlowRow(
                Modifier
                    .fillMaxWidth()
                    .heightIn(max = 260.dp)
                    .verticalScroll(rememberScrollState())
                    .padding(vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                activeJobs.forEach { job ->
                    JobCard(
                        job = job,
                        tick = tick,
                        onClick = { tabs.openScanDetail(job.id, schemaLabel(job)) },
                        onCancel = { confirmCancel = job },
                    )
                }
            }
        }

        // 近期历史
        Text("近期历史", fontSize = 15.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(vertical = 12.dp))
        DataTable(
            columns = historyColumns,
            rows = historyJobs,
            // DataTable 内部 LazyColumn 未配重,会超出表头高度,裁剪掉溢出部分
            modifier = Modifier.fillMaxWidth().weight(1f).clipToBounds(),
            rowKey = { it.job.id },
        )
    }

    confirmCancel?.let { job ->
        ConfirmDialog(
            title = "取消确认",
            message = "确定取消该扫描任务吗?",
            onConfirm = {
                confirmCancel = null
                doCancel(job.id)
            },
            onDismiss = { confirmCancel = null },
        )
    }
    confirmDelete?.let { job ->
        ConfirmDialog(
            title = "删除确认",
            message = "确定删除任务 #${job.id} 的扫描记录吗?",
            danger = true,
            onConfirm = {
                confirmDelete = null
                doDelete(job.id)
            },
            onDismiss = { confirmDelete = null },
        )
    }
}

/** 小号文字按钮(表格操作列用):Jewel 无 TextButton,用可点击文字实现 */
@Composable
private fun SmallTextButton(text: String, color: Color = LocalStatusColors.current.primary, onClick: () -> Unit) {
    Text(
        text,
        fontSize = 12.sp,
        color = color,
        modifier = Modifier.clickable(onClick = onClick).padding(horizontal = 6.dp, vertical = 2.dp),
    )
}

/** 进行中任务卡片:环形进度 + 状态/取消 + 元信息(对应 Dashboard.vue 的 job-card) */
@Composable
private fun JobCard(job: ScanJobView, tick: Long, onClick: () -> Unit, onCancel: () -> Unit) {
    // 读取 tick 以订阅秒针,让"已耗时"每秒刷新
    @Suppress("UNUSED_EXPRESSION")
    tick
    val statusColors = LocalStatusColors.current
    Row(
        Modifier
            .width(330.dp)
            .floatingSurface()
            .clickable(onClick = onClick)
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        // 环形进度(PENDING 用 info 灰),中心显示百分比
        Box(Modifier.size(56.dp), contentAlignment = Alignment.Center) {
            ProgressRing(
                progress = (job.progressPercent / 100.0).coerceIn(0.0, 1.0).toFloat(),
                color = if (job.status == ScanStatus.PENDING) statusColors.info else statusColors.primary,
                modifier = Modifier.fillMaxSize(),
            )
            Text("${job.progressPercent.roundToInt()}%", fontSize = 11.sp)
        }
        Column(Modifier.weight(1f)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    schemaLabel(job),
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                Spacer(Modifier.weight(1f))
                StatusTag(job.status)
                // Vue 版是悬停才把状态标签换成取消按钮;桌面端直接并列展示
                SmallTextButton("取消", color = statusColors.danger, onClick = onCancel)
            }
            Text(
                "${job.datasourceName ?: "-"} · 任务 #${job.id}",
                fontSize = 12.sp,
                color = JewelTheme.globalColors.text.disabled,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                "表 ${job.doneTables}/${job.totalTables} · 已耗时 ${formatDuration(job.startedAt, null)}",
                fontSize = 12.sp,
                color = JewelTheme.globalColors.text.disabled,
            )
        }
    }
}

/** 确定性环形进度:Jewel 的 CircularProgressIndicator 只有不确定模式,这里用 Canvas 自绘保持原行为 */
@Composable
private fun ProgressRing(progress: Float, color: Color, modifier: Modifier = Modifier) {
    val trackColor = JewelTheme.globalColors.borders.normal
    Canvas(modifier) {
        val strokeWidth = 4.dp.toPx()
        val inset = strokeWidth / 2
        val arcSize = Size(size.width - strokeWidth, size.height - strokeWidth)
        val topLeft = Offset(inset, inset)
        // 底圈
        drawArc(
            color = trackColor,
            startAngle = 0f,
            sweepAngle = 360f,
            useCenter = false,
            topLeft = topLeft,
            size = arcSize,
            style = Stroke(strokeWidth),
        )
        // 进度弧(从 12 点方向起)
        drawArc(
            color = color,
            startAngle = -90f,
            sweepAngle = 360f * progress,
            useCenter = false,
            topLeft = topLeft,
            size = arcSize,
            style = Stroke(strokeWidth, cap = StrokeCap.Round),
        )
    }
}
