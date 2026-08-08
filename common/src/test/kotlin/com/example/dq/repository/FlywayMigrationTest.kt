package com.example.dq.repository

import org.h2.jdbcx.JdbcDataSource
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * Flyway 迁移两条路径验证(H2 内存库,无需容器):
 * - 新库:空库执行 V1~V4 全量迁移
 * - 老库:先按迁移前 schema.sql 建好表(无 license_info),baselineOnMigrate 应基线到 V1 后只执行 V2
 */
class FlywayMigrationTest {

    private fun memDs(name: String) = JdbcDataSource().apply {
        setURL("jdbc:h2:mem:$name;DB_CLOSE_DELAY=-1")
        user = "sa"
    }

    private fun tableExists(ds: JdbcDataSource, table: String): Boolean =
        ds.connection.use { conn ->
            conn.metaData.getTables(null, null, table.uppercase(), null).use { it.next() }
        }

    @Test
    fun `新库全量迁移`() {
        val ds = memDs("fresh_${UUID.randomUUID()}")
        SchemaInit.run(ds)

        assertTrue(tableExists(ds, "scan_job"))
        assertTrue(tableExists(ds, "scan_chunk"))
        assertTrue(tableExists(ds, "license_info"))
        assertTrue(tableExists(ds, "tag_def"))
        assertTrue(tableExists(ds, "table_tag"))
        // 系统「空表」标记随迁移自动插入
        Jdbc(ds).queryOne("SELECT COUNT(*) FROM tag_def WHERE name='空表' AND kind='EMPTY'") {
            it.getLong(1)
        }.let { assertEquals(1L, it) }
        // 迁移历史:V1~V4(flyway 表名为小写带引号,H2 中需原样引用;history 表还含建表标记行,按版本号过滤)
        Jdbc(ds).queryOne(
            """SELECT COUNT(*) FROM "flyway_schema_history" WHERE "version" IN ('1','2','3','4') AND "success" = TRUE""",
        ) { it.getLong(1) }.let { assertEquals(4L, it) }
    }

    @Test
    fun `已是最新的库第二次执行走快速路径跳过 Flyway`() {
        val ds = memDs("uptodate_${UUID.randomUUID()}")
        // 首次:全量迁移
        assertTrue(SchemaInit.run(ds), "新库应执行 Flyway 迁移")
        // 二次:库已是最新,应跳过(桌面应用后续启动的常态路径)
        assertTrue(!SchemaInit.run(ds), "已是最新的库应跳过 Flyway")
        // 跳过不影响库表完整性
        assertTrue(tableExists(ds, "scan_job"))
        assertTrue(tableExists(ds, "tag_def"))
    }

    @Test
    fun `老库 baseline 后增量迁移`() {
        val ds = memDs("legacy_${UUID.randomUUID()}")
        // 模拟老库:执行 V1 内容建表(相当于老版本 schema.sql 已跑过),无 flyway_schema_history
        val v1 = String(
            requireNotNull(javaClass.getResourceAsStream("/db/migration/V1__baseline.sql")).readBytes()
        )
        ds.connection.use { conn ->
            conn.createStatement().use { st ->
                v1.split(";")
                    .map { it.trim() }
                    .filter { chunk -> chunk.lines().any { l -> l.isNotBlank() && !l.trim().startsWith("--") } }
                    .forEach { st.execute(it) }
            }
        }
        assertTrue(tableExists(ds, "scan_job"))
        // 模拟 db_mode 引入前的更老库:data_source 表无 db_mode 列(旧版 schema.sql 时期创建的库)
        ds.connection.use { conn ->
            conn.createStatement().use { st -> st.execute("ALTER TABLE data_source DROP COLUMN db_mode") }
        }

        SchemaInit.run(ds)

        // baseline(V1 标记为已应用)+ V2 实际执行
        val jdbc = Jdbc(ds)
        assertTrue(tableExists(ds, "license_info"))
        // V3 增量:标记表创建 + 系统「空表」标记插入,老库升级路径同样生效
        assertTrue(tableExists(ds, "tag_def"))
        assertTrue(tableExists(ds, "table_tag"))
        jdbc.queryOne("SELECT COUNT(*) FROM tag_def WHERE name='空表' AND kind='EMPTY'") {
            it.getLong(1)
        }.let { assertEquals(1L, it) }
        // V2 补列恢复 db_mode(否则 DataSourceService 读该列会报列不存在)
        val dbModeBack = ds.connection.use { conn ->
            conn.metaData.getColumns(null, null, "DATA_SOURCE", "DB_MODE").use { it.next() }
        }
        assertTrue(dbModeBack)
        val appliedV2 = jdbc.queryOne(
            """SELECT COUNT(*) FROM "flyway_schema_history" WHERE "version" = '2' AND "success" = TRUE""",
        ) { it.getLong(1) }
        assertEquals(1L, appliedV2)
        // V4 补列:老库升级路径同样补齐 SSH 隧道配置列
        for (col in listOf("SSH_ENABLED", "SSH_HOST", "SSH_PORT", "SSH_USERNAME", "SSH_AUTH_METHOD",
            "SSH_PASSWORD_ENC", "SSH_PRIVATE_KEY_ENC", "SSH_PASSPHRASE_ENC")) {
            val exists = ds.connection.use { conn ->
                conn.metaData.getColumns(null, null, "DATA_SOURCE", col).use { it.next() }
            }
            assertTrue(exists, "老库缺少 V4 补列: $col")
        }
        val appliedV4 = jdbc.queryOne(
            """SELECT COUNT(*) FROM "flyway_schema_history" WHERE "version" = '4' AND "success" = TRUE""",
        ) { it.getLong(1) }
        assertEquals(1L, appliedV4)
    }
}
