package com.example.dq.model

/** AI 配置保存入参;apiKey 为空表示不修改已存的 key */
data class AiConfigRequest(val baseUrl: String?, val apiKey: String?, val model: String?)
