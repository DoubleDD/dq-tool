package com.example.dq

import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.example.dq.config.AppConfig
import com.example.dq.ui.App
import com.example.dq.ui.AppEnv
import com.example.dq.ui.TabsModel
import com.example.dq.ui.theme.DqTheme

fun main() {
    // 手动组装全部依赖(替代 Spring),并完成 H2 建表 + 中断任务恢复
    val env = AppEnv(AppConfig.load())
    val tabs = TabsModel()

    application {
        Window(
            onCloseRequest = {
                env.shutdown()
                exitApplication()
            },
            title = "dq-tool 数据质量检测",
            state = rememberWindowState(width = 1280.dp, height = 840.dp),
        ) {
            DqTheme {
                App(env, tabs)
            }
        }
    }
}
