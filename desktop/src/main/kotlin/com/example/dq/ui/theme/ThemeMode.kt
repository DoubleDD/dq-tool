package com.example.dq.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp

/**
 * 主题模式三档:浅色(太阳)/ 深色(月亮)/ 跟随系统(auto)。
 * 标题栏图标按钮按 浅色 → 深色 → 跟随系统 循环切换。
 */
enum class ThemeMode(val label: String, val icon: ImageVector) {
    Light("浅色", SunIcon),
    Dark("深色", MoonIcon),
    System("跟随系统", AutoIcon),
    ;

    /** 点击切换到下一档 */
    fun next(): ThemeMode = entries[(ordinal + 1) % entries.size]
}

private fun vectorIcon(name: String, pathData: String): ImageVector =
    ImageVector.Builder(
        name = name,
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f,
    ).addPath(pathData = addPathNodes(pathData), fill = SolidColor(Color.Black)).build()

// 太阳(material light_mode 路径)
private val SunIcon =
    vectorIcon(
        "sun",
        "M12,7c-2.76,0 -5,2.24 -5,5s2.24,5 5,5 5,-2.24 5,-5 -2.24,-5 -5,-5zM2,13h2c0.55,0 1,-0.45 " +
            "1,-1s-0.45,-1 -1,-1L2,11c-0.55,0 -1,0.45 -1,1s0.45,1 1,1zM18,13h2c0.55,0 1,-0.45 1,-1s-0.45,-1 " +
            "-1,-1h-2c-0.55,0 -1,0.45 -1,1s0.45,1 1,1zM11,2v2c0,0.55 0.45,1 1,1s1,-0.45 1,-1L13,2c0,-0.55 " +
            "-0.45,-1 -1,-1s-1,0.45 -1,1zM11,20v2c0,0.55 0.45,1 1,1s1,-0.45 1,-1v-2c0,-0.55 -0.45,-1 -1,-1s-1," +
            "0.45 -1,1zM5.99,4.58c-0.39,-0.39 -1.03,-0.39 -1.41,0 -0.39,0.39 -0.39,1.03 0,1.41l1.06,1.06c0.39," +
            "0.39 1.03,0.39 1.41,0s0.39,-1.03 0,-1.41L5.99,4.58zM18.36,16.95c-0.39,-0.39 -1.03,-0.39 -1.41,0 " +
            "-0.39,0.39 -0.39,1.03 0,1.41l1.06,1.06c0.39,0.39 1.03,0.39 1.41,0 0.39,-0.39 0.39,-1.03 0,-1.41" +
            "l-1.07,-1.06zM19.42,5.99c0.39,-0.39 0.39,-1.03 0,-1.41 -0.39,-0.39 -1.03,-0.39 -1.41,0l-1.06,1.06" +
            "c-0.39,0.39 -0.39,1.03 0,1.41s1.03,0.39 1.41,0l1.06,-1.06zM7.05,18.36c0.39,-0.39 0.39,-1.03 0,-1.41" +
            " -0.39,-0.39 -1.03,-0.39 -1.41,0l-1.06,1.06c-0.39,0.39 -0.39,1.03 0,1.41 0.39,0.39 1.03,0.39 1.41," +
            "0l1.06,-1.06z",
    )

// 月亮(material dark_mode 路径)
private val MoonIcon =
    vectorIcon(
        "moon",
        "M12,3c-4.97,0 -9,4.03 -9,9s4.03,9 9,9 9,-4.03 9,-9c0,-0.46 -0.04,-0.92 -0.1,-1.36 -0.98,1.37 " +
            "-2.58,2.26 -4.4,2.26 -2.98,0 -5.4,-2.42 -5.4,-5.4 0,-1.81 0.89,-3.42 2.26,-4.4 -0.44,-0.06 -0.9," +
            "-0.1 -1.36,-0.1z",
    )

// 跟随系统:半黑半白圆(material brightness_6 路径)
private val AutoIcon =
    vectorIcon(
        "auto",
        "M12,2c-5.52,0 -10,4.48 -10,10s4.48,10 10,10 10,-4.48 10,-10S17.52,2 12,2zM12,20L12,4c4.42,0 " +
            "8,3.58 8,8s-3.58,8 -8,8z",
    )
