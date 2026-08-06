package com.example.dq.repository

import org.slf4j.LoggerFactory
import javax.sql.DataSource

/**
 * 启动时执行 classpath 里的 schema.sql 初始化 H2 库表。
 * 替代原 Spring Boot 的 spring.sql.init;schema.sql 全部是 CREATE TABLE IF NOT EXISTS /
 * ALTER TABLE ... IF NOT EXISTS,可重复执行,兼容老库。
 */
object SchemaInit {

    private val log = LoggerFactory.getLogger(SchemaInit::class.java)

    fun run(ds: DataSource) {
        val sql = SchemaInit::class.java.getResource("/schema.sql")?.readText()
            ?: error("classpath 中找不到 schema.sql")
        ds.connection.use { conn ->
            conn.createStatement().use { st ->
                // H2 按分号切分;脚本内无存储过程,简单切分即可
                sql.split(";")
                    .map { it.trim() }
                    .filter { chunk ->
                        // 过滤纯注释块(注释行外的内容为空)
                        chunk.lines().any { line -> line.trim().isNotEmpty() && !line.trim().startsWith("--") }
                    }
                    .forEach { stmt -> st.execute(stmt) }
            }
        }
        log.info("H2 库表初始化完成")
    }
}
