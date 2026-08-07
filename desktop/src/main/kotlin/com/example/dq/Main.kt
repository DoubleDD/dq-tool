package com.example.dq

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import org.jetbrains.skiko.SystemTheme
import org.jetbrains.skiko.currentSystemTheme
import com.example.dq.config.AppConfig
import com.example.dq.ui.App
import com.example.dq.env.ServiceEnv
import com.example.dq.ui.TabsModel
import com.example.dq.ui.theme.AppTheme
import com.example.dq.ui.theme.ThemeMode
import org.jetbrains.jewel.foundation.theme.LocalContentColor
import org.jetbrains.jewel.ui.component.Icon
import org.jetbrains.jewel.ui.component.IconButton
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.component.Tooltip
import org.jetbrains.jewel.window.DecoratedWindow
import org.jetbrains.jewel.window.TitleBar
import org.jetbrains.jewel.window.newFullscreenControls

// Jewel Tooltip 底层依赖 foundation 的实验性 TooltipArea API
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
fun main() {
    // 手动组装全部依赖(替代 Spring),并完成 H2 建表 + 中断任务恢复
    val env = ServiceEnv(AppConfig.load())
    val tabs = TabsModel()

    application {
        // 主题模式:Linux(KDE) 下系统主题探测不可靠,提供标题栏图标按钮三档切换(浅色/深色/跟随系统),默认跟随系统
        var themeMode by remember { mutableStateOf(ThemeMode.System) }
        val dark = when (themeMode) {
            ThemeMode.Light -> false
            ThemeMode.Dark -> true
            ThemeMode.System -> currentSystemTheme == SystemTheme.DARK
        }
        // Jewel 主题装配集中在 theme/Theme.kt 的 AppTheme(含字体、标题栏样式、状态色)
        AppTheme(darkTheme = dark) {
            // DecoratedWindow 依赖 JBR 的自定义窗口装饰,标题栏与应用内容融为一体
            DecoratedWindow(
                onCloseRequest = {
                    env.shutdown()
                    exitApplication()
                },
                title = "dq-tool 数据质量检测",
                state = rememberWindowState(width = 1280.dp, height = 840.dp),
            ) {
                TitleBar(Modifier.newFullscreenControls()) {
                    Text(title)
                    Row(
                        Modifier.align(Alignment.End).padding(end = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        // 主题切换:图标按钮三档循环(浅色/深色/跟随系统),图标为当前模式
                        Tooltip(tooltip = { Text("主题:${themeMode.label},点击切换") }) {
                            IconButton(onClick = { themeMode = themeMode.next() }) {
                                Icon(
                                    themeMode.icon,
                                    contentDescription = "主题:${themeMode.label}",
                                    modifier = Modifier.size(16.dp),
                                    tint = LocalContentColor.current,
                                )
                            }
                        }
                    }
                }

                App(env, tabs)
            }
        }
    }
}
