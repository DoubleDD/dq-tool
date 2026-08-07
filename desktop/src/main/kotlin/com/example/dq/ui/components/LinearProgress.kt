package com.example.dq.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import org.jetbrains.jewel.ui.component.HorizontalProgressBar
import org.jetbrains.jewel.ui.component.IndeterminateHorizontalProgressBar

/**
 * 线性进度条(Jewel 主题配色,IntelliJ 风格):
 * progress 为 null 时是不确定模式(循环动画),否则为 0..1 的确定进度,超界自动收敛。
 */
@Composable
fun LinearProgress(progress: Float?, modifier: Modifier = Modifier) {
    if (progress == null) {
        IndeterminateHorizontalProgressBar(modifier)
    } else {
        HorizontalProgressBar(progress = progress.coerceIn(0f, 1f), modifier = modifier)
    }
}
