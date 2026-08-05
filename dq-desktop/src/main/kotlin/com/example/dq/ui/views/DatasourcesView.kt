package com.example.dq.ui.views

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.dq.model.DataSourceConfig
import com.example.dq.model.DataSourceRequest
import com.example.dq.model.DbType
import com.example.dq.ui.AppEnv
import com.example.dq.ui.TabsModel
import com.example.dq.ui.components.ConfirmDialog
import com.example.dq.ui.components.EmptyHint
import com.example.dq.ui.components.formatBytes
import com.example.dq.ui.components.formatNumber
import com.example.dq.ui.theme.StatusDanger
import com.example.dq.ui.theme.StatusSuccess
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 各数据库类型的 JDBC 地址示例(平移自 web/src/views/Datasources.vue) */
private val URL_PLACEHOLDERS = mapOf(
    DbType.MYSQL to "jdbc:mysql://host:3306/",
    DbType.POSTGRESQL to "jdbc:postgresql://host:5432/db",
    DbType.DM to "jdbc:dm://host:5236",
    DbType.KINGBASE to "jdbc:kingbase8://host:54321/db",
    DbType.OCEANBASE to "jdbc:oceanbase://host:2881/",
    DbType.SQLSERVER to "jdbc:sqlserver://host:1433;databaseName=db",
    DbType.ORACLE to "jdbc:oracle:thin:@//host:1521/service",
)

/** 各数据库类型的默认端口 */
private val DEFAULT_PORTS = mapOf(
    DbType.MYSQL to "3306",
    DbType.POSTGRESQL to "5432",
    DbType.DM to "5236",
    DbType.KINGBASE to "54321",
    DbType.OCEANBASE to "2881",
    DbType.SQLSERVER to "1433",
    DbType.ORACLE to "1521",
)

/** 数据源新增/编辑表单状态 */
private class DsFormState {
    var id by mutableStateOf<Long?>(null)
    var name by mutableStateOf("")
    var dbType by mutableStateOf(DbType.MYSQL)

    /** 填写方式:fields=默认(主机/端口拆分填写),url=直接填 JDBC 地址 */
    var inputMode by mutableStateOf("fields")
    var jdbcUrl by mutableStateOf("")
    var host by mutableStateOf("")
    var port by mutableStateOf("")
    var database by mutableStateOf("")
    var username by mutableStateOf("")
    var password by mutableStateOf("")
    var rowThreshold by mutableStateOf("")
    var sizeThresholdBytes by mutableStateOf("")

    fun reset(row: DataSourceConfig?) {
        id = row?.id
        name = row?.name ?: ""
        dbType = row?.dbType ?: DbType.MYSQL
        inputMode = "fields"
        jdbcUrl = row?.jdbcUrl ?: ""
        host = ""
        port = DEFAULT_PORTS[dbType] ?: ""
        database = ""
        username = row?.username ?: ""
        password = ""
        rowThreshold = row?.rowThreshold?.toString() ?: ""
        sizeThresholdBytes = row?.sizeThresholdBytes?.toString() ?: ""
        if (row != null) parseJdbcUrl()
    }

    /** 解析现有 JDBC URL 到主机/端口/数据库(支持 host:// 和 Oracle @// 两种形式) */
    fun parseJdbcUrl() {
        val url = jdbcUrl.trim()
        if (url.isEmpty()) return
        val m = Regex("(?:@//|://)([^/:;?]+)(?::(\\d+))?").find(url)
        if (m != null) {
            host = m.groupValues[1]
            port = m.groupValues[2].ifEmpty { DEFAULT_PORTS[dbType] ?: "" }
        }
        if (dbType == DbType.DM) {
            database = ""
            return
        }
        if (dbType == DbType.SQLSERVER) {
            val d = Regex("databaseName=([^;]+)", RegexOption.IGNORE_CASE).find(url)
            database = d?.groupValues?.get(1) ?: ""
            return
        }
        val d = Regex("(?:@//|://)[^/:;?]+(?::\\d+)?/([^?;]+)").find(url)
        database = d?.groupValues?.get(1) ?: ""
    }

    /** 拆分填写模式下,按数据库类型模板拼出 JDBC URL */
    fun buildJdbcUrl(): String {
        val h = host.trim()
        val db = database.trim()
        val p = port.trim()
        return when (dbType) {
            DbType.MYSQL -> "jdbc:mysql://$h:$p/$db"
            DbType.POSTGRESQL -> "jdbc:postgresql://$h:$p/$db"
            DbType.DM -> "jdbc:dm://$h:$p"
            DbType.KINGBASE -> "jdbc:kingbase8://$h:$p/$db"
            DbType.OCEANBASE -> "jdbc:oceanbase://$h:$p/$db"
            DbType.SQLSERVER -> "jdbc:sqlserver://$h:$p" + if (db.isNotEmpty()) ";databaseName=$db" else ""
            DbType.ORACLE -> "jdbc:oracle:thin:@//$h:$p/$db"
        }
    }

    /** 拆分填写模式下校验字段并把拼好的 URL 写回 jdbcUrl;返回校验错误文案,无错误为 null */
    fun syncJdbcUrl(): String? {
        if (inputMode != "fields") return null
        if (host.isBlank()) return "请填写主机"
        if (port.isBlank()) return "请填写端口"
        jdbcUrl = buildJdbcUrl()
        return null
    }
}

/** 数据源列表页(首页页签):卡片列表 + 新增/编辑/删除/测试连接 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DatasourcesView(env: AppEnv, tabs: TabsModel) {
    var list by remember { mutableStateOf<List<DataSourceConfig>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    /** 页面级消息条:文案 + 是否错误 */
    var pageMsg by remember { mutableStateOf<Pair<String, Boolean>?>(null) }
    var showDialog by remember { mutableStateOf(false) }
    var deleteTarget by remember { mutableStateOf<DataSourceConfig?>(null) }
    val form = remember { DsFormState() }
    val scope = rememberCoroutineScope()

    fun load() {
        scope.launch {
            loading = true
            try {
                list = withContext(Dispatchers.IO) { env.dataSourceService.list() }
            } catch (e: Exception) {
                pageMsg = (e.message ?: "加载失败") to true
            } finally {
                loading = false
            }
        }
    }

    // 首页是常驻页签,首次挂载加载(原 Vue 用 onActivated 每次切回刷新)
    LaunchedEffect(Unit) { load() }

    Column(Modifier.fillMaxSize().padding(16.dp)) {
        // 工具栏
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("数据源管理", fontSize = 16.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
            if (list.isNotEmpty()) {
                Button(onClick = { form.reset(null); showDialog = true }) { Text("新增数据源") }
            }
        }

        // 页面级消息条(替代原 ElMessage)
        pageMsg?.let { (msg, isError) ->
            Text(
                msg,
                color = if (isError) StatusDanger else StatusSuccess,
                fontSize = 13.sp,
                modifier = Modifier.padding(vertical = 8.dp),
            )
        }

        when {
            loading && list.isEmpty() -> EmptyHint("加载中…")
            list.isEmpty() -> Column(Modifier.padding(32.dp)) {
                EmptyHint("暂无数据源")
                Button(onClick = { form.reset(null); showDialog = true }) { Text("新增数据源") }
            }

            else -> FlowRow(
                Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(top = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                list.forEach { row ->
                    DatasourceCard(
                        row = row,
                        onBrowse = { tabs.openDatasource(row.id!!, row.name ?: "") },
                        onEdit = { form.reset(row); showDialog = true },
                        onDelete = { deleteTarget = row },
                    )
                }
            }
        }
    }

    if (showDialog) {
        DatasourceDialog(
            env = env,
            form = form,
            onDismiss = { showDialog = false },
            onSaved = {
                showDialog = false
                pageMsg = "保存成功" to false
                load()
            },
        )
    }

    deleteTarget?.let { row ->
        ConfirmDialog(
            title = "删除确认",
            text = "确定删除数据源「${row.name}」吗?",
            confirmText = "删除",
            onConfirm = {
                deleteTarget = null
                scope.launch {
                    try {
                        withContext(Dispatchers.IO) { env.dataSourceService.delete(row.id!!) }
                        pageMsg = "删除成功" to false
                        load()
                    } catch (e: Exception) {
                        pageMsg = (e.message ?: "删除失败") to true
                    }
                }
            },
            onDismiss = { deleteTarget = null },
        )
    }
}

/** 单个数据源卡片 */
@Composable
private fun DatasourceCard(
    row: DataSourceConfig,
    onBrowse: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    Card(Modifier.width(340.dp)) {
        Column(Modifier.padding(16.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    row.name ?: "",
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                // 数据库类型标签(原 Vue 用 DbTypeIcon SVG 图标,桌面端以文字标签代替)
                Text(
                    row.dbType?.name ?: "-",
                    color = MaterialTheme.colorScheme.primary,
                    fontSize = 12.sp,
                    modifier = Modifier
                        .padding(start = 8.dp),
                )
            }
            DsField("JDBC", row.jdbcUrl ?: "")
            DsField("用户名", row.username ?: "")
            DsField("阈值", "${formatNumber(row.rowThreshold)} 行 / ${formatBytes(row.sizeThresholdBytes)}")
            HorizontalDivider(Modifier.padding(vertical = 8.dp), thickness = 0.5.dp)
            Row {
                TextButton(onClick = onBrowse) { Text("浏览库") }
                TextButton(onClick = onEdit) { Text("编辑") }
                TextButton(onClick = onDelete) { Text("删除", color = StatusDanger) }
            }
        }
    }
}

/** 卡片内字段行:灰标签 + 值 */
@Composable
private fun DsField(label: String, value: String) {
    Row(Modifier.padding(vertical = 2.dp)) {
        Text(
            label,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 13.sp,
            modifier = Modifier.width(48.dp),
        )
        Text(
            value,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 13.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** 新增/编辑数据源对话框(平移原 el-dialog:拆分/URL 两种填写方式 + 测试连接) */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DatasourceDialog(
    env: AppEnv,
    form: DsFormState,
    onDismiss: () -> Unit,
    onSaved: () -> Unit,
) {
    var saving by remember { mutableStateOf(false) }
    var testing by remember { mutableStateOf(false) }
    /** 对话框内消息(校验失败/测试结果):文案 + 是否错误 */
    var dialogMsg by remember { mutableStateOf<Pair<String, Boolean>?>(null) }
    var typeExpanded by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    // 拆分填写模式下实时预览拼出的 JDBC URL(主机未填时不显示)
    val urlPreview =
        if (form.inputMode == "fields" && form.host.isNotBlank() && form.port.isNotBlank()) form.buildJdbcUrl() else ""

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (form.id != null) "编辑数据源" else "新增数据源") },
        text = {
            Column(Modifier.widthIn(max = 480.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = form.name,
                    onValueChange = { form.name = it },
                    label = { Text("名称 *") },
                    placeholder = { Text("数据源名称") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )

                // 数据库类型(仅影响示例地址/默认端口/拆分模板,保存时服务层按 URL 推断实际类型)
                ExposedDropdownMenuBox(expanded = typeExpanded, onExpandedChange = { typeExpanded = it }) {
                    OutlinedTextField(
                        value = form.dbType.name,
                        onValueChange = {},
                        readOnly = true,
                        label = { Text("数据库类型") },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(typeExpanded) },
                        modifier = Modifier.menuAnchor(MenuAnchorType.PrimaryNotEditable).fillMaxWidth(),
                    )
                    ExposedDropdownMenu(expanded = typeExpanded, onDismissRequest = { typeExpanded = false }) {
                        DbType.entries.forEach { t ->
                            DropdownMenuItem(
                                text = { Text(t.name) },
                                onClick = {
                                    form.dbType = t
                                    form.port = DEFAULT_PORTS[t] ?: ""
                                    typeExpanded = false
                                },
                            )
                        }
                    }
                }

                // 填写方式:默认(拆分)/ JDBC 地址
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("填写方式", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(start = 16.dp),
                    ) {
                        RadioButton(
                            selected = form.inputMode == "fields",
                            onClick = {
                                if (form.inputMode != "fields") {
                                    form.inputMode = "fields"
                                    form.parseJdbcUrl()
                                }
                            },
                        )
                        Text("默认", fontSize = 13.sp)
                        RadioButton(
                            selected = form.inputMode == "url",
                            onClick = {
                                if (form.inputMode != "url") {
                                    form.inputMode = "url"
                                    if (form.host.isNotBlank()) form.jdbcUrl = form.buildJdbcUrl()
                                }
                            },
                            modifier = Modifier.padding(start = 12.dp),
                        )
                        Text("JDBC 地址", fontSize = 13.sp)
                    }
                }

                if (form.inputMode == "url") {
                    OutlinedTextField(
                        value = form.jdbcUrl,
                        onValueChange = { form.jdbcUrl = it },
                        label = { Text("JDBC 地址 *") },
                        placeholder = { Text(URL_PLACEHOLDERS[form.dbType] ?: "jdbc:...") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                } else {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = form.host,
                            onValueChange = { form.host = it },
                            label = { Text("主机 *") },
                            placeholder = { Text("IP 或主机名") },
                            singleLine = true,
                            modifier = Modifier.weight(1f),
                        )
                        OutlinedTextField(
                            value = form.port,
                            onValueChange = { v -> form.port = v.filter { it.isDigit() } },
                            label = { Text("端口 *") },
                            singleLine = true,
                            modifier = Modifier.width(110.dp),
                        )
                    }
                    if (form.dbType != DbType.DM) {
                        OutlinedTextField(
                            value = form.database,
                            onValueChange = { form.database = it },
                            label = { Text(if (form.dbType == DbType.ORACLE) "服务名" else "数据库") },
                            placeholder = { Text(if (form.dbType == DbType.ORACLE) "Oracle 服务名,可留空" else "数据库名,可留空") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }

                OutlinedTextField(
                    value = form.username,
                    onValueChange = { form.username = it },
                    label = { Text("用户名 *") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = form.password,
                    onValueChange = { form.password = it },
                    label = { Text(if (form.id == null) "密码 *" else "密码") },
                    placeholder = { Text(if (form.id != null) "留空表示不修改" else "请输入密码") },
                    visualTransformation = PasswordVisualTransformation(),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (form.inputMode == "fields") {
                    OutlinedTextField(
                        value = urlPreview,
                        onValueChange = {},
                        readOnly = true,
                        label = { Text("JDBC 地址") },
                        placeholder = { Text("填写主机和端口后自动生成") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                OutlinedTextField(
                    value = form.rowThreshold,
                    onValueChange = { v -> form.rowThreshold = v.filter { it.isDigit() } },
                    label = { Text("行数阈值") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = form.sizeThresholdBytes,
                    onValueChange = { v -> form.sizeThresholdBytes = v.filter { it.isDigit() } },
                    label = { Text("大小阈值(字节)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )

                dialogMsg?.let { (msg, isError) ->
                    Text(msg, color = if (isError) StatusDanger else StatusSuccess, fontSize = 13.sp)
                }
            }
        },
        confirmButton = {
            Button(
                enabled = !saving && !testing,
                onClick = {
                    dialogMsg = null
                    form.syncJdbcUrl()?.let { dialogMsg = it to true; return@Button }
                    if (form.name.isBlank() || form.jdbcUrl.isBlank() || form.username.isBlank()) {
                        dialogMsg = "请填写名称、JDBC 地址和用户名" to true
                        return@Button
                    }
                    if (form.id == null && form.password.isEmpty()) {
                        dialogMsg = "请填写密码" to true
                        return@Button
                    }
                    saving = true
                    scope.launch {
                        try {
                            val req = DataSourceRequest(
                                name = form.name.trim(),
                                jdbcUrl = form.jdbcUrl.trim(),
                                username = form.username.trim(),
                                password = form.password,
                                rowThreshold = form.rowThreshold.toLongOrNull(),
                                sizeThresholdBytes = form.sizeThresholdBytes.toLongOrNull(),
                            )
                            withContext(Dispatchers.IO) {
                                val id = form.id
                                if (id == null) env.dataSourceService.create(req) else env.dataSourceService.update(id, req)
                            }
                            onSaved()
                        } catch (e: Exception) {
                            dialogMsg = (e.message ?: "保存失败") to true
                        } finally {
                            saving = false
                        }
                    }
                },
            ) {
                if (saving) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                else Text("保存")
            }
        },
        dismissButton = {
            Row {
                TextButton(
                    enabled = !saving && !testing,
                    onClick = {
                        dialogMsg = null
                        form.syncJdbcUrl()?.let { dialogMsg = it to true; return@TextButton }
                        if (form.jdbcUrl.isBlank() || form.username.isBlank()) {
                            dialogMsg = "请先填写 JDBC 地址和用户名" to true
                            return@TextButton
                        }
                        testing = true
                        scope.launch {
                            try {
                                // 编辑时密码留空表示沿用旧密码,取解密后的真实密码测试
                                val password = form.password.ifEmpty {
                                    form.id?.let { id ->
                                        withContext(Dispatchers.IO) { env.dataSourceService.get(id).password }
                                    } ?: ""
                                }
                                withContext(Dispatchers.IO) {
                                    env.dataSourceService.testConnection(form.jdbcUrl.trim(), form.username.trim(), password)
                                }
                                dialogMsg = "连接成功" to false
                            } catch (e: Exception) {
                                dialogMsg = (e.message ?: "连接失败") to true
                            } finally {
                                testing = false
                            }
                        }
                    },
                ) {
                    if (testing) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                    else Text("测试连接")
                }
                TextButton(onClick = onDismiss, enabled = !saving) { Text("取消") }
            }
        },
    )
}
