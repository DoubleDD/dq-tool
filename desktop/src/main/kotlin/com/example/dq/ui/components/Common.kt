package com.example.dq.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.dq.model.ScanStatus
import com.example.dq.ui.theme.BadgeCorner
import com.example.dq.ui.theme.StatusDanger
import com.example.dq.ui.theme.StatusInfo
import com.example.dq.ui.theme.StatusPrimary
import com.example.dq.ui.theme.StatusSuccess
import com.example.dq.ui.theme.StatusWarning
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.ui.component.Text

/** 状态色(对齐原 Element Plus tag 类型);非组合函数,沿用顶层常量,组合内新代码优先 LocalStatusColors */
fun statusColor(status: ScanStatus?): Color = when (status) {
    ScanStatus.DONE -> StatusSuccess
    ScanStatus.RUNNING -> StatusPrimary
    ScanStatus.FAILED -> StatusDanger
    ScanStatus.INTERRUPTED -> StatusWarning
    ScanStatus.PENDING, ScanStatus.CANCELED, null -> StatusInfo
}

/** 状态标签(对应 el-tag) */
@Composable
fun StatusTag(status: ScanStatus?, modifier: Modifier = Modifier) {
    val color = statusColor(status)
    Text(
        text = statusText(status),
        color = color,
        fontSize = 12.sp,
        modifier = modifier
            .background(color.copy(alpha = 0.12f), RoundedCornerShape(BadgeCorner))
            .padding(horizontal = 6.dp, vertical = 2.dp),
    )
}

/** 页面空态/加载态占位 */
@Composable
fun EmptyHint(text: String, modifier: Modifier = Modifier) {
    Box(modifier.padding(32.dp)) {
        Text(text, color = JewelTheme.globalColors.text.disabled, fontSize = 13.sp)
    }
}

/** 键值对描述行(对应 el-descriptions 的简化版) */
@Composable
fun DescriptionRow(items: List<Pair<String, String>>, modifier: Modifier = Modifier) {
    Row(modifier.padding(vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
        items.forEach { (label, value) ->
            Row {
                Text("$label:", color = JewelTheme.globalColors.text.disabled, fontSize = 13.sp)
                Text(value, fontSize = 13.sp, modifier = Modifier.padding(start = 4.dp))
            }
        }
    }
}
