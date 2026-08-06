package com.example.dq.ui.views

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.dq.model.AiConfigRequest
import com.example.dq.model.NullRule
import com.example.dq.model.ScanRequest
import com.example.dq.model.TableStat
import com.example.dq.repository.ScanRepository
import com.example.dq.ui.AppEnv
import com.example.dq.ui.Screen
import com.example.dq.ui.Tab
import com.example.dq.ui.TabsModel
import com.example.dq.ui.components.EmptyHint
import com.example.dq.ui.components.TableColumn
import com.example.dq.ui.components.formatBytes
import com.example.dq.ui.components.formatDateTime
import com.example.dq.ui.components.formatNumber
import com.example.dq.ui.theme.StatusWarning
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.min
import kotlin.math.roundToInt

/** 空值规则编辑行(表单态,提交时转换成 NullRule) */
private class NullRuleRow {
    var column by mutableStateOf("")
    var valuesText by mutableStateOf("")
}

/**
 * 可排序数据表:在 DataTable 的基础上支持点击表头排序。
 * sortSelectors 与 columns 一一对应,null 表示该列不可排序;排序由调用方用 [applySort] 完成。
 */
@Composable
internal fun <T> SortableTable(
    columns: List<TableColumn<T>>,
    sortSelectors: List<((T) -> Comparable<*>?)?>,
    sortIndex: Int,
    sortAsc: Boolean,
    onSort: (Int) -> Unit,
    rows: List<T>,
    modifier: Modifier = Modifier,
) {
    val scroll = rememberScrollState()
    Column(modifier.horizontalScroll(scroll)) {
        Row(
            Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            columns.forEachIndexed { i, col ->
                SortableCellSlot(col) {
                    val sortable = sortSelectors.getOrNull(i) != null
                    val arrow = if (sortIndex == i) (if (sortAsc) " ↑" else " ↓") else ""
                    Text(
                        col.title + arrow,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        modifier = if (sortable) Modifier.clickable { onSort(i) } else Modifier,
                    )
                }
            }
        }
        HorizontalDivider()
        LazyColumn {
            items(rows.size) { idx ->
                val row = rows[idx]
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    columns.forEach { col -> SortableCellSlot(col) { col.content(row) } }
                }
                HorizontalDivider(thickness = 0.5.dp, color = MaterialTheme.colorScheme.outlineVariant)
            }
        }
    }
}

/** 按当前排序状态排序;sortIndex 不可排序或越界时原样返回 */
internal fun <T> applySort(
    rows: List<T>,
    sortSelectors: List<((T) -> Comparable<*>?)?>,
    sortIndex: Int,
    sortAsc: Boolean,
): List<T> {
    val selector = sortSelectors.getOrNull(sortIndex) ?: return rows
    return if (sortAsc) rows.sortedWith(compareBy(selector)) else rows.sortedWith(compareByDescending(selector))
}

@Composable
private fun <T> RowScope.SortableCellSlot(col: TableColumn<T>, content: @Composable () -> Unit) {
    val base = Modifier.padding(horizontal = 4.dp)
    when {
        col.weight > 0f -> Row(base.weight(col.weight)) { content() }
        col.width != Dp.Unspecified -> Row(base.width(col.width)) { content() }
        else -> Row(base.weight(1f)) { content() }
    }
}

/** 次要信息(占位 "-")的颜色,对齐 Element Plus 的 #c0c4cc */
internal val PlaceholderColor = Color(0xFFC0C4CC)

/**
 * 表列表页(平移自 web/src/views/Tables.vue):
 * 表列表(行数/大小/注释/AI 说明列)、搜索/空表过滤、扫描中进度轮询、
 * 扫描发起对话框(空值规则编辑)、批量生成 AI 描述。
 */
@Composable
fun TablesView(env: AppEnv, tabs: TabsModel, tab: Tab, screen: Screen.Tables) {
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val schemaLabel = if (screen.db != null) "${screen.db}.${screen.schema}" else screen.schema

    var tables by remember { mutableStateOf<List<TableStat>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var columnCount by remember { mutableStateOf<Long?>(null) }
    var keyword by remember { mutableStateOf("") }
    var onlyEmpty by remember { mutableStateOf(false) }
    val selected = remember { mutableStateMapOf<String, Boolean>() }
    // 每张表最近一次 DONE 扫描(表名 -> 扫描信息);有值的表名渲染为链接
    var latestScans by remember { mutableStateOf<Map<String, ScanRepository.LatestScan>>(emptyMap()) }
    // 运行中任务里每张未完成表的分段进度
    var runningScans by remember { mutableStateOf<Map<String, ScanRepository.RunningScan>>(emptyMap()) }
    // AI 表说明(表名 -> 说明文字)
    val docs = remember { mutableStateMapOf<String, String>() }
    val docLoading = remember { mutableStateMapOf<String, Boolean>() }
    // 批量生成描述进度
    var batchRunning by remember { mutableStateOf(false) }
    var batchDone by remember { mutableIntStateOf(0) }
    var batchTotal by remember { mutableIntStateOf(0) }
    // 手动编辑描述弹窗
    var docEditTable by remember { mutableStateOf<String?>(null) }
    var docEditText by remember { mutableStateOf("") }
    var docEditSaving by remember { mutableStateOf(false) }
    // AI 配置弹窗
    var aiConfigVisible by remember { mutableStateOf(false) }
    // 扫描对话框
    var scanDialogVisible by remember { mutableStateOf(false) }
    var submitting by remember { mutableStateOf(false) }
    var forceFull by remember { mutableStateOf(false) }
    val nullRules = remember { mutableStateListOf<NullRuleRow>() }
    var maxSizeText by remember { mutableStateOf("") }
    var maxSizeUnit by remember { mutableStateOf("GB") }
    // 行内"扫描"按钮带出的单表目标;为空则按勾选/全库走
    var singleTable by remember { mutableStateOf("") }
    // 排序
    var sortIndex by remember { mutableIntStateOf(-1) }
    var sortAsc by remember { mutableStateOf(true) }

    fun showMessage(msg: String) {
        scope.launch { snackbar.showSnackbar(msg) }
    }

    /** 行数取值:非采样的最新完成扫描是 COUNT(*) 精确值,优先于元数据估算 */
    fun effectiveRows(t: TableStat): Pair<Long?, Boolean> {
        val s = latestScans[t.name]
        if (s != null && !s.sampled && s.totalRows != null) {
            return s.totalRows to true
        }
        return t.estRows to false
    }

    /** 大小取值:已扫描的表用最近一次扫描时记录的快照,否则用当前元数据 */
    fun effectiveSize(t: TableStat): Long? = latestScans[t.name]?.sizeBytes ?: t.sizeBytes

    suspend fun load() {
        loading = true
        try {
            val (tableList, latest, tableDocs, colCount) = withContext(Dispatchers.IO) {
                val list = env.metadataService.listTables(screen.dsId, screen.db, screen.schema)
                    .filter { it.name != null }
                // 最新扫描/表说明查本地 H2,失败仅影响表名可点与说明展示,不阻塞表列表
                val l = runCatching {
                    env.metadataService.latestScanJobsByTable(screen.dsId, screen.db, screen.schema)
                }.getOrDefault(emptyMap())
                val d = runCatching {
                    env.tableDocService.list(screen.dsId, screen.db, screen.schema)
                }.getOrDefault(emptyMap())
                // 字段总数走业务库元数据,失败时显示 -
                val c = runCatching {
                    env.metadataService.countColumns(screen.dsId, screen.db, screen.schema)
                }.getOrNull()
                List4(list, l, d, c)
            }
            tables = tableList
            latestScans = latest
            docs.clear()
            docs.putAll(tableDocs)
            columnCount = colCount
        } catch (e: Exception) {
            showMessage(e.message ?: "加载表列表失败")
        } finally {
            loading = false
        }
    }

    /** 最近一次扫描与表说明的轻量刷新(扫描跑完后调用) */
    suspend fun refreshLatest() {
        val (latest, tableDocs) = withContext(Dispatchers.IO) {
            val l = runCatching {
                env.metadataService.latestScanJobsByTable(screen.dsId, screen.db, screen.schema)
            }.getOrDefault(emptyMap())
            val d = runCatching {
                env.tableDocService.list(screen.dsId, screen.db, screen.schema)
            }.getOrDefault(emptyMap())
            l to d
        }
        latestScans = latest
        docs.clear()
        docs.putAll(tableDocs)
    }

    LaunchedEffect(screen) { load() }

    // 轮询运行中扫描进度(本地 H2 查询,开销极小);有 RUNNING 任务时 2s 一次,
    // 没有时低频探测(10s),能发现别处发起的扫描;刚跑完一轮时顺带刷新最近扫描
    LaunchedEffect(screen) {
        var hadRunning = false
        while (isActive) {
            val running = withContext(Dispatchers.IO) {
                runCatching {
                    env.metadataService.runningScansByTable(screen.dsId, screen.db, screen.schema)
                }.getOrDefault(emptyMap())
            }
            runningScans = running
            if (running.isEmpty() && hadRunning) {
                refreshLatest()
            }
            hadRunning = running.isNotEmpty()
            delay(if (hadRunning) 2000L else 10000L)
        }
    }

    // 行内"生成描述":大模型响应较慢,逐个走 IO 线程
    fun generateDoc(tableName: String) {
        if (docLoading[tableName] == true) return
        docLoading[tableName] = true
        scope.launch {
            try {
                val doc = withContext(Dispatchers.IO) {
                    env.tableDocService.generate(screen.dsId, screen.db, screen.schema, tableName)
                }
                doc.description?.let { docs[tableName] = it }
                showMessage("已生成「$tableName」的表说明")
            } catch (e: Exception) {
                showMessage(e.message ?: "生成表说明失败")
            } finally {
                docLoading[tableName] = false
            }
        }
    }

    // 批量生成勾选的表说明:逐表调单表生成(耗时操作,带进度反馈),单表失败不影响其他
    fun generateDocsBatch() {
        val queue = selected.filterValues { it }.keys.toList()
        if (queue.isEmpty() || batchRunning) return
        batchRunning = true
        batchTotal = queue.size
        batchDone = 0
        scope.launch {
            var ok = 0
            var fail = 0
            for (name in queue) {
                try {
                    val doc = withContext(Dispatchers.IO) {
                        env.tableDocService.generate(screen.dsId, screen.db, screen.schema, name)
                    }
                    docs[name] = doc.description ?: ""
                    ok++
                } catch (e: Exception) {
                    fail++
                    showMessage("「$name」生成失败:" + (e.message ?: "未知错误"))
                }
                batchDone++
            }
            showMessage("表说明生成完成:成功 $ok 张,失败 $fail 张")
            batchRunning = false
        }
    }

    fun openScanDialog(single: String) {
        singleTable = single
        forceFull = false
        nullRules.clear()
        maxSizeText = ""
        maxSizeUnit = "GB"
        scanDialogVisible = true
    }

    fun maxSizeBytes(): Long? {
        val v = maxSizeText.trim().toLongOrNull() ?: return null
        if (v <= 0) return null
        return v * if (maxSizeUnit == "GB") 1073741824L else 1048576L
    }

    fun submitScan() {
        val rules = nullRules
            .filter { it.column.trim().isNotEmpty() && it.valuesText.trim().isNotEmpty() }
            .map { r ->
                NullRule(r.column.trim(), r.valuesText.split(',').map { it.trim() }.filter { it.isNotEmpty() })
            }
            .filter { it.values.isNotEmpty() }
        submitting = true
        scope.launch {
            try {
                val jobId = withContext(Dispatchers.IO) {
                    env.scanService.createScan(
                        ScanRequest(
                            datasourceId = screen.dsId,
                            schema = screen.schema,
                            database = screen.db,
                            tables = if (singleTable.isNotEmpty()) {
                                listOf(singleTable)
                            } else {
                                selected.filterValues { it }.keys.toList().ifEmpty { null }
                            },
                            forceFull = forceFull,
                            nullRules = rules,
                            maxTableSizeBytes = maxSizeBytes(),
                        )
                    )
                }
                showMessage("扫描任务已提交")
                scanDialogVisible = false
                tabs.openScanDetail(jobId, schemaLabel)
            } catch (e: Exception) {
                showMessage(e.message ?: "提交扫描失败")
            } finally {
                submitting = false
            }
        }
    }

    // ---- 派生数据 ----
    val emptyTables = tables.filter { (effectiveRows(it).first ?: 0L) == 0L }
    val totalEstRows = tables.sumOf { effectiveRows(it).first ?: 0L }
    val totalSizeBytes = tables.sumOf { effectiveSize(it) ?: 0L }
    val selectedCount = selected.count { it.value }

    val filtered = run {
        var list = if (onlyEmpty) emptyTables else tables
        val kw = keyword.trim().lowercase()
        if (kw.isNotEmpty()) {
            list = list.filter {
                it.name!!.lowercase().contains(kw) || (it.comment ?: "").lowercase().contains(kw)
            }
        }
        list
    }

    // 当前扫描范围内的表:单表 > 勾选 > 全库
    val scopeTables = when {
        singleTable.isNotEmpty() -> tables.filter { it.name == singleTable }
        selectedCount > 0 -> tables.filter { selected[it.name] == true }
        else -> tables
    }
    // 范围内将被大小上限跳过的表数量(大小未知的表不参与统计)
    val skippedBySize = maxSizeBytes()?.let { limit ->
        scopeTables.count { it.sizeBytes != null && it.sizeBytes > limit }
    } ?: 0

    // 排序 selector 与 columns 顺序对齐:选择/序号/表名/注释/描述/引擎/行数/总大小/最近扫描时间/操作
    val sortSelectors: List<((TableStat) -> Comparable<*>?)?> = listOf(
        null,
        null,
        { it.name },
        null,
        null,
        null,
        { effectiveRows(it).first },
        { effectiveSize(it) },
        { latestScans[it.name]?.finishedAt },
        null,
    )
    val sortedRows = applySort(filtered, sortSelectors, sortIndex, sortAsc)

    val columns = listOf(
        TableColumn<TableStat>("选择", width = 50.dp) { row ->
            Checkbox(
                checked = selected[row.name] == true,
                onCheckedChange = { selected[row.name!!] = it },
            )
        },
        TableColumn("序号", width = 50.dp) { row ->
            Text((sortedRows.indexOf(row) + 1).toString(), fontSize = 13.sp)
        },
        TableColumn("表名", width = 200.dp) { row ->
            val scan = latestScans[row.name]
            if (scan != null) {
                // 点击表名直达该表最近一次扫描完成的字段级结果
                Text(
                    row.name!!,
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.clickable {
                        tabs.navigate(tab, "${row.name} - 字段统计", Screen.TableColumns(scan.jobId, row.name!!))
                    },
                )
            } else {
                Text(row.name!!, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        },
        TableColumn("注释", width = 160.dp) { row ->
            if (!row.comment.isNullOrEmpty()) {
                Text(row.comment, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            } else {
                Text("-", fontSize = 13.sp, color = PlaceholderColor)
            }
        },
        TableColumn("描述", weight = 1f) { row ->
            val name = row.name!!
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (docLoading[name] == true) {
                    CircularProgressIndicator(Modifier.width(16.dp).height(16.dp), strokeWidth = 2.dp)
                } else if (docs[name] != null) {
                    // 已有描述:刷新按钮重新生成(基于最新表结构,覆盖现有描述)
                    TextButton(onClick = { generateDoc(name) }) { Text("重生成", fontSize = 12.sp) }
                } else {
                    TextButton(onClick = { generateDoc(name) }) { Text("生成描述", fontSize = 12.sp) }
                }
                docs[name]?.let { doc ->
                    Text(
                        doc,
                        fontSize = 13.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.clickable {
                            docEditTable = name
                            docEditText = doc
                        },
                    )
                }
            }
        },
        TableColumn("引擎/表空间", width = 120.dp) { row ->
            if (!row.storageInfo.isNullOrEmpty()) {
                Text(row.storageInfo, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            } else {
                Text("-", fontSize = 13.sp, color = PlaceholderColor)
            }
        },
        TableColumn("行数", width = 120.dp) { row ->
            val (rows, exact) = effectiveRows(row)
            if (rows != null) {
                Text(if (exact) formatNumber(rows) else "约 ${formatNumber(rows)}", fontSize = 13.sp)
            } else {
                Text("-", fontSize = 13.sp, color = PlaceholderColor)
            }
        },
        TableColumn("总大小", width = 110.dp) { row ->
            Text(formatBytes(effectiveSize(row)), fontSize = 13.sp)
        },
        TableColumn("最近扫描时间", width = 160.dp) { row ->
            val scan = latestScans[row.name]
            if (scan != null) {
                Text(formatDateTime(scan.finishedAt), fontSize = 13.sp)
            } else {
                Text("-", fontSize = 13.sp, color = PlaceholderColor)
            }
        },
        TableColumn("操作", width = 140.dp) { row ->
            val running = runningScans[row.name]
            if (running != null) {
                // 正在扫描:显示分段进度,点击跳到任务详情;排队中的表显示 0%
                val percent = if (running.totalChunks > 0) {
                    min(100, (running.doneChunks * 100.0 / running.totalChunks).roundToInt())
                } else {
                    0
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.clickable { tabs.openScanDetail(running.jobId, schemaLabel) },
                ) {
                    LinearProgressIndicator(
                        progress = { percent / 100f },
                        modifier = Modifier.weight(1f).height(8.dp),
                    )
                    Text("$percent%", fontSize = 11.sp, modifier = Modifier.padding(start = 4.dp))
                }
            } else {
                TextButton(onClick = { openScanDialog(row.name!!) }) { Text("扫描", fontSize = 13.sp) }
            }
        },
    )

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().padding(16.dp)) {
            // 工具栏
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "表列表 - $schemaLabel",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Medium,
                )
                Spacer(Modifier.weight(1f))
                OutlinedButton(onClick = {
                    if (!tabs.back(tab)) {
                        tabs.navigate(tab, "${screen.dsName} - 库列表", Screen.Schemas(screen.dsId, screen.dsName, screen.db))
                    }
                }) { Text("返回") }
                Spacer(Modifier.width(8.dp))
                OutlinedButton(onClick = {
                    tabs.navigate(tab, "$schemaLabel - 扫描记录", Screen.Scans(screen.dsId, screen.dsName, screen.db, screen.schema))
                }) { Text("扫描记录") }
                Spacer(Modifier.width(8.dp))
                OutlinedButton(onClick = { aiConfigVisible = true }) { Text("AI 配置") }
                Spacer(Modifier.width(8.dp))
                OutlinedButton(
                    enabled = selectedCount > 0 && !batchRunning,
                    onClick = { generateDocsBatch() },
                ) {
                    Text(
                        if (batchRunning) {
                            "生成中 $batchDone/$batchTotal"
                        } else {
                            "生成描述" + if (selectedCount > 0) "($selectedCount)" else ""
                        },
                    )
                }
                Spacer(Modifier.width(8.dp))
                Button(onClick = { openScanDialog("") }) { Text("开始扫描") }
            }
            Spacer(Modifier.height(12.dp))

            // 搜索 / 过滤
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = keyword,
                    onValueChange = { keyword = it },
                    placeholder = { Text("按表名或注释搜索", fontSize = 13.sp) },
                    singleLine = true,
                    modifier = Modifier.width(280.dp),
                )
                Spacer(Modifier.width(16.dp))
                Checkbox(checked = onlyEmpty, onCheckedChange = { onlyEmpty = it })
                Text("只看空表(行数为 0)", fontSize = 13.sp)
            }
            Spacer(Modifier.height(12.dp))

            // 库级汇总(行数/大小按各表有效值求和:扫描准确值优先)
            if (tables.isNotEmpty()) {
                Surface(
                    color = Color(0xFFF0F9EB),
                    shape = RoundedCornerShape(4.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Row(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                        Text("总行数: 约 ${formatNumber(totalEstRows)}", fontSize = 13.sp)
                        Text("总大小: ${formatBytes(totalSizeBytes)}", fontSize = 13.sp, modifier = Modifier.padding(start = 24.dp))
                        Text("表数量: ${tables.size}", fontSize = 13.sp, modifier = Modifier.padding(start = 24.dp))
                        Text(
                            "字段数量: ${if (columnCount == null) "-" else formatNumber(columnCount)}",
                            fontSize = 13.sp,
                            modifier = Modifier.padding(start = 24.dp),
                        )
                        Row(Modifier.padding(start = 24.dp)) {
                            Text("空表数量: ", fontSize = 13.sp)
                            Text(
                                "${emptyTables.size}",
                                fontSize = 13.sp,
                                color = if (emptyTables.isNotEmpty()) MaterialTheme.colorScheme.primary else Color.Unspecified,
                                modifier = if (emptyTables.isNotEmpty()) {
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
                    columns = columns,
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

    // ---- 开始扫描对话框 ----
    if (scanDialogVisible) {
        AlertDialog(
            onDismissRequest = { if (!submitting) scanDialogVisible = false },
            modifier = Modifier.width(640.dp),
            title = { Text("开始扫描") },
            text = {
                Column {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("扫描范围", fontSize = 13.sp, fontWeight = FontWeight.Medium, modifier = Modifier.width(90.dp))
                        Text(
                            when {
                                singleTable.isNotEmpty() -> "仅扫描表:$singleTable"
                                selectedCount > 0 -> "已选 $selectedCount 张表"
                                else -> "未选择表,将扫描全库"
                            },
                            fontSize = 13.sp,
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("强制全量", fontSize = 13.sp, fontWeight = FontWeight.Medium, modifier = Modifier.width(90.dp))
                        Switch(checked = forceFull, onCheckedChange = { forceFull = it })
                    }
                    Text(
                        "超过阈值的表不做采样,逐行精确统计",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 90.dp),
                    )
                    Spacer(Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("表大小上限", fontSize = 13.sp, fontWeight = FontWeight.Medium, modifier = Modifier.width(90.dp))
                        OutlinedTextField(
                            value = maxSizeText,
                            onValueChange = { maxSizeText = it.filter { c -> c.isDigit() } },
                            placeholder = { Text("不限制", fontSize = 13.sp) },
                            singleLine = true,
                            modifier = Modifier.width(120.dp),
                        )
                        Spacer(Modifier.width(8.dp))
                        FilterChip(selected = maxSizeUnit == "MB", onClick = { maxSizeUnit = "MB" }, label = { Text("MB") })
                        Spacer(Modifier.width(4.dp))
                        FilterChip(selected = maxSizeUnit == "GB", onClick = { maxSizeUnit = "GB" }, label = { Text("GB") })
                    }
                    Text(
                        "只扫描不超过该大小的表(按元数据估算的数据+索引大小),留空表示不限制",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 90.dp),
                    )
                    if (skippedBySize > 0) {
                        Text(
                            "当前范围内有 $skippedBySize 张表超过上限,将被跳过",
                            fontSize = 12.sp,
                            color = StatusWarning,
                            modifier = Modifier.padding(start = 90.dp),
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    Row {
                        Text("空值规则", fontSize = 13.sp, fontWeight = FontWeight.Medium, modifier = Modifier.width(90.dp))
                        Column(Modifier.weight(1f)) {
                            nullRules.forEachIndexed { idx, rule ->
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    OutlinedTextField(
                                        value = rule.column,
                                        onValueChange = { rule.column = it },
                                        placeholder = { Text("列名(* 表示所有列)", fontSize = 12.sp) },
                                        singleLine = true,
                                        modifier = Modifier.width(170.dp),
                                    )
                                    Spacer(Modifier.width(8.dp))
                                    OutlinedTextField(
                                        value = rule.valuesText,
                                        onValueChange = { rule.valuesText = it },
                                        placeholder = { Text("视为空的取值,逗号分隔", fontSize = 12.sp) },
                                        singleLine = true,
                                        modifier = Modifier.weight(1f),
                                    )
                                    TextButton(onClick = { nullRules.removeAt(idx) }) {
                                        Text("删除", fontSize = 12.sp, color = MaterialTheme.colorScheme.error)
                                    }
                                }
                                Spacer(Modifier.height(6.dp))
                            }
                            TextButton(onClick = { nullRules.add(NullRuleRow()) }) {
                                Text("+ 添加规则", fontSize = 13.sp)
                            }
                            Text(
                                "例如:列名 *,取值 0,-1 表示所有列中值为 0 或 -1 的也视为空",
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            },
            confirmButton = {
                Button(enabled = !submitting, onClick = { submitScan() }) {
                    if (submitting) {
                        CircularProgressIndicator(Modifier.width(14.dp).height(14.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(6.dp))
                    }
                    Text("提交扫描")
                }
            },
            dismissButton = {
                TextButton(onClick = { scanDialogVisible = false }) { Text("取消") }
            },
        )
    }

    // ---- 手动编辑表描述对话框 ----
    docEditTable?.let { editTable ->
        AlertDialog(
            onDismissRequest = { if (!docEditSaving) docEditTable = null },
            modifier = Modifier.width(560.dp),
            title = { Text("编辑描述 - $editTable") },
            text = {
                OutlinedTextField(
                    value = docEditText,
                    onValueChange = { if (it.length <= 2000) docEditText = it },
                    placeholder = { Text("输入该表的用途描述", fontSize = 13.sp) },
                    minLines = 5,
                    supportingText = { Text("${docEditText.length}/2000", fontSize = 11.sp) },
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            confirmButton = {
                Button(
                    enabled = !docEditSaving,
                    onClick = {
                        val text = docEditText.trim()
                        if (text.isEmpty()) {
                            showMessage("描述不能为空")
                            return@Button
                        }
                        docEditSaving = true
                        scope.launch {
                            try {
                                withContext(Dispatchers.IO) {
                                    env.tableDocService.update(screen.dsId, screen.db, screen.schema, editTable, text)
                                }
                                docs[editTable] = text
                                docEditTable = null
                                showMessage("已保存")
                            } catch (e: Exception) {
                                showMessage(e.message ?: "保存失败")
                            } finally {
                                docEditSaving = false
                            }
                        }
                    },
                ) { Text("保存") }
            },
            dismissButton = {
                TextButton(onClick = { docEditTable = null }) { Text("取消") }
            },
        )
    }

    // ---- AI 配置对话框 ----
    if (aiConfigVisible) {
        AiConfigDialog(env, snackbar, onClose = { aiConfigVisible = false })
    }
}

/** 四元组(load 的并行结果用,data class 自带 componentN 解构) */
private data class List4<A, B, C, D>(val first: A, val second: B, val third: C, val fourth: D)

/**
 * AI 配置对话框(平移自 web/src/components/AiConfigDialog.vue):
 * 配置生成「表说明」的大模型接口,apiKey 留空表示不修改已存 key。
 */
@Composable
private fun AiConfigDialog(env: AppEnv, snackbar: SnackbarHostState, onClose: () -> Unit) {
    val scope = rememberCoroutineScope()
    var baseUrl by remember { mutableStateOf("") }
    var apiKey by remember { mutableStateOf("") }
    var model by remember { mutableStateOf("") }
    var hasKey by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        val cfg = withContext(Dispatchers.IO) { env.aiConfigService.get() }
        baseUrl = cfg.baseUrl ?: ""
        model = cfg.model ?: ""
        apiKey = "" // 明文不回传,留空表示不修改
        hasKey = cfg.hasKey
    }

    AlertDialog(
        onDismissRequest = { if (!saving) onClose() },
        modifier = Modifier.width(520.dp),
        title = { Text("AI 配置") },
        text = {
            Column {
                Text(
                    "用于生成「表说明」的大模型接口,任意 OpenAI 兼容服务均可(DeepSeek / 通义 / 本地 vLLM 等)。生成时只发送表结构元数据(表名、字段、注释),不发送业务数据。",
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = baseUrl,
                    onValueChange = { baseUrl = it },
                    label = { Text("接口地址") },
                    placeholder = { Text("如 https://api.deepseek.com 或 http://localhost:11434/v1", fontSize = 12.sp) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = apiKey,
                    onValueChange = { apiKey = it },
                    label = { Text("API Key") },
                    placeholder = { Text(if (hasKey) "已配置,留空则不修改" else "请输入 API Key", fontSize = 12.sp) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = model,
                    onValueChange = { model = it },
                    label = { Text("模型") },
                    placeholder = { Text("如 deepseek-chat / qwen-plus", fontSize = 12.sp) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            Button(
                enabled = !saving,
                onClick = {
                    if (baseUrl.trim().isEmpty() || model.trim().isEmpty()) {
                        scope.launch { snackbar.showSnackbar("接口地址和模型不能为空") }
                        return@Button
                    }
                    if (!hasKey && apiKey.trim().isEmpty()) {
                        scope.launch { snackbar.showSnackbar("请填写 API Key") }
                        return@Button
                    }
                    saving = true
                    scope.launch {
                        try {
                            withContext(Dispatchers.IO) {
                                env.aiConfigService.save(AiConfigRequest(baseUrl, apiKey, model))
                            }
                            snackbar.showSnackbar("已保存")
                            onClose()
                        } catch (e: Exception) {
                            snackbar.showSnackbar(e.message ?: "保存失败")
                        } finally {
                            saving = false
                        }
                    }
                },
            ) { Text("保存") }
        },
        dismissButton = {
            TextButton(onClick = { onClose() }) { Text("取消") }
        },
    )
}
