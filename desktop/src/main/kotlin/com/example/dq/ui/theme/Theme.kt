package com.example.dq.ui.theme

import androidx.compose.foundation.background
import androidx.compose.foundation.shape.CornerSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.intui.standalone.styling.Default
import org.jetbrains.jewel.intui.standalone.styling.Outlined
import org.jetbrains.jewel.intui.standalone.styling.Slim
import org.jetbrains.jewel.intui.standalone.styling.Undecorated
import org.jetbrains.jewel.intui.standalone.styling.dark
import org.jetbrains.jewel.intui.standalone.styling.default
import org.jetbrains.jewel.intui.standalone.styling.defaultWithRowCount
import org.jetbrains.jewel.intui.standalone.styling.defaults
import org.jetbrains.jewel.intui.standalone.styling.light
import org.jetbrains.jewel.intui.standalone.styling.outlined
import org.jetbrains.jewel.intui.standalone.styling.undecorated
import org.jetbrains.jewel.intui.standalone.theme.IntUiTheme
import org.jetbrains.jewel.intui.standalone.theme.createDefaultTextStyle
import org.jetbrains.jewel.intui.standalone.theme.createEditorTextStyle
import org.jetbrains.jewel.intui.standalone.theme.dark
import org.jetbrains.jewel.intui.standalone.theme.darkThemeDefinition
import org.jetbrains.jewel.intui.standalone.theme.light
import org.jetbrains.jewel.intui.standalone.theme.lightThemeDefinition
import org.jetbrains.jewel.intui.window.decoratedWindow
import org.jetbrains.jewel.intui.window.styling.dark
import org.jetbrains.jewel.intui.window.styling.lightWithLightHeader
import org.jetbrains.jewel.ui.ComponentStyling
import org.jetbrains.jewel.ui.component.styling.ButtonMetrics
import org.jetbrains.jewel.ui.component.styling.ButtonStyle
import org.jetbrains.jewel.ui.component.styling.ComboBoxMetrics
import org.jetbrains.jewel.ui.component.styling.ComboBoxStyle
import org.jetbrains.jewel.ui.component.styling.DropdownMetrics
import org.jetbrains.jewel.ui.component.styling.DropdownStyle
import org.jetbrains.jewel.ui.component.styling.MenuMetrics
import org.jetbrains.jewel.ui.component.styling.MenuStyle
import org.jetbrains.jewel.ui.component.styling.PopupContainerMetrics
import org.jetbrains.jewel.ui.component.styling.PopupContainerStyle
import org.jetbrains.jewel.ui.component.styling.SegmentedControlButtonMetrics
import org.jetbrains.jewel.ui.component.styling.SegmentedControlButtonStyle
import org.jetbrains.jewel.ui.component.styling.SegmentedControlMetrics
import org.jetbrains.jewel.ui.component.styling.SegmentedControlStyle
import org.jetbrains.jewel.ui.component.styling.TextAreaMetrics
import org.jetbrains.jewel.ui.component.styling.TextAreaStyle
import org.jetbrains.jewel.ui.component.styling.TextFieldMetrics
import org.jetbrains.jewel.ui.component.styling.TextFieldStyle
import org.jetbrains.jewel.window.styling.TitleBarStyle

// 状态色(对齐原 Element Plus tag 配色,浅色主题基准值)。
// 顶层常量保留给非组合场景直接引用(如 Common.kt 的状态→颜色映射);组合代码请改用 LocalStatusColors 跟随明暗主题。
val StatusSuccess = Color(0xFF67C23A)
val StatusPrimary = Color(0xFF409EFF)
val StatusDanger = Color(0xFFF56C6C)
val StatusWarning = Color(0xFFE6A23C)
val StatusInfo = Color(0xFF909399)

/**
 * 全局圆角尺度约定(Jewel 默认控件仅 3-4dp,观感偏"矩形方块",统一放大):
 * 控件(按钮/输入框/下拉/组合框)8dp · 分段控件按钮 6dp · 菜单/弹层 10dp · 徽章/状态 tag 6dp · 卡片/弹窗 12dp。
 * Jewel 组件在 [roundedStyling] 经 ComponentStyling 覆盖;自绘区块(卡片/弹窗/徽章/表格)直接引用这些常量。
 */
val ControlCorner = CornerSize(8.dp)
val SegmentedButtonCorner = CornerSize(6.dp)
val PopupCorner = CornerSize(10.dp)
val BadgeCorner = CornerSize(6.dp)
val CardCorner = CornerSize(12.dp)
val DialogCorner = CornerSize(12.dp)

/**
 * 界面层级配色:页面底色(page)比容器低一层,容器(卡片/表格/弹窗)以 Jewel 面板色为底 + 柔和投影
 * "浮"在页面上,替代硬边框制造层次。由 AppTheme 按明暗主题通过 LocalLevels 提供。
 */
class LevelColors(
    val page: Color,
    val shadowAmbient: Color,
    val shadowSpot: Color,
    // 表格发线与表头底色(对齐 Web 版 --el-border-color-lighter / --dq-table-header-bg)
    val hairline: Color,
    val tableHeaderBg: Color,
    // 表格行 hover 底色(对齐 Web 版 --dq-table-row-hover-bg)
    val tableRowHoverBg: Color,
)

private val LightLevels =
    LevelColors(
        page = Color(0xFFF3F5F9),
        shadowAmbient = Color(0x0F0F172A),
        shadowSpot = Color(0x1A0F172A),
        hairline = Color(0xFFEAEEF5),
        tableHeaderBg = Color(0xFFF8FAFC),
        tableRowHoverBg = Color(0xFFF6F8FC),
    )

private val DarkLevels =
    LevelColors(
        page = Color(0xFF1E2023),
        shadowAmbient = Color(0x59000000),
        shadowSpot = Color(0x73000000),
        hairline = Color(0xFF35383E),
        tableHeaderBg = Color(0xFF26292E),
        tableRowHoverBg = Color(0xFF303338),
    )

/** 层级配色 CompositionLocal:在 AppTheme 作用域内自动跟随明暗主题,缺省为浅色 */
val LocalLevels = staticCompositionLocalOf { LightLevels }

/**
 * 浮起容器修饰符:柔和投影 + 面板底色 + 圆角裁剪,替代 1dp 硬边框。
 * 容器底色取 JewelTheme.globalColors.panelBackground(与弹窗一致),页面底色用 [LevelColors.page] 低一层。
 */
@Composable
fun Modifier.floatingSurface(corner: CornerSize = CardCorner): Modifier {
    val levels = LocalLevels.current
    val shape = RoundedCornerShape(corner)
    return this
        .shadow(6.dp, shape, ambientColor = levels.shadowAmbient, spotColor = levels.shadowSpot)
        .background(JewelTheme.globalColors.panelBackground, shape)
        .clip(shape)
}

/** 状态色集合:由 AppTheme 按明暗主题通过 LocalStatusColors 提供 */
class StatusColors(
    val success: Color,
    val primary: Color,
    val danger: Color,
    val warning: Color,
    val info: Color,
)

private val LightStatusColors =
    StatusColors(
        success = StatusSuccess,
        primary = StatusPrimary,
        danger = StatusDanger,
        warning = StatusWarning,
        info = StatusInfo,
    )

// 深色主题下提高亮度,避免暗底上发闷
private val DarkStatusColors =
    StatusColors(
        success = Color(0xFF7CC655),
        primary = Color(0xFF5CADFF),
        danger = Color(0xFFF78383),
        warning = Color(0xFFEAB35E),
        info = Color(0xFFA6A9AD),
    )

/** 状态色 CompositionLocal:在 AppTheme 作用域内自动跟随明暗主题,缺省为浅色 */
val LocalStatusColors = staticCompositionLocalOf { LightStatusColors }

/**
 * 圆角化的组件样式:等价于 ComponentStyling.default() 的全量 IntUi 默认样式,
 * 仅把控件 metrics 的 cornerSize 按上方尺度约定放大。
 * 注意:default() 额外读取系统滚动条设置(ScrollbarHelper),这里放弃,用库默认滚动条样式。
 */
@Composable
private fun roundedStyling(darkTheme: Boolean): ComponentStyling {
    val styling =
        if (darkTheme) {
            ComponentStyling.dark(
                defaultButtonStyle = ButtonStyle.Default.dark(metrics = ButtonMetrics.default(cornerSize = ControlCorner)),
                outlinedButtonStyle = ButtonStyle.Outlined.dark(metrics = ButtonMetrics.outlined(cornerSize = ControlCorner)),
                defaultSlimButtonStyle = ButtonStyle.Slim.Default.dark(metrics = ButtonMetrics.Slim.default(cornerSize = ControlCorner)),
                outlinedSlimButtonStyle = ButtonStyle.Slim.Outlined.dark(metrics = ButtonMetrics.Slim.outlined(cornerSize = ControlCorner)),
                textFieldStyle = TextFieldStyle.dark(metrics = TextFieldMetrics.defaults(cornerSize = ControlCorner)),
                textAreaStyle = TextAreaStyle.dark(metrics = TextAreaMetrics.defaults(cornerSize = ControlCorner)),
                dropdownStyle = DropdownStyle.Default.dark(metrics = DropdownMetrics.default(cornerSize = ControlCorner)),
                undecoratedDropdownStyle = DropdownStyle.Undecorated.dark(metrics = DropdownMetrics.undecorated(cornerSize = ControlCorner)),
                comboBoxStyle = ComboBoxStyle.Default.dark(metrics = ComboBoxMetrics.defaultWithRowCount(cornerSize = ControlCorner)),
                segmentedControlStyle = SegmentedControlStyle.dark(metrics = SegmentedControlMetrics.defaults(cornerSize = ControlCorner)),
                segmentedControlButtonStyle = SegmentedControlButtonStyle.dark(metrics = SegmentedControlButtonMetrics.defaults(cornerSize = SegmentedButtonCorner)),
                menuStyle = MenuStyle.dark(metrics = MenuMetrics.defaults(cornerSize = PopupCorner)),
                popupContainerStyle = PopupContainerStyle.dark(metrics = PopupContainerMetrics.defaults(cornerSize = PopupCorner)),
            )
        } else {
            ComponentStyling.light(
                defaultButtonStyle = ButtonStyle.Default.light(metrics = ButtonMetrics.default(cornerSize = ControlCorner)),
                outlinedButtonStyle = ButtonStyle.Outlined.light(metrics = ButtonMetrics.outlined(cornerSize = ControlCorner)),
                defaultSlimButtonStyle = ButtonStyle.Slim.Default.light(metrics = ButtonMetrics.Slim.default(cornerSize = ControlCorner)),
                outlinedSlimButtonStyle = ButtonStyle.Slim.Outlined.light(metrics = ButtonMetrics.Slim.outlined(cornerSize = ControlCorner)),
                textFieldStyle = TextFieldStyle.light(metrics = TextFieldMetrics.defaults(cornerSize = ControlCorner)),
                textAreaStyle = TextAreaStyle.light(metrics = TextAreaMetrics.defaults(cornerSize = ControlCorner)),
                dropdownStyle = DropdownStyle.Default.light(metrics = DropdownMetrics.default(cornerSize = ControlCorner)),
                undecoratedDropdownStyle = DropdownStyle.Undecorated.light(metrics = DropdownMetrics.undecorated(cornerSize = ControlCorner)),
                comboBoxStyle = ComboBoxStyle.Default.light(metrics = ComboBoxMetrics.defaultWithRowCount(cornerSize = ControlCorner)),
                segmentedControlStyle = SegmentedControlStyle.light(metrics = SegmentedControlMetrics.defaults(cornerSize = ControlCorner)),
                segmentedControlButtonStyle = SegmentedControlButtonStyle.light(metrics = SegmentedControlButtonMetrics.defaults(cornerSize = SegmentedButtonCorner)),
                menuStyle = MenuStyle.light(metrics = MenuMetrics.defaults(cornerSize = PopupCorner)),
                popupContainerStyle = PopupContainerStyle.light(metrics = PopupContainerMetrics.defaults(cornerSize = PopupCorner)),
            )
        }
    return styling.decoratedWindow(
        // lightWithLightHeader:现代 IDEA 浅色主题的浅色标题栏(light() 是旧式深色标题栏)
        titleBarStyle = if (darkTheme) TitleBarStyle.dark() else TitleBarStyle.lightWithLightHeader()
    )
}

/**
 * 应用主题:Jewel IntUi 独立主题(已替代原 MaterialTheme),
 * 含 DqFontFamily 文字样式与 DecoratedWindow 标题栏样式,
 * 并按明暗主题提供状态色。窗口/页签等组件直接消费 JewelTheme。
 *
 * 注意:Jewel 0.39.1 没有名为 intUiStandaloneTheme 的函数,
 * 独立主题入口即 IntUiTheme(theme = ThemeDefinition, styling = ComponentStyling)。
 */
@Composable
fun AppTheme(darkTheme: Boolean, content: @Composable () -> Unit) {
    val textStyle = JewelTheme.createDefaultTextStyle(fontFamily = DqFontFamily)
    val editorStyle = JewelTheme.createEditorTextStyle(fontFamily = DqFontFamily)
    val themeDefinition =
        if (darkTheme) {
            JewelTheme.darkThemeDefinition(defaultTextStyle = textStyle, editorTextStyle = editorStyle)
        } else {
            JewelTheme.lightThemeDefinition(defaultTextStyle = textStyle, editorTextStyle = editorStyle)
        }

    IntUiTheme(
        theme = themeDefinition,
        styling = roundedStyling(darkTheme),
    ) {
        CompositionLocalProvider(
            LocalStatusColors provides if (darkTheme) DarkStatusColors else LightStatusColors,
            LocalLevels provides if (darkTheme) DarkLevels else LightLevels,
            content = content,
        )
    }
}
