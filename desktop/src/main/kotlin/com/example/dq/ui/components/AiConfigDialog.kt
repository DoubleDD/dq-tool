package com.example.dq.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.dq.model.AiConfigRequest
import com.example.dq.ui.AppEnv
import com.example.dq.ui.theme.StatusDanger
import com.example.dq.ui.theme.StatusSuccess
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * AI 配置按钮 + 对话框(平移自 web/src/components/AiConfigDialog.vue)。
 * 自包含组件,直接放在需要的页面工具栏里:`AiConfigDialog(env)`。
 * apiKey 只写不回显:打开时清空输入框,留空保存表示不修改已存 key。
 */
@Composable
fun AiConfigDialog(env: AppEnv) {
    var visible by remember { mutableStateOf(false) }

    OutlinedButton(onClick = { visible = true }) { Text("AI 配置") }

    if (visible) {
        AiConfigDialogContent(env, onClose = { visible = false })
    }
}

@Composable
private fun AiConfigDialogContent(env: AppEnv, onClose: () -> Unit) {
    var loading by remember { mutableStateOf(true) }
    var saving by remember { mutableStateOf(false) }
    var hasKey by remember { mutableStateOf(false) }
    var baseUrl by remember { mutableStateOf("") }
    var apiKey by remember { mutableStateOf("") }
    var model by remember { mutableStateOf("") }
    /** 对话框内消息(校验/加载/保存错误):文案 + 是否错误 */
    var dialogMsg by remember { mutableStateOf<Pair<String, Boolean>?>(null) }
    val scope = rememberCoroutineScope()

    // 打开时加载已存配置;apiKey 明文不回传,只拿 hasKey 标记
    LaunchedEffect(Unit) {
        try {
            val cfg = withContext(Dispatchers.IO) { env.aiConfigService.get() }
            baseUrl = cfg.baseUrl ?: ""
            model = cfg.model ?: ""
            apiKey = ""
            hasKey = cfg.hasKey
        } catch (e: Exception) {
            dialogMsg = (e.message ?: "加载配置失败") to true
        } finally {
            loading = false
        }
    }

    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("AI 配置") },
        text = {
            Column(
                Modifier.widthIn(max = 440.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    "用于生成「表说明」的大模型接口,任意 OpenAI 兼容服务均可(DeepSeek / 通义 / 本地 vLLM 等)。\n" +
                        "生成时只发送表结构元数据(表名、字段、注释),不发送业务数据。",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 13.sp,
                )
                if (loading) {
                    CircularProgressIndicator(Modifier.padding(vertical = 8.dp).size(24.dp), strokeWidth = 2.dp)
                } else {
                    OutlinedTextField(
                        value = baseUrl,
                        onValueChange = { baseUrl = it },
                        label = { Text("接口地址") },
                        placeholder = { Text("如 https://api.deepseek.com 或 http://localhost:11434/v1") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = apiKey,
                        onValueChange = { apiKey = it },
                        label = { Text("API Key") },
                        placeholder = { Text(if (hasKey) "已配置,留空则不修改" else "请输入 API Key") },
                        visualTransformation = PasswordVisualTransformation(),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = model,
                        onValueChange = { model = it },
                        label = { Text("模型") },
                        placeholder = { Text("如 deepseek-chat / qwen-plus") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                dialogMsg?.let { (msg, isError) ->
                    Text(msg, color = if (isError) StatusDanger else StatusSuccess, fontSize = 13.sp)
                }
            }
        },
        confirmButton = {
            Button(
                enabled = !loading && !saving,
                onClick = {
                    dialogMsg = null
                    if (baseUrl.isBlank() || model.isBlank()) {
                        dialogMsg = "接口地址和模型不能为空" to true
                        return@Button
                    }
                    if (!hasKey && apiKey.isBlank()) {
                        dialogMsg = "请填写 API Key" to true
                        return@Button
                    }
                    saving = true
                    scope.launch {
                        try {
                            withContext(Dispatchers.IO) {
                                env.aiConfigService.save(
                                    AiConfigRequest(baseUrl.trim(), apiKey, model.trim()),
                                )
                            }
                            onClose()
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
            TextButton(onClick = onClose, enabled = !saving) { Text("取消") }
        },
    )
}
