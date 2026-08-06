package com.example.dq

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import org.jetbrains.jewel.intui.window.styling.lightWithLightHeader
import org.jetbrains.skiko.SystemTheme
import org.jetbrains.skiko.currentSystemTheme
import com.example.dq.config.AppConfig
import com.example.dq.ui.App
import com.example.dq.ui.AppEnv
import com.example.dq.ui.TabsModel
import com.example.dq.ui.theme.DqFontFamily
import com.example.dq.ui.theme.DqTheme
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.intui.standalone.theme.IntUiTheme
import org.jetbrains.jewel.intui.standalone.theme.createDefaultTextStyle
import org.jetbrains.jewel.intui.standalone.theme.createEditorTextStyle
import org.jetbrains.jewel.intui.standalone.theme.darkThemeDefinition
import org.jetbrains.jewel.intui.standalone.theme.default
import org.jetbrains.jewel.intui.standalone.theme.lightThemeDefinition
import org.jetbrains.jewel.intui.window.decoratedWindow
import org.jetbrains.jewel.intui.window.styling.dark
import org.jetbrains.jewel.intui.window.styling.light
import org.jetbrains.jewel.ui.ComponentStyling
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.window.DecoratedWindow
import org.jetbrains.jewel.window.TitleBar
import org.jetbrains.jewel.window.newFullscreenControls
import org.jetbrains.jewel.window.styling.TitleBarStyle

fun main() {
    // 手动组装全部依赖(替代 Spring),并完成 H2 建表 + 中断任务恢复
    val env = AppEnv(AppConfig.load())
    val tabs = TabsModel()

    application {
        // 主题模式:Linux(KDE) 下系统主题探测不可靠,提供标题栏手动切换,默认跟随系统
        var themeMode by remember { mutableStateOf(ThemeMode.System) }
        val dark = when (themeMode) {
            ThemeMode.Light -> false
            ThemeMode.Dark -> true
            ThemeMode.System -> currentSystemTheme == SystemTheme.DARK
        }
        val textStyle = JewelTheme.createDefaultTextStyle(fontFamily = DqFontFamily)
        val editorStyle = JewelTheme.createEditorTextStyle(fontFamily = DqFontFamily)
        val themeDefinition =
            if (dark) {
                JewelTheme.darkThemeDefinition(defaultTextStyle = textStyle, editorTextStyle = editorStyle)
            } else {
                JewelTheme.lightThemeDefinition(defaultTextStyle = textStyle, editorTextStyle = editorStyle)
            }

        IntUiTheme(
            theme = themeDefinition,
            styling = ComponentStyling.default()
                // lightWithLightHeader:现代 IDEA 浅色主题的浅色标题栏(light() 是旧式深色标题栏)
                .decoratedWindow(
                    titleBarStyle = if (dark) TitleBarStyle.dark() else TitleBarStyle.lightWithLightHeader()
                ),
        ) {
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
                        Text(
                            text = if (dark) "浅色模式" else "深色模式",
                            fontSize = 12.sp,
                            modifier = Modifier
                                .clickable { themeMode = if (dark) ThemeMode.Light else ThemeMode.Dark }
                                .padding(horizontal = 8.dp, vertical = 4.dp),
                        )
                    }
                }

                // M3 主题暂时保留:未迁移到 Jewel 组件的旧页面仍由它提供配色(跟随同一 dark 值)
                DqTheme(darkTheme = dark) {
                    App(env, tabs)
                }
            }
        }
    }
}

private enum class ThemeMode { Light, Dark, System }
