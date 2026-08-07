package com.example.dq.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import org.jetbrains.jewel.ui.component.InlineErrorBanner
import org.jetbrains.jewel.ui.component.InlineInformationBanner
import org.jetbrains.jewel.ui.component.InlineWarningBanner
import org.jetbrains.jewel.ui.component.banner.BannerIconActionScope
import org.jetbrains.jewel.ui.icons.AllIconsKeys

/** 提示级别:对应 IntelliJ 内联横幅的信息(蓝)/警告(黄)/错误(红)三种 */
enum class BannerLevel {
    Info,
    Warn,
    Error,
}

/**
 * 页面顶部内联提示条(替代 Snackbar):IntelliJ 风格的横幅,配色跟随 Jewel 主题。
 * onClose 非空时右上角显示关闭图标按钮。
 */
@Composable
fun InlineBanner(
    level: BannerLevel,
    message: String,
    modifier: Modifier = Modifier,
    onClose: (() -> Unit)? = null,
) {
    val iconActions: (BannerIconActionScope.() -> Unit)? = onClose?.let { close ->
        {
            iconAction(
                icon = AllIconsKeys.Actions.Close,
                contentDescription = "关闭",
                tooltipText = "关闭",
                onClick = close,
            )
        }
    }
    when (level) {
        BannerLevel.Info -> InlineInformationBanner(text = message, modifier = modifier, iconActions = iconActions)
        BannerLevel.Warn -> InlineWarningBanner(text = message, modifier = modifier, iconActions = iconActions)
        BannerLevel.Error -> InlineErrorBanner(text = message, modifier = modifier, iconActions = iconActions)
    }
}
