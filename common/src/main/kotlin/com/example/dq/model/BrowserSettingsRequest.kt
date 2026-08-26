package com.example.dq.model

/** 浏览器设置保存请求:browser 为浏览器 id,null/"auto"/空串表示自动按系统优先级选择 */
data class BrowserSettingsRequest(
    val browser: String? = null,
)
