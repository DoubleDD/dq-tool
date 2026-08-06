package com.example.dq.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.dq.model.ScanStatus
import com.example.dq.ui.theme.StatusDanger
import com.example.dq.ui.theme.StatusInfo
import com.example.dq.ui.theme.StatusPrimary
import com.example.dq.ui.theme.StatusSuccess
import com.example.dq.ui.theme.StatusWarning

/** 状态色(对齐原 Element Plus tag 类型) */
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
            .background(color.copy(alpha = 0.12f), RoundedCornerShape(4.dp))
            .padding(horizontal = 6.dp, vertical = 2.dp),
    )
}

/** 通用确认对话框(对应 ElMessageBox.confirm) */
@Composable
fun ConfirmDialog(
    title: String,
    text: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    confirmText: String = "确定",
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(text) },
        confirmButton = { TextButton(onClick = onConfirm) { Text(confirmText) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

/** 页面空态/加载态占位 */
@Composable
fun EmptyHint(text: String, modifier: Modifier = Modifier) {
    Box(modifier.padding(32.dp)) {
        Text(text, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
    }
}

/** 键值对描述行(对应 el-descriptions 的简化版) */
@Composable
fun DescriptionRow(items: List<Pair<String, String>>, modifier: Modifier = Modifier) {
    Row(modifier.padding(vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
        items.forEach { (label, value) ->
            Row {
                Text("$label:", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
                Text(value, fontSize = 13.sp, modifier = Modifier.padding(start = 4.dp))
            }
        }
    }
}
