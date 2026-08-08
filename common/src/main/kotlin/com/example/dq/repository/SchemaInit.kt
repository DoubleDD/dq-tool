package com.example.dq.repository

import org.flywaydb.core.Flyway
import org.slf4j.LoggerFactory
import java.io.File
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.sql.SQLException
import java.util.jar.JarFile
import javax.sql.DataSource

/**
 * H2 库表迁移(Flyway):迁移脚本在 classpath:db/migration。
 * 存量老库(有表无 flyway_schema_history)baselineOnMigrate 自动基线到 V1,再执行 V2 补齐
 * license_info 与后加的列;新库从 V1 全量执行。
 * 规则:结构变更一律新增 V{n}__描述.sql,已发布的迁移文件禁止修改,不允许破坏性变更。
 *
 * 快速路径(2026-08,桌面应用启动优化):迁移脚本随软件版本固定,库已是最新时跳过 Flyway
 * —— 实测「已是最新」的 migrate 也要 ~0.8s(扫描迁移文件 + 校验 history + Flyway 自身初始化),
 * 而一条 history 查询只要几毫秒。判定失败(无 history 表/脚本扫描异常)一律回落执行 Flyway。
 */
object SchemaInit {

    private val log = LoggerFactory.getLogger(SchemaInit::class.java)

    private val scriptNameRegex = Regex("""V(\d+)__.*\.sql""")

    /**
     * 执行库表迁移;库已是最新时跳过。
     * @return true = 实际执行了 Flyway migrate;false = 已是最新走了快速路径
     */
    fun run(ds: DataSource): Boolean {
        val latest = latestScriptVersion()
        val current = currentVersion(ds)
        if (latest != null && current != null && current >= latest) {
            log.info("H2 库表已是最新版本 V{},跳过 Flyway 迁移", current)
            return false
        }
        val result = Flyway.configure()
            .dataSource(ds)
            .baselineOnMigrate(true)
            .baselineVersion("1")
            .load()
            .migrate()
        log.info("H2 库表迁移完成:执行 {} 个迁移,当前版本 {}", result.migrationsExecuted, result.targetSchemaVersion)
        return true
    }

    /** history 表中最新成功迁移的版本号;表不存在(新库/老库)或查询失败返回 null(回落执行 Flyway) */
    private fun currentVersion(ds: DataSource): Int? = try {
        ds.connection.use { conn ->
            conn.createStatement().use { st ->
                st.executeQuery(
                    """SELECT "version" FROM "flyway_schema_history" WHERE "success" = TRUE ORDER BY "installed_rank" DESC LIMIT 1"""
                ).use { rs ->
                    if (rs.next()) rs.getString(1)?.toIntOrNull() else null
                }
            }
        }
    } catch (e: SQLException) {
        null
    }

    /**
     * 扫描 classpath `db/migration` 下 V{n}__*.sql 的最大版本号(只读 jar 中央目录/目录列表,毫秒级);
     * 同时兼容 fat jar(jar: 协议)与 exploded classpath(gradle run/测试,file: 协议);
     * 扫描失败返回 null(回落执行 Flyway,宁慢勿错)
     */
    private fun latestScriptVersion(): Int? = try {
        val versions = sortedSetOf<Int>()
        val urls = SchemaInit::class.java.classLoader.getResources("db/migration")
        while (urls.hasMoreElements()) {
            val url = urls.nextElement()
            when (url.protocol) {
                "file" -> File(url.toURI()).listFiles()?.forEach { f ->
                    parseVersion(f.name)?.let(versions::add)
                }
                "jar" -> {
                    // jar:file:/path/app.jar!/db/migration
                    val jarPath = URLDecoder.decode(
                        url.path.substringAfter("file:").substringBefore("!"), StandardCharsets.UTF_8
                    )
                    JarFile(File(jarPath)).use { jar ->
                        jar.entries().asSequence()
                            .map { it.name }
                            .filter { it.startsWith("db/migration/") }
                            .forEach { parseVersion(it.substringAfterLast('/'))?.let(versions::add) }
                    }
                }
            }
        }
        versions.maxOrNull().also {
            if (it == null) log.warn("未扫描到迁移脚本,回落为执行 Flyway")
        }
    } catch (e: Exception) {
        log.warn("扫描迁移脚本版本失败,回落为执行 Flyway: {}", e.message)
        null
    }

    private fun parseVersion(fileName: String): Int? =
        scriptNameRegex.matchEntire(fileName)?.groupValues?.get(1)?.toIntOrNull()
}
