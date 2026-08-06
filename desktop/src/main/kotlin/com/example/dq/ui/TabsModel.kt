package com.example.dq.ui

import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf

/**
 * 页面路由(页签内下钻的目标)。对应原 Vue Router 的 8 条路由:
 * 数据源列表 / 任务看板 / 库列表 / 表列表 / 扫描记录 / 扫描详情 / 字段统计。
 */
sealed interface Screen {
    data object Datasources : Screen
    data object Dashboard : Screen
    /** 库列表;db 为多库方言(如 SQL Server)当前选中的数据库,单库方言为 null */
    data class Schemas(val dsId: Long, val dsName: String, val db: String? = null) : Screen
    data class Tables(val dsId: Long, val dsName: String, val db: String?, val schema: String) : Screen
    data class Scans(val dsId: Long, val dsName: String, val db: String?, val schema: String) : Screen
    data class ScanDetail(val jobId: Long, val label: String) : Screen
    data class TableColumns(val jobId: Long, val tableName: String) : Screen
}

/** 页签:每个数据源/扫描任务一个页签,首页与任务看板固定不可关闭 */
class Tab(
    val key: String,
    title: String,
    screen: Screen,
    val closable: Boolean,
) {
    var title = mutableStateOf(title)
    /** 页签内当前停留的页面(下钻时替换) */
    var screen = mutableStateOf(screen)
    /** 页签内回退栈 */
    val backStack = mutableStateListOf<Screen>()
}

/**
 * 顶部页签状态(平移自 web/src/stores/tabs.js):
 * - 首页、任务看板为固定页签
 * - 每个数据源一个页签,库→表/扫描记录在其内下钻
 * - 每个扫描任务详情一个页签
 */
class TabsModel {
    val tabs = mutableStateListOf(
        Tab("home", "首页", Screen.Datasources, closable = false),
        Tab("dashboard", "任务看板", Screen.Dashboard, closable = false),
    )
    val activeKey = mutableStateOf("home")

    fun active(): Tab = tabs.firstOrNull { it.key == activeKey.value } ?: tabs.first()

    private fun activate(key: String, title: String, screen: Screen, closable: Boolean): Tab {
        var tab = tabs.firstOrNull { it.key == key }
        if (tab == null) {
            tab = Tab(key, title, screen, closable)
            tabs.add(tab)
        } else {
            tab.screen.value = screen
            tab.backStack.clear()
        }
        tab.title.value = title
        activeKey.value = key
        return tab
    }

    /** 打开数据源的库列表页签 */
    fun openDatasource(dsId: Long, dsName: String) =
        activate("ds-$dsId", "$dsName - 库列表", Screen.Schemas(dsId, dsName), closable = true)

    /** 打开扫描任务详情页签 */
    fun openScanDetail(jobId: Long, label: String) =
        activate("scan-$jobId", "$label - 扫描 #$jobId", Screen.ScanDetail(jobId, label), closable = true)

    /** 页签内下钻(可回退) */
    fun navigate(tab: Tab, title: String, screen: Screen) {
        tab.backStack.add(tab.screen.value)
        tab.screen.value = screen
        tab.title.value = title
    }

    /** 页签内回退一级,无可回退时返回 false */
    fun back(tab: Tab): Boolean {
        val prev = tab.backStack.removeLastOrNull() ?: return false
        tab.screen.value = prev
        return true
    }

    /** 关闭页签;关闭当前页签时激活相邻页签 */
    fun close(key: String) {
        val idx = tabs.indexOfFirst { it.key == key }
        if (idx < 0 || !tabs[idx].closable) return
        tabs.removeAt(idx)
        if (activeKey.value == key) {
            activeKey.value = tabs[minOf(idx, tabs.size - 1)].key
        }
    }
}
