package com.example.dq.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.ui.Orientation
import org.jetbrains.jewel.ui.component.Divider
import org.jetbrains.jewel.ui.component.Text
import com.example.dq.ui.theme.ControlCorner
import com.example.dq.ui.theme.LocalLevels
import com.example.dq.ui.theme.floatingSurface

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
 * 计算表格内容总宽:horizontalScroll 内 max width 无界,fillMaxWidth 失效、weight 列会塌陷为零宽;
 * 显式取 max(视口宽, 定宽列求和 + 每个弹性列 160dp 兜底),让 weight 在有限宽度内分配。
 */
internal fun tableContentWidth(
    columns: List<TableColumn<*>>,
    viewportWidth: Dp,
): Dp {
    val fixedWidth = columns.filter { it.weight <= 0f && it.width != Dp.Unspecified }.fold(0.dp) { acc, c -> acc + c.width }
    val flexibleCount = columns.count { it.weight > 0f || it.width == Dp.Unspecified }
    return maxOf(viewportWidth, fixedWidth + 160.dp * flexibleCount)
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
    // 表格作为浮起容器:柔和投影 + 面板底色 + 圆角(替代硬边框)
    BoxWithConstraints(modifier.floatingSurface(ControlCorner)) {
        Column(Modifier.horizontalScroll(scroll).width(tableContentWidth(columns, maxWidth))) {
        // 表头(淡灰底,比面板色略深一档)与 1px 格子发线:都用低对比层级色,不生硬。
        // 竖线贯穿整行:行高用 IntrinsicSize.Min 测量,垂直 padding 收进单元格
        val levels = LocalLevels.current
        Row(
            Modifier
                .fillMaxWidth()
                .height(IntrinsicSize.Min)
                .background(levels.tableHeaderBg)
                .padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            columns.forEachIndexed { i, col ->
                if (i > 0) ColumnDivider(levels.hairline)
                CellSlot(col, 10.dp) { Text(col.title, fontSize = 12.sp, fontWeight = FontWeight.Medium) }
            }
        }
        Divider(Orientation.Horizontal, thickness = 0.5.dp, color = levels.hairline)
        // 数据行
        LazyColumn {
            items(rows, key = rowKey?.let { k -> { r: T -> k(r) } }) { row ->
                val rowInteraction = remember { MutableInteractionSource() }
                val hovered by rowInteraction.collectIsHoveredAsState()
                Row(
                    Modifier
                        .fillMaxWidth()
                        .height(IntrinsicSize.Min)
                        .hoverable(rowInteraction)
                        .background(if (hovered) levels.tableRowHoverBg else Color.Unspecified)
                        .then(if (onRowClick != null) Modifier.clickable { onRowClick(row) } else Modifier)
                        // 行底发线用 drawBehind 画在行自身上(而不是 Row 外的兄弟 Divider):
                        // LazyColumn item 多子节点布局不可靠,且这样横线必然贯穿整行所有列
                        .drawBehind {
                            val stroke = 0.5.dp.toPx()
                            val y = size.height - stroke / 2
                            drawLine(levels.hairline, Offset(0f, y), Offset(size.width, y), strokeWidth = stroke)
                        }
                        .padding(horizontal = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    columns.forEachIndexed { i, col ->
                        if (i > 0) ColumnDivider(levels.hairline)
                        CellSlot(col, 8.dp) { col.content(row) }
                    }
                }
            }
        }
        }
    }
}

/** 列间竖向发线(撑满行高,依赖父行 IntrinsicSize.Min) */
@Composable
private fun ColumnDivider(color: androidx.compose.ui.graphics.Color) {
    Divider(Orientation.Vertical, thickness = 0.5.dp, color = color, modifier = Modifier.fillMaxHeight())
}

@Composable
private fun <T> RowScope.CellSlot(col: TableColumn<T>, verticalPadding: Dp, content: @Composable () -> Unit) {
    val base = Modifier.padding(horizontal = 4.dp, vertical = verticalPadding)
    if (col.weight > 0f) {
        Row(Modifier.then(base).weight(col.weight)) { content() }
    } else if (col.width != Dp.Unspecified) {
        Row(Modifier.then(base).width(col.width)) { content() }
    } else {
        // 既没给宽度也没给权重:给一个默认权重,避免布局塌陷
        Row(Modifier.then(base).weight(1f)) { content() }
    }
}
