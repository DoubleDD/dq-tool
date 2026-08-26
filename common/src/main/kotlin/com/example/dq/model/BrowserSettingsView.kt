package com.example.dq.model

/** 本机探测到的 Chromium 系浏览器(应用模式 --app= 仅 Chromium 系支持) */
data class DetectedBrowser(
    val id: String,
    val name: String,
)

/** 浏览器设置回显:当前选择(null=自动)+ 本机探测到的浏览器清单(探测由 server 壳层完成) */
data class BrowserSettingsView(
    val browser: String?,
    val browsers: List<DetectedBrowser>,
)
