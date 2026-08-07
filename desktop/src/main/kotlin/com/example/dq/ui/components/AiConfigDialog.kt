package com.example.dq.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.input.OutputTransformation
import androidx.compose.foundation.text.input.TextFieldState
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.dq.model.AiConfigRequest
import com.example.dq.env.ServiceEnv
import com.example.dq.ui.theme.LocalStatusColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.ui.component.CircularProgressIndicator
import org.jetbrains.jewel.ui.component.DefaultButton
import org.jetbrains.jewel.ui.component.OutlinedButton
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.component.TextField

/**
 * AI 配置按钮 + 对话框(平移自 web/src/components/AiConfigDialog.vue)。
 * 自包含组件,直接放在需要的页面工具栏里:`AiConfigDialog(env)`。
 * apiKey 只写不回显:打开时清空输入框,留空保存表示不修改已存 key。
 */
@Composable
fun AiConfigDialog(env: ServiceEnv) {
    var visible by remember { mutableStateOf(false) }

    OutlinedButton(onClick = { visible = true }) { Text("AI 配置") }

    if (visible) {
        AiConfigDialogContent(env, onClose = { visible = false })
    }
}

@Composable
private fun AiConfigDialogContent(env: ServiceEnv, onClose: () -> Unit) {
    var loading by remember { mutableStateOf(true) }
    var saving by remember { mutableStateOf(false) }
    var hasKey by remember { mutableStateOf(false) }
    // Jewel TextField 使用 TextFieldState,保持光标/选区与输出变换
    val baseUrlState = remember { TextFieldState("") }
    val apiKeyState = remember { TextFieldState("") }
    val modelState = remember { TextFieldState("") }
    /** 对话框内消息(校验/加载/保存错误):文案 + 是否错误 */
    var dialogMsg by remember { mutableStateOf<Pair<String, Boolean>?>(null) }
    val scope = rememberCoroutineScope()

    // 打开时加载已存配置;apiKey 明文不回传,只拿 hasKey 标记
    LaunchedEffect(Unit) {
        try {
            val cfg = withContext(Dispatchers.IO) { env.aiConfigService.get() }
            baseUrlState.edit { replace(0, length, cfg.baseUrl ?: "") }
            modelState.edit { replace(0, length, cfg.model ?: "") }
            apiKeyState.edit { replace(0, length, "") }
            hasKey = cfg.hasKey
        } catch (e: Exception) {
            dialogMsg = (e.message ?: "加载配置失败") to true
        } finally {
            loading = false
        }
    }

    // JewelDialog 自带圆角面板与垂直滚动,原 AlertDialog 的 title/text/confirm/dismiss 结构展开为普通布局
    JewelDialog(onDismissRequest = onClose, width = 440.dp) {
        Text("AI 配置", fontWeight = FontWeight.Medium)

        Text(
            "用于生成「表说明」的大模型接口,任意 OpenAI 兼容服务均可(DeepSeek / 通义 / 本地 vLLM 等)。\n" +
                "生成时只发送表结构元数据(表名、字段、注释),不发送业务数据。",
            color = JewelTheme.globalColors.text.disabled,
            fontSize = 13.sp,
        )
        if (loading) {
            CircularProgressIndicator()
        } else {
            TextField(
                state = baseUrlState,
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text("如 https://api.deepseek.com 或 http://localhost:11434/v1") },
            )
            TextField(
                state = apiKeyState,
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text(if (hasKey) "已配置,留空则不修改" else "请输入 API Key") },
                // API Key 掩码显示(替代 PasswordVisualTransformation)
                outputTransformation = OutputTransformation {
                    replace(0, length, "•".repeat(length))
                },
            )
            TextField(
                state = modelState,
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text("如 deepseek-chat / qwen-plus") },
            )
        }
        dialogMsg?.let { (msg, isError) ->
            val statusColors = LocalStatusColors.current
            Text(msg, color = if (isError) statusColors.danger else statusColors.success, fontSize = 13.sp)
        }

        // 底部按钮:取消(左)/保存(右),替代 AlertDialog 的 confirmButton/dismissButton
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
            OutlinedButton(onClick = onClose, enabled = !saving) { Text("取消") }
            DefaultButton(
                enabled = !loading && !saving,
                onClick = {
                    dialogMsg = null
                    if (baseUrlState.text.isBlank() || modelState.text.isBlank()) {
                        dialogMsg = "接口地址和模型不能为空" to true
                        return@DefaultButton
                    }
                    if (!hasKey && apiKeyState.text.isBlank()) {
                        dialogMsg = "请填写 API Key" to true
                        return@DefaultButton
                    }
                    saving = true
                    scope.launch {
                        try {
                            withContext(Dispatchers.IO) {
                                env.aiConfigService.save(
                                    AiConfigRequest(
                                        baseUrlState.text.toString().trim(),
                                        apiKeyState.text.toString(),
                                        modelState.text.toString().trim(),
                                    ),
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
                if (saving) CircularProgressIndicator() else Text("保存")
            }
        }
    }
}
