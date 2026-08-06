package com.example.dq.ui.views

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.dq.model.DbType
import com.example.dq.model.ScanRequest
import com.example.dq.model.ScanStatus
import com.example.dq.model.SchemaStat
import com.example.dq.ui.AppEnv
import com.example.dq.ui.Screen
import com.example.dq.ui.Tab
import com.example.dq.ui.TabsModel
import com.example.dq.ui.components.DataTable
import com.example.dq.ui.components.EmptyHint
import com.example.dq.ui.components.StatusTag
import com.example.dq.ui.components.TableColumn
import com.example.dq.ui.components.formatBytes
import com.example.dq.ui.components.formatDateTime
import com.example.dq.ui.components.formatNumber
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 库列表页(对应 Vue 版 Schemas.vue):库/schema 表格 + 多库切换 + 勾选批量扫描 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SchemasView(env: AppEnv, tabs: TabsModel, tab: Tab, screen: Screen.Schemas) {
    val dsId = screen.dsId
    var schemas by remember { mutableStateOf<List<SchemaStat>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var statsLoaded by remember { mutableStateOf(false) }
    var keyword by remember { mutableStateOf("") }
    var databases by remember { mutableStateOf<List<String>>(emptyList()) }
    var currentDb by remember { mutableStateOf(screen.db ?: "") }
    var selected by remember { mutableStateOf<Set<String>>(emptySet()) }
    var submitting by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    fun isScanning(s: SchemaStat): Boolean =
        s.lastScanStatus == ScanStatus.PENDING.name || s.lastScanStatus == ScanStatus.RUNNING.name

    /** 先渲染库名列表,再异步补表数量与最近扫描(失败不影响列表) */
    suspend fun loadStats() {
        val stats = try {
            withContext(Dispatchers.IO) { env.metadataService.listSchemaStats(dsId, currentDb.ifBlank { null }) }
        } catch (e: Exception) {
            // 统计查询失败(如连接超时)时保留纯名称列表,扫描记录入口不置灰
            statsLoaded = true
            return
        }
        val byName = stats.associateBy { it.name }
        schemas = schemas.map { s -> byName[s.name] ?: s }
        statsLoaded = true
    }

    suspend fun loadSchemas() {
        statsLoaded = false
        val names = withContext(Dispatchers.IO) { env.metadataService.listSchemas(dsId, currentDb.ifBlank { null }) }
        schemas = names.map { SchemaStat(it, null, null, null, null, null, null, null) }
        selected = emptySet()
        loadStats()
    }

    LaunchedEffect(dsId) {
        loading = true
        try {
            val ds = withContext(Dispatchers.IO) { env.dataSourceService.get(dsId) }
            // 仅 SQL Server 支持多库切换(与 Vue 版一致)
            if (ds.dbType == DbType.SQLSERVER) {
                databases = runCatching {
                    withContext(Dispatchers.IO) { env.metadataService.listDatabases(dsId) }
                }.getOrDefault(emptyList())
                if (currentDb.isBlank()) {
                    val fromUrl = parseDefaultDb(ds.jdbcUrl)
                    currentDb = if (fromUrl in databases) fromUrl else databases.firstOrNull() ?: ""
                }
            }
            loadSchemas()
        } catch (e: Exception) {
            message = "加载库列表失败: ${e.message}"
        } finally {
            loading = false
        }
    }

    // 有进行中的任务时每 2 秒刷新统计(对应 Vue 版的轮询);切走页签时组合取消,轮询自动停止
    LaunchedEffect(dsId) {
        while (isActive) {
            delay(2000)
            if (schemas.any { isScanning(it) }) loadStats()
        }
    }

    /** 对给定库逐个提交全库扫描任务(tables=null 即整库),失败的单独计数 */
    fun submitScans(rows: List<SchemaStat>) {
        scope.launch {
            submitting = true
            var ok = 0
            var fail = 0
            for (row in rows) {
                try {
                    withContext(Dispatchers.IO) {
                        env.scanService.createScan(
                            ScanRequest(dsId, row.name, currentDb.ifBlank { null }, null, false, emptyList(), null)
                        )
                    }
                    ok++
                } catch (e: Exception) {
                    fail++
                }
            }
            message = if (fail == 0) "已提交 $ok 个扫描任务" else "已提交 $ok/${ok + fail} 个扫描任务,其余提交失败"
            submitting = false
            loadStats()
        }
    }

    fun goTables(schema: String) {
        tabs.navigate(
            tab, "${screen.dsName} - $schema",
            Screen.Tables(dsId, screen.dsName, currentDb.ifBlank { null }, schema),
        )
    }

    fun goScans(schema: String) {
        tabs.navigate(
            tab, "${screen.dsName} - $schema 扫描记录",
            Screen.Scans(dsId, screen.dsName, currentDb.ifBlank { null }, schema),
        )
    }

    val kw = keyword.trim().lowercase()
    val filtered = if (kw.isEmpty()) schemas else schemas.filter { it.name?.lowercase()?.contains(kw) == true }
    val rows = filtered.mapIndexed { i, s -> SchemaRow(i + 1, s) }

    Column(Modifier.fillMaxSize().padding(16.dp)) {
        // 工具栏
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("库列表 - ${screen.dsName}", fontSize = 16.sp, modifier = Modifier.weight(1f))
            Button(
                onClick = {
                    // 跳过正在扫描的库,避免重复提交
                    val targets = schemas.filter { it.name in selected && !isScanning(it) }
                    if (targets.isEmpty()) message = "选中的库都正在扫描中" else submitScans(targets)
                },
                enabled = selected.isNotEmpty() && !submitting,
            ) { Text(if (selected.isEmpty()) "批量扫描" else "批量扫描(${selected.size})") }
            Spacer(Modifier.width(8.dp))
            OutlinedButton(onClick = { if (!tabs.back(tab)) tabs.activeKey.value = "home" }) { Text("返回数据源") }
        }
        message?.let {
            Text(it, color = MaterialTheme.colorScheme.primary, fontSize = 13.sp, modifier = Modifier.padding(top = 8.dp))
        }

        // 多库方言(SQL Server)的数据库切换
        if (databases.isNotEmpty()) {
            Row(Modifier.padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("数据库", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
                Spacer(Modifier.width(8.dp))
                var dbExpanded by remember { mutableStateOf(false) }
                ExposedDropdownMenuBox(expanded = dbExpanded, onExpandedChange = { dbExpanded = it }) {
                    OutlinedTextField(
                        value = currentDb, onValueChange = {}, readOnly = true, singleLine = true,
                        modifier = Modifier.menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable).width(240.dp),
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(dbExpanded) },
                    )
                    ExposedDropdownMenu(expanded = dbExpanded, onDismissRequest = { dbExpanded = false }) {
                        databases.forEach { d ->
                            DropdownMenuItem(text = { Text(d) }, onClick = {
                                dbExpanded = false
                                if (d != currentDb) {
                                    currentDb = d
                                    scope.launch { loadSchemas() }
                                }
                            })
                        }
                    }
                }
            }
        }

        OutlinedTextField(
            value = keyword, onValueChange = { keyword = it },
            placeholder = { Text("按库名搜索") }, singleLine = true,
            modifier = Modifier.width(280.dp).padding(top = 12.dp),
        )

        if (loading) LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 8.dp))
        if (!loading && rows.isEmpty()) EmptyHint("暂无数据")

        val grey = MaterialTheme.colorScheme.onSurfaceVariant
        val columns = listOf<TableColumn<SchemaRow>>(
            TableColumn("", width = 45.dp, content = { row ->
                Checkbox(
                    checked = row.stat.name in selected,
                    onCheckedChange = { checked ->
                        row.stat.name?.let { name ->
                            selected = if (checked) selected + name else selected - name
                        }
                    },
                )
            }),
            TableColumn("序号", width = 60.dp, content = { row ->
                Text("${row.index}", fontSize = 13.sp)
            }),
            TableColumn("库名(Schema)", weight = 1.6f, content = { row ->
                Text(
                    row.stat.name ?: "",
                    color = MaterialTheme.colorScheme.primary, fontSize = 13.sp,
                    modifier = Modifier.clickable { row.stat.name?.let { goTables(it) } },
                )
            }),
            TableColumn("表数量", width = 100.dp, content = { row ->
                val count = row.stat.tableCount ?: 0
                Text(
                    formatNumber(count.toLong()), fontSize = 13.sp,
                    color = if (count == 0) grey else MaterialTheme.colorScheme.onSurface,
                )
            }),
            TableColumn("占用空间", width = 110.dp, content = { row ->
                Text(formatBytes(row.stat.sizeBytes), fontSize = 13.sp)
            }),
            TableColumn("最近扫描", weight = 2f, content = { row ->
                val s = row.stat
                if (s.lastScanStatus == null) {
                    Text("未扫描", color = grey, fontSize = 13.sp)
                } else {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        StatusTag(runCatching { ScanStatus.valueOf(s.lastScanStatus) }.getOrNull())
                        Spacer(Modifier.width(8.dp))
                        if (isScanning(s)) {
                            // 最近任务的表级进度(完成表数/总表数)
                            val total = s.lastScanTotalTables ?: 0
                            val percent = if (total <= 0) 0 else minOf(100, (s.lastScanDoneTables ?: 0) * 100 / total)
                            LinearProgressIndicator(progress = { percent / 100f }, modifier = Modifier.width(130.dp))
                        } else {
                            Text(formatDateTime(s.lastScanAt), color = grey, fontSize = 13.sp)
                        }
                    }
                }
            }),
            TableColumn("操作", width = 200.dp, content = { row ->
                Row {
                    TextButton(enabled = !isScanning(row.stat), onClick = { submitScans(listOf(row.stat)) }) {
                        Text("扫描")
                    }
                    TextButton(
                        enabled = !statsLoaded || row.stat.lastScanStatus != null,
                        onClick = { row.stat.name?.let { goScans(it) } },
                    ) { Text("扫描记录") }
                }
            }),
        )
        DataTable(columns, rows, rowKey = { it.stat.name ?: "idx-${it.index}" }, modifier = Modifier.padding(top = 12.dp))
    }
}

/** 表格行:过滤后的序号 + 库统计 */
private class SchemaRow(val index: Int, val stat: SchemaStat)

/** 从 jdbcUrl 解析默认库(databaseName=/database=) */
private fun parseDefaultDb(jdbcUrl: String?): String {
    val m = Regex("[;?&]database(?:Name)?=([^;?&]+)", RegexOption.IGNORE_CASE).find(jdbcUrl ?: "") ?: return ""
    return java.net.URLDecoder.decode(m.groupValues[1], Charsets.UTF_8)
}
