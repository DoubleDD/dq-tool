package com.example.dq.service

import com.example.dq.config.AppConfig
import com.example.dq.model.ChangelogEntry
import com.example.dq.model.ChangelogView

/**
 * 更新日志:CHANGELOG.md(Keep-a-Changelog 简式,人维护,构建期拷入 jar)的解析与读取。
 * 版本段落标题为 `## <展示版版本号> (发布日期)`,展示版 = VERSION 去掉 0. 前缀,与页脚显示一致;
 * 文件顺序即最新在前。classpath 资源缺失/为空时返回空条目列表,不抛异常
 * (开发态 make dev 的 :server:run 也经 processResources 拷入,正常应有;兜底仅防极端情况)。
 */
class ChangelogService(config: AppConfig) {

    private val currentVersion: String = config.appVersion
    private val entries: List<ChangelogEntry> = parse(readChangelogResource())

    /** 更新日志概览:当前版本号 + 全部版本条目(最新在前) */
    fun overview(): ChangelogView = ChangelogView(currentVersion, entries)

    /** 从 classpath 读 CHANGELOG.md;读不到/读取异常返回 null(由 parse 兜底为空列表) */
    private fun readChangelogResource(): String? =
        runCatching {
            ChangelogService::class.java.getResourceAsStream("/CHANGELOG.md")
                ?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }
        }.getOrNull()

    companion object {
        private val HEADING = Regex("^##\\s+(\\S+?)(?:\\s*\\(([^)]*)\\))?\\s*$")

        /**
         * 解析 CHANGELOG.md 文本为版本条目列表(纯函数,便于单测)。
         * `#` 一级标题(文件题)忽略;`##` 二级标题切段;段内保留非空原始行(trim 后)。
         */
        fun parse(text: String?): List<ChangelogEntry> {
            if (text.isNullOrBlank()) return emptyList()
            val entries = mutableListOf<ChangelogEntry>()
            var version: String? = null
            var date: String? = null
            var lines = mutableListOf<String>()

            fun flush() {
                val v = version ?: return
                entries.add(ChangelogEntry(v, date, lines.toList()))
            }

            for (raw in text.lineSequence()) {
                val line = raw.trim()
                val m = HEADING.matchEntire(line)
                if (m != null) {
                    flush()
                    version = m.groupValues[1]
                    date = m.groupValues[2].ifBlank { null }
                    lines = mutableListOf()
                } else if (version != null && line.isNotEmpty()) {
                    lines.add(line)
                }
            }
            flush()
            return entries
        }
    }
}
