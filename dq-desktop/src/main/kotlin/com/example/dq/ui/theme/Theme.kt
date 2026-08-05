package com.example.dq.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// 状态色(对齐原 Element Plus tag 配色)
val StatusSuccess = Color(0xFF67C23A)
val StatusPrimary = Color(0xFF409EFF)
val StatusDanger = Color(0xFFF56C6C)
val StatusWarning = Color(0xFFE6A23C)
val StatusInfo = Color(0xFF909399)

private val LightColors = lightColorScheme(
    primary = Color(0xFF409EFF),
    surfaceVariant = Color(0xFFF5F7FA),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF409EFF),
)

@Composable
fun DqTheme(darkTheme: Boolean, content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        content = content,
    )
}
