package com.example.dq.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.clickable
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.dq.ui.views.DashboardView
import com.example.dq.ui.views.DatasourcesView
import com.example.dq.ui.views.ScanDetailView
import com.example.dq.ui.views.ScansView
import com.example.dq.ui.views.SchemasView
import com.example.dq.ui.views.TableColumnsView
import com.example.dq.ui.views.TablesView

/** 应用根组件:顶部页签条 + 当前页签内容 */
@Composable
fun App(env: AppEnv, tabs: TabsModel) {
    Column(Modifier.fillMaxSize()) {
        // 页签条(对应原 App.vue 的顶部 tab)
        // 不用 m3 ScrollableTabRow:动态增删页签时其内部会用旧索引取 tabPositions,
        // 存在已知的 IndexOutOfBounds 竞态;自绘页签条简单可靠
        Row(
            Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .background(MaterialTheme.colorScheme.surfaceVariant),
        ) {
            tabs.tabs.forEach { tab ->
                val selected = tab.key == tabs.activeKey.value
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .clickable { tabs.activeKey.value = tab.key }
                        .background(if (selected) MaterialTheme.colorScheme.surface else Color.Transparent)
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                ) {
                    Text(
                        tab.title.value,
                        fontSize = 13.sp,
                        maxLines = 1,
                        color = if (selected) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (tab.closable) {
                        Text(
                            "  ✕",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.clickable { tabs.close(tab.key) },
                        )
                    }
                }
            }
        }
        HorizontalDivider()

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
