package com.example.dq.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import com.example.dq.generated.resources.Inter_Bold
import com.example.dq.generated.resources.Inter_Medium
import com.example.dq.generated.resources.Inter_Regular
import com.example.dq.generated.resources.Inter_SemiBold
import com.example.dq.generated.resources.NotoSansSC_Bold
import com.example.dq.generated.resources.NotoSansSC_Regular
import com.example.dq.generated.resources.Res
import org.jetbrains.compose.resources.Font

/**
 * 应用字体回退链:Inter(Latin/数字) + Noto Sans SC(CJK)。
 * 系统的 CJK 回退字体(Droid Sans Fallback)无字重、渲染发虚,
 * 捆绑字体保证各平台(含麒麟/统信等信创环境)渲染一致。
 */
val DqFontFamily: FontFamily
    @Composable
    get() = FontFamily(
        Font(Res.font.Inter_Regular, FontWeight.Normal),
        Font(Res.font.Inter_Medium, FontWeight.Medium),
        Font(Res.font.Inter_SemiBold, FontWeight.SemiBold),
        Font(Res.font.Inter_Bold, FontWeight.Bold),
        Font(Res.font.NotoSansSC_Regular, FontWeight.Normal),
        Font(Res.font.NotoSansSC_Bold, FontWeight.Bold),
    )
