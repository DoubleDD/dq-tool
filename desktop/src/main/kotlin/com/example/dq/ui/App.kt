package com.example.dq.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.example.dq.env.ServiceEnv
import com.example.dq.ui.theme.LocalLevels
import com.example.dq.ui.views.DashboardView
import com.example.dq.ui.views.DatasourcesView
import com.example.dq.ui.views.ScanDetailView
import com.example.dq.ui.views.ScansView
import com.example.dq.ui.views.SchemasView
import com.example.dq.ui.views.TableColumnsView
import com.example.dq.ui.views.TablesView
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.ui.component.TabData
import org.jetbrains.jewel.ui.component.TabStrip
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.theme.editorTabStyle

/** 应用根组件:顶部页签条 + 当前页签内容 */
@Composable
fun App(env: ServiceEnv, tabs: TabsModel) {
    // 显式铺页面底色(比容器低一层的层级色):DecoratedWindow 内容区默认不绘制背景,
    // 不铺会透出 AWT 窗口默认色,导致深色主题下出现"标题栏深、内容白"的割裂
    Column(Modifier.fillMaxSize().background(LocalLevels.current.page)) {
        // 页签条(对应原 App.vue 的顶部 tab),Jewel TabStrip 提供 IDE 风格的外观与关闭按钮
        TabStrip(
            tabs = tabs.tabs.map { tab ->
                TabData.Default(
                    selected = tab.key == tabs.activeKey.value,
                    closable = tab.closable,
                    onClose = { tabs.close(tab.key) },
                    onClick = { tabs.activeKey.value = tab.key },
                    content = { Text(tab.title.value) },
                )
            },
            style = JewelTheme.editorTabStyle,
        )

        // 当前页签内容
        val tab = tabs.active()
        when (val screen = tab.screen.value) {
            is Screen.Datasources -> DatasourcesView(env, tabs)
            is Screen.Dashboard -> DashboardView(env, tabs)
            is Screen.Schemas -> SchemasView(env, tabs, tab, screen)
            is Screen.Tables -> TablesView(env, tabs, tab, screen)
            is Screen.Scans -> ScansView(env, tabs, tab, screen)
            is Screen.ScanDetail -> ScanDetailView(env, tabs, tab, screen)
            is Screen.TableColumns -> TableColumnsView(env, tabs, screen)
        }
    }
}
