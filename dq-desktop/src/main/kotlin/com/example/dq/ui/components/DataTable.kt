package com.example.dq.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 表格列定义(对应 el-table-column)。
 * width 指定固定宽度;weight > 0 时按权重占满剩余空间。
 */
class TableColumn<T>(
    val title: String,
    val width: Dp = Dp.Unspecified,
    val weight: Float = 0f,
    val content: @Composable (T) -> Unit,
)

/** 简单文本列 */
fun <T> textColumn(
    title: String,
    width: Dp = Dp.Unspecified,
    weight: Float = 0f,
    value: (T) -> String,
): TableColumn<T> = TableColumn(title, width, weight) { row ->
    Text(value(row), fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
}

/**
 * 通用数据表(对应 el-table):固定表头 + 行列表,行可点击。
 * 数据量在本工具场景内(表列表/字段列表,几百到几千行)直接全量渲染 + LazyColumn 足够。
 */
@Composable
fun <T> DataTable(
    columns: List<TableColumn<T>>,
    rows: List<T>,
    modifier: Modifier = Modifier,
    rowKey: ((T) -> Any)? = null,
    onRowClick: ((T) -> Unit)? = null,
) {
    val scroll = rememberScrollState()
    Column(modifier.horizontalScroll(scroll)) {
        // 表头
        Row(
            Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            columns.forEach { col -> CellSlot(col, null) { Text(col.title, fontSize = 12.sp, fontWeight = FontWeight.Medium) } }
        }
        HorizontalDivider()
        // 数据行
        LazyColumn {
            items(rows, key = rowKey?.let { k -> { r: T -> k(r) } }) { row ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .then(if (onRowClick != null) Modifier.clickable { onRowClick(row) } else Modifier)
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    columns.forEach { col -> CellSlot(col, row) { col.content(row) } }
                }
                HorizontalDivider(thickness = 0.5.dp, color = MaterialTheme.colorScheme.outlineVariant)
            }
        }
    }
}

@Composable
private fun <T> RowScope.CellSlot(col: TableColumn<T>, row: T?, content: @Composable () -> Unit) {
    val base = Modifier.padding(horizontal = 4.dp)
    if (col.weight > 0f) {
        Row(Modifier.then(base).weight(col.weight)) { content() }
    } else if (col.width != Dp.Unspecified) {
        Row(Modifier.then(base).width(col.width)) { content() }
    } else {
        // 既没给宽度也没给权重:给一个默认权重,避免布局塌陷
        Row(Modifier.then(base).weight(1f)) { content() }
    }
}
