package com.example.dq.ui.views

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.input.InputTransformation
import androidx.compose.foundation.text.input.OutputTransformation
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.example.dq.model.DataSourceConfig
import com.example.dq.model.DataSourceRequest
import com.example.dq.model.DbType
import com.example.dq.ui.AppEnv
import com.example.dq.ui.TabsModel
import com.example.dq.ui.components.EmptyHint
import com.example.dq.ui.components.formatBytes
import com.example.dq.ui.components.formatNumber
import com.example.dq.ui.theme.StatusDanger
import com.example.dq.ui.theme.StatusSuccess
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.ui.component.CircularProgressIndicator
import org.jetbrains.jewel.ui.component.DefaultButton
import org.jetbrains.jewel.ui.component.ListComboBox
import org.jetbrains.jewel.ui.component.OutlinedButton
import org.jetbrains.jewel.ui.component.RadioButtonRow
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.component.TextField

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

/** 数据源新增/编辑表单状态;字段使用 TextFieldState,保持光标/选区与输入变换 */
private class DsFormState {
    var id by mutableStateOf<Long?>(null)
    var dbType by mutableStateOf(DbType.MYSQL)

    /** 填写方式:fields=默认(主机/端口拆分填写),url=直接填 JDBC 地址 */
    var inputMode by mutableStateOf("fields")

    val name = TextFieldState("")
    val jdbcUrl = TextFieldState("")
    val host = TextFieldState("")
    val port = TextFieldState("")
    val database = TextFieldState("")
    val username = TextFieldState("")
    val password = TextFieldState("")
    val rowThreshold = TextFieldState("")
    val sizeThresholdBytes = TextFieldState("")

    private fun TextFieldState.set(value: String) {
        edit { replace(0, length, value) }
    }

    fun reset(row: DataSourceConfig?) {
        id = row?.id
        dbType = row?.dbType ?: DbType.MYSQL
        inputMode = "fields"
        name.set(row?.name ?: "")
        jdbcUrl.set(row?.jdbcUrl ?: "")
        host.set("")
        port.set(DEFAULT_PORTS[dbType] ?: "")
        database.set("")
        username.set(row?.username ?: "")
        password.set("")
        rowThreshold.set(row?.rowThreshold?.toString() ?: "")
        sizeThresholdBytes.set(row?.sizeThresholdBytes?.toString() ?: "")
        if (row != null) parseJdbcUrl()
    }

    /** 解析现有 JDBC URL 到主机/端口/数据库(支持 host:// 和 Oracle @// 两种形式) */
    fun parseJdbcUrl() {
        val url = jdbcUrl.text.toString().trim()
        if (url.isEmpty()) return
        val m = Regex("(?:@//|://)([^/:;?]+)(?::(\\d+))?").find(url)
        if (m != null) {
            host.set(m.groupValues[1])
            port.set(m.groupValues[2].ifEmpty { DEFAULT_PORTS[dbType] ?: "" })
        }
        if (dbType == DbType.DM) {
            database.set("")
            return
        }
        if (dbType == DbType.SQLSERVER) {
            val d = Regex("databaseName=([^;]+)", RegexOption.IGNORE_CASE).find(url)
            database.set(d?.groupValues?.get(1) ?: "")
            return
        }
        val d = Regex("(?:@//|://)[^/:;?]+(?::\\d+)?/([^?;]+)").find(url)
        database.set(d?.groupValues?.get(1) ?: "")
    }

    /** 拆分填写模式下,按数据库类型模板拼出 JDBC URL */
    fun buildJdbcUrl(): String {
        val h = host.text.toString().trim()
        val db = database.text.toString().trim()
        val p = port.text.toString().trim()
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
        if (host.text.isBlank()) return "请填写主机"
        if (port.text.isBlank()) return "请填写端口"
        jdbcUrl.set(buildJdbcUrl())
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

    Column(Modifier.fillMaxSize()) {
        // 页头:标题 + 主操作,下方细分隔线(IDE 工具页结构)
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("数据源管理", fontSize = 15.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
            if (list.isNotEmpty()) {
                DefaultButton(onClick = { form.reset(null); showDialog = true }) { Text("新增数据源") }
            }
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(JewelTheme.globalColors.borders.normal))

        // 页面级消息条(替代原 ElMessage)
        pageMsg?.let { (msg, isError) ->
            Text(
                msg,
                color = if (isError) StatusDanger else StatusSuccess,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
            )
        }

        when {
            loading && list.isEmpty() -> EmptyHint("加载中…")
            list.isEmpty() -> Column(
                Modifier.fillMaxSize(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Text("暂无数据源", fontSize = 15.sp, fontWeight = FontWeight.Medium)
                Spacer(Modifier.height(8.dp))
                Text("添加数据库连接后即可开始质量检测", color = JewelTheme.globalColors.text.disabled)
                Spacer(Modifier.height(16.dp))
                DefaultButton(onClick = { form.reset(null); showDialog = true }) { Text("新增数据源") }
            }

            else -> FlowRow(
                Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
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
        JewelDialog(onDismissRequest = { deleteTarget = null }, width = 380.dp) {
            Text("删除确认", fontWeight = FontWeight.Medium)
            Text("确定删除数据源「${row.name}」吗?")
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
                OutlinedButton(onClick = { deleteTarget = null }) { Text("取消") }
                DefaultButton(onClick = {
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
                }) { Text("删除") }
            }
        }
    }
}

/** Jewel 风格的对话框面板:圆角 + 主题描边 + 面板底色 */
@Composable
private fun JewelDialog(
    onDismissRequest: () -> Unit,
    width: androidx.compose.ui.unit.Dp,
    content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit,
) {
    Dialog(onDismissRequest = onDismissRequest) {
        Column(
            Modifier
                .width(width)
                .clip(RoundedCornerShape(8.dp))
                .background(JewelTheme.globalColors.panelBackground)
                .border(1.dp, JewelTheme.globalColors.borders.normal, RoundedCornerShape(8.dp))
                .padding(20.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            content = content,
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
    Column(
        Modifier
            .width(340.dp)
            .border(1.dp, JewelTheme.globalColors.borders.normal, RoundedCornerShape(6.dp))
            .padding(16.dp),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                row.name ?: "",
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Text(row.dbType?.name ?: "-", color = JewelTheme.globalColors.text.disabled)
        }
        Spacer(Modifier.height(8.dp))
        DsField("JDBC", row.jdbcUrl ?: "")
        DsField("用户名", row.username ?: "")
        DsField("阈值", "${formatNumber(row.rowThreshold)} 行 / ${formatBytes(row.sizeThresholdBytes)}")
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = onBrowse) { Text("浏览库") }
            OutlinedButton(onClick = onEdit) { Text("编辑") }
            OutlinedButton(onClick = onDelete) { Text("删除", color = StatusDanger) }
        }
    }
}

/** 卡片内字段行:灰标签 + 值 */
@Composable
private fun DsField(label: String, value: String) {
    Row(Modifier.padding(vertical = 2.dp)) {
        Text(label, color = JewelTheme.globalColors.text.disabled, modifier = Modifier.width(48.dp))
        Text(value, color = JewelTheme.globalColors.text.disabled, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** 带标签的输入框:上方灰色小标签 + Jewel TextField */
@Composable
private fun LabeledField(
    label: String,
    state: TextFieldState,
    modifier: Modifier = Modifier,
    placeholder: String? = null,
    readOnly: Boolean = false,
    digitsOnly: Boolean = false,
    masked: Boolean = false,
) {
    Column(modifier) {
        Text(label, color = JewelTheme.globalColors.text.disabled, fontSize = 12.sp)
        Spacer(Modifier.height(4.dp))
        TextField(
            state = state,
            readOnly = readOnly,
            modifier = Modifier.fillMaxWidth(),
            placeholder = placeholder?.let { p -> ({ Text(p) }) },
            inputTransformation = if (digitsOnly) InputTransformation {
                if (!asCharSequence().all { it.isDigit() }) revertAllChanges()
            } else null,
            outputTransformation = if (masked) OutputTransformation {
                replace(0, length, "•".repeat(length))
            } else null,
        )
    }
}

/** 新增/编辑数据源对话框(Jewel 版:拆分/URL 两种填写方式 + 测试连接) */
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
    val scope = rememberCoroutineScope()

    // 拆分填写模式下实时预览拼出的 JDBC URL(主机未填时不显示)
    val urlPreview =
        if (form.inputMode == "fields" && form.host.text.isNotBlank() && form.port.text.isNotBlank()) {
            form.buildJdbcUrl()
        } else {
            ""
        }

    JewelDialog(onDismissRequest = onDismiss, width = 480.dp) {
        Text(if (form.id != null) "编辑数据源" else "新增数据源", fontWeight = FontWeight.Medium)

        LabeledField("名称 *", form.name, placeholder = "数据源名称")

        // 数据库类型(仅影响示例地址/默认端口/拆分模板,保存时服务层按 URL 推断实际类型)
        Column {
            Text("数据库类型", color = JewelTheme.globalColors.text.disabled, fontSize = 12.sp)
            Spacer(Modifier.height(4.dp))
            val typeNames = DbType.entries.map { it.name }
            ListComboBox(
                items = typeNames,
                selectedIndex = DbType.entries.indexOf(form.dbType),
                onSelectedItemChange = { idx ->
                    form.dbType = DbType.entries[idx]
                    form.port.edit { replace(0, length, DEFAULT_PORTS[form.dbType] ?: "") }
                },
                modifier = Modifier.fillMaxWidth(),
            )
        }

        // 填写方式:默认(拆分)/ JDBC 地址
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("填写方式", color = JewelTheme.globalColors.text.disabled, fontSize = 12.sp)
            RadioButtonRow(
                text = "默认",
                selected = form.inputMode == "fields",
                onClick = {
                    if (form.inputMode != "fields") {
                        form.inputMode = "fields"
                        form.parseJdbcUrl()
                    }
                },
                modifier = Modifier.padding(start = 16.dp),
            )
            RadioButtonRow(
                text = "JDBC 地址",
                selected = form.inputMode == "url",
                onClick = {
                    if (form.inputMode != "url") {
                        form.inputMode = "url"
                        if (form.host.text.isNotBlank()) form.jdbcUrl.edit { replace(0, length, form.buildJdbcUrl()) }
                    }
                },
                modifier = Modifier.padding(start = 8.dp),
            )
        }

        if (form.inputMode == "url") {
            LabeledField("JDBC 地址 *", form.jdbcUrl, placeholder = URL_PLACEHOLDERS[form.dbType] ?: "jdbc:...")
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                LabeledField("主机 *", form.host, placeholder = "IP 或主机名", modifier = Modifier.weight(1f))
                LabeledField("端口 *", form.port, digitsOnly = true, modifier = Modifier.width(110.dp))
            }
            if (form.dbType != DbType.DM) {
                LabeledField(
                    label = if (form.dbType == DbType.ORACLE) "服务名" else "数据库",
                    state = form.database,
                    placeholder = if (form.dbType == DbType.ORACLE) "Oracle 服务名,可留空" else "数据库名,可留空",
                )
            }
        }

        LabeledField("用户名 *", form.username)
        LabeledField(
            label = if (form.id == null) "密码 *" else "密码",
            state = form.password,
            placeholder = if (form.id != null) "留空表示不修改" else "请输入密码",
            masked = true,
        )
        if (form.inputMode == "fields") {
            val previewState = remember(urlPreview) { TextFieldState(urlPreview) }
            LabeledField("JDBC 地址", previewState, readOnly = true, placeholder = "填写主机和端口后自动生成")
        }
        LabeledField("行数阈值", form.rowThreshold, digitsOnly = true)
        LabeledField("大小阈值(字节)", form.sizeThresholdBytes, digitsOnly = true)

        dialogMsg?.let { (msg, isError) ->
            Text(msg, color = if (isError) StatusDanger else StatusSuccess)
        }

        // 底部按钮:测试连接(左) + 取消/保存(右)
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            OutlinedButton(
                enabled = !saving && !testing,
                onClick = {
                    dialogMsg = null
                    form.syncJdbcUrl()?.let { dialogMsg = it to true; return@OutlinedButton }
                    if (form.jdbcUrl.text.isBlank() || form.username.text.isBlank()) {
                        dialogMsg = "请先填写 JDBC 地址和用户名" to true
                        return@OutlinedButton
                    }
                    testing = true
                    scope.launch {
                        try {
                            // 编辑时密码留空表示沿用旧密码,取解密后的真实密码测试
                            val password = form.password.text.toString().ifEmpty {
                                form.id?.let { id ->
                                    withContext(Dispatchers.IO) { env.dataSourceService.get(id).password }
                                } ?: ""
                            }
                            withContext(Dispatchers.IO) {
                                env.dataSourceService.testConnection(
                                    form.jdbcUrl.text.toString().trim(),
                                    form.username.text.toString().trim(),
                                    password,
                                )
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
                if (testing) CircularProgressIndicator() else Text("测试连接")
            }
            Spacer(Modifier.weight(1f))
            OutlinedButton(onClick = onDismiss, enabled = !saving) { Text("取消") }
            Spacer(Modifier.width(8.dp))
            DefaultButton(
                enabled = !saving && !testing,
                onClick = {
                    dialogMsg = null
                    form.syncJdbcUrl()?.let { dialogMsg = it to true; return@DefaultButton }
                    if (form.name.text.isBlank() || form.jdbcUrl.text.isBlank() || form.username.text.isBlank()) {
                        dialogMsg = "请填写名称、JDBC 地址和用户名" to true
                        return@DefaultButton
                    }
                    if (form.id == null && form.password.text.isEmpty()) {
                        dialogMsg = "请填写密码" to true
                        return@DefaultButton
                    }
                    saving = true
                    scope.launch {
                        try {
                            val req = DataSourceRequest(
                                name = form.name.text.toString().trim(),
                                jdbcUrl = form.jdbcUrl.text.toString().trim(),
                                username = form.username.text.toString().trim(),
                                password = form.password.text.toString(),
                                rowThreshold = form.rowThreshold.text.toString().toLongOrNull(),
                                sizeThresholdBytes = form.sizeThresholdBytes.text.toString().toLongOrNull(),
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
                if (saving) CircularProgressIndicator() else Text("保存")
            }
        }
    }
}
