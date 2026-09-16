package com.example.dq.service

import com.example.dq.config.AppConfig
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Path

/** 更新日志解析:多版本段落、无日期标题、空内容/资源缺失兜底、Markdown 正文原样保留 */
class ChangelogServiceTest {

    @Test
    fun `parse 多版本段落 文件顺序即最新在前`() {
        val text = """
            # 更新日志

            ## 1.9.8 (2026-09-06)

            - 新增更新日志功能
            - 点击页脚版本号查看历史记录

            ## 1.9.7 (2026-08-20)

            - 修复扫描中断恢复问题
        """.trimIndent()
        val entries = ChangelogService.parse(text)
        assertEquals(2, entries.size)
        assertEquals("1.9.8", entries[0].version)
        assertEquals("2026-09-06", entries[0].date)
        assertEquals("- 新增更新日志功能\n- 点击页脚版本号查看历史记录", entries[0].markdown)
        assertEquals("1.9.7", entries[1].version)
        assertEquals("2026-08-20", entries[1].date)
        assertEquals("- 修复扫描中断恢复问题", entries[1].markdown)
    }

    @Test
    fun `parse 无日期标题与小标题行`() {
        val text = """
            # 更新日志

            ## 1.9.8

            ### 新增

            - 条目一
            普通说明行
        """.trimIndent()
        val entries = ChangelogService.parse(text)
        assertEquals(1, entries.size)
        assertEquals("1.9.8", entries[0].version)
        assertNull(entries[0].date)
        assertEquals("### 新增\n\n- 条目一\n普通说明行", entries[0].markdown)
    }

    @Test
    fun `parse 保留段内 markdown 标记 空行与缩进`() {
        val text = """
            ## 1.9.8 (2026-09-06)

            ### 新增

            - **加粗标题**:含 `行内代码`
                - 嵌套子项
            - 第二项
        """.trimIndent()
        val entries = ChangelogService.parse(text)
        assertEquals(1, entries.size)
        // 加粗/行内代码标记、段内空行与行首缩进都必须原样交给前端 Markdown 渲染器
        assertEquals(
            "### 新增\n\n- **加粗标题**:含 `行内代码`\n    - 嵌套子项\n- 第二项",
            entries[0].markdown
        )
    }

    @Test
    fun `parse 空内容与 null 返回空列表`() {
        assertTrue(ChangelogService.parse(null).isEmpty())
        assertTrue(ChangelogService.parse("").isEmpty())
        assertTrue(ChangelogService.parse("   \n  ").isEmpty())
        // 只有一级标题、没有任何版本段落
        assertTrue(ChangelogService.parse("# 更新日志\n\n一些说明").isEmpty())
    }

    @Test
    fun `parse 版本段落无正文时正文为空串`() {
        val entries = ChangelogService.parse("## 1.9.8 (2026-09-06)\n\n## 1.9.7\n\n- 旧条目")
        assertEquals(2, entries.size)
        assertEquals("", entries[0].markdown)
        assertEquals("- 旧条目", entries[1].markdown)
    }

    @Test
    fun `overview classpath 无 CHANGELOG-md 资源时兜底为空条目 不抛异常`() {
        // :common 的测试 classpath 不含 CHANGELOG.md(由 :server processResources 拷入),
        // 服务应静默降级为空条目,并把配置的 appVersion 透传为 currentVersion
        val config = AppConfig(dataDir = Path.of("build/tmp/changelog-test"), appVersion = "1.9.8")
        val view = ChangelogService(config).overview()
        assertEquals("1.9.8", view.currentVersion)
        assertTrue(view.entries.isEmpty())
    }
}
