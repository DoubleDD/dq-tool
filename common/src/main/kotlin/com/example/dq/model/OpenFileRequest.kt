package com.example.dq.model

/** POST /api/system/open 请求体:打开数据目录内的产物文件(Tauri 导出直存后前端「打开文件」) */
data class OpenFileRequest(val path: String?)
