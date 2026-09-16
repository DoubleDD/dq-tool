package com.example.dq.util

import java.security.MessageDigest

/**
 * 错误指纹:同一类错误无论消息里的数字/路径/参数怎么变,都归并为同一条聚合记录。
 *
 * 组成:`source | kind | 归一化消息 | 首个业务栈帧`。
 * 归一化规则(逐条替换为占位符,尽量保留错误语义):
 * - 引号内字面量 → `?`(SQL 参数、字段名等易变内容)
 * - 十六进制/长哈希 → `0x…`
 * - 数字(含日期时间、端口、行号) → `n`
 * - 连续空白 → 单个空格
 * 栈帧只取第一条 `com.example.dq` 开头的 `at` 行并去掉行号,用于区分同类消息的不同代码位置。
 */
object ErrorFingerprint {

    private val QUOTED = Regex("'[^']*'|\"[^\"]*\"|`[^`]*`")
    private val HEX = Regex("(?i)\\b0x[0-9a-f]+\\b|\\b[0-9a-f]{16,}\\b")
    private val UUID = Regex("(?i)\\b[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\\b")
    private val NUMBER = Regex("\\d+")
    private val WHITESPACE = Regex("\\s+")
    private val FRAME_LINE = Regex("^\\s*at\\s+", RegexOption.MULTILINE)
    private val FRAME_LINE_NO = Regex("\\([^)]*\\)")

    /** 计算指纹(返回 64 位十六进制 SHA-256) */
    fun of(source: String, kind: String, message: String?, detail: String?): String {
        val normalized = normalize(source, kind, message, detail)
        val digest = MessageDigest.getInstance("SHA-256")
        val bytes = digest.digest(normalized.toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }
    }

    /** 归一化后的原文(便于单测断言,不参与对外接口) */
    fun normalize(source: String, kind: String, message: String?, detail: String?): String {
        val msg = normalizeText(message.orEmpty())
        val frame = firstBusinessFrame(detail)
        return "$source|$kind|$msg|$frame"
    }

    /** 文本归一:去字面量、哈希、UUID、数字,压缩空白,截断到 500 字符防超长 */
    fun normalizeText(text: String): String {
        var s = text
        s = QUOTED.replace(s, "?")
        s = UUID.replace(s, "<uuid>")
        s = HEX.replace(s, "0x…")
        s = NUMBER.replace(s, "n")
        s = WHITESPACE.replace(s, " ").trim()
        return s.take(500)
    }

    /** 首个本项目栈帧(去掉行号);没有则返回空串 */
    private fun firstBusinessFrame(detail: String?): String {
        if (detail.isNullOrBlank()) return ""
        for (raw in detail.lineSequence()) {
            val line = raw.trim()
            if (!line.startsWith("at ")) continue
            if (!line.contains("com.example.dq")) continue
            return FRAME_LINE_NO.replace(FRAME_LINE.replace(line, ""), "").trim()
        }
        return ""
    }
}
