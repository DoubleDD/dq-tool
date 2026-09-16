package com.example.dq.model

/** 单个版本的更新日志条目(对应 CHANGELOG.md 中一个 `## <版本> (日期)` 段落) */
data class ChangelogEntry(
    val version: String,
    /** 发布日期(标题括号内,可空) */
    val date: String?,
    /**
     * 段落正文的**原始 Markdown**(去掉版本标题行,段首段尾空行已 trim;
     * 段内空行与行首缩进原样保留,交给前端 Markdown 渲染器解析)
     */
    val markdown: String,
)

/** 更新日志概览:当前版本号 + 全部版本条目(文件顺序,最新在前) */
data class ChangelogView(
    val currentVersion: String,
    val entries: List<ChangelogEntry>,
)
