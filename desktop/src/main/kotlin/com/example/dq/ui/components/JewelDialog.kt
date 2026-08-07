package com.example.dq.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.example.dq.ui.theme.DialogCorner
import com.example.dq.ui.theme.LocalStatusColors
import com.example.dq.ui.theme.floatingSurface
import org.jetbrains.jewel.ui.component.DefaultButton
import org.jetbrains.jewel.ui.component.OutlinedButton
import org.jetbrains.jewel.ui.component.Text

/** Jewel 风格对话框面板:浮起容器(柔和投影 + 面板底色 + 圆角,见 theme.floatingSurface) */
@Composable
fun JewelDialog(
    onDismissRequest: () -> Unit,
    width: Dp = 380.dp,
    content: @Composable ColumnScope.() -> Unit,
) {
    Dialog(onDismissRequest = onDismissRequest) {
        Column(
            Modifier
                .width(width)
                .floatingSurface(DialogCorner)
                .padding(24.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            content = content,
        )
    }
}

/**
 * 共用确认框(Jewel 版,替代原 Common.kt 里的 Material3 ConfirmDialog,后者已随迁移删除)。
 *
 * danger = true 用于删除等破坏性操作:确认按钮文字标红。
 */
@Composable
fun ConfirmDialog(
    title: String,
    message: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    confirmText: String = "确定",
    cancelText: String = "取消",
    danger: Boolean = false,
) {
    JewelDialog(onDismissRequest = onDismiss) {
        Text(title, fontWeight = FontWeight.Medium)
        Text(message)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
            OutlinedButton(onClick = onDismiss) { Text(cancelText) }
            DefaultButton(onClick = onConfirm) {
                Text(
                    confirmText,
                    color = if (danger) LocalStatusColors.current.danger else Color.Unspecified,
                )
            }
        }
    }
}
