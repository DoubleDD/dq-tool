package com.example.dq.ui.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.runtime.Composable
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.dq.model.ScanJobEvent
import com.example.dq.model.ScanStatus
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.component.Tooltip

/**
 * 任务状态变更时间线(对应 Vue 版 JobTimeline):
 * 悬停内容时弹出事件列表 [{status, at}],按时间升序;无事件(老数据)时不挂 tooltip。
 * 首次 RUNNING 为"开始",后续 RUNNING 为"继续"(续扫);其余状态直接映射文案。
 */
@Composable
fun JobTimeline(events: List<ScanJobEvent>?, content: @Composable () -> Unit) {
    if (events.isNullOrEmpty()) {
        content()
        return
    }
    val firstRunning = events.indexOfFirst { it.status == ScanStatus.RUNNING }
    // Jewel Tooltip 自带面板样式(阴影/描边/底色/内边距),tooltip 内容直接放列表即可
    // 定位参数类型 TooltipPlacement 仍为 foundation 实验 API,此处 opt-in
    @OptIn(ExperimentalFoundationApi::class)
    Tooltip(
        tooltip = {
            Column {
                events.forEachIndexed { i, e ->
                    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        Text(eventLabel(e.status, i == firstRunning), fontWeight = FontWeight.SemiBold, fontSize = 12.sp)
                        Text(formatDateTime(e.at), fontSize = 12.sp)
                    }
                }
            }
        },
    ) {
        content()
    }
}

/** 事件文案:首次 RUNNING 为"开始",后续 RUNNING 为"继续";其余状态直接映射 */
private fun eventLabel(status: ScanStatus?, isFirstRunning: Boolean): String = when (status) {
    ScanStatus.RUNNING -> if (isFirstRunning) "开始" else "继续"
    ScanStatus.PENDING -> "创建"
    ScanStatus.DONE -> "完成"
    ScanStatus.FAILED -> "失败"
    ScanStatus.CANCELED -> "取消"
    ScanStatus.INTERRUPTED -> "中断"
    null -> "-"
}
