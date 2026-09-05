package com.example.dq.model

/** 单个版本的更新日志条目(对应 CHANGELOG.md 中一个 `## <版本> (日期)` 段落) */
data class ChangelogEntry(
    val version: String,
    /** 发布日期(标题括号内,可空) */
    val date: String?,
    /** 段落内原始文本行(去空行、逐项 trim;前端按 `- `/`* `/`### ` 前缀轻渲染) */
    val lines: List<String>,
)

/** 更新日志概览:当前版本号 + 全部版本条目(文件顺序,最新在前) */
data class ChangelogView(
    val currentVersion: String,
    val entries: List<ChangelogEntry>,
)
