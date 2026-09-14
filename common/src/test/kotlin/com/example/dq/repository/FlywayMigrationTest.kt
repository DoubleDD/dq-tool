package com.example.dq.repository

import org.flywaydb.core.Flyway
import org.flywaydb.core.api.MigrationVersion
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
        assertTrue(tableExists(ds, "system_settings"))
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
        // V18 补列:老库升级路径同样补齐数据源分组列
        val groupColBack = ds.connection.use { conn ->
            conn.metaData.getColumns(null, null, "DATA_SOURCE", "GROUP_NAME").use { it.next() }
        }
        assertTrue(groupColBack, "老库缺少 V18 补列: GROUP_NAME")
    }

    @Test
    fun `V47 老库升级回填人工审核标记与原始关系快照`() {
        val ds = memDs("relation_v47_${UUID.randomUUID()}")
        // 先迁到 V46(模拟已发布版本的库),再塞入存量关系数据
        Flyway.configure()
            .dataSource(ds)
            .locations("classpath:db/migration")
            .target(MigrationVersion.fromVersion("46"))
            .load()
            .migrate()
        val jdbc = Jdbc(ds)
        fun rel(oneTable: String, oneCol: String, manyTable: String, manyCol: String,
                source: String, status: String): Long =
            jdbc.insert(
                "INSERT INTO table_relation(datasource_id, db_name, schema_name, one_table, one_column, " +
                    "many_table, many_column, cardinality, status, source, confidence, overlap_ratio, remark) " +
                    "VALUES (1,'','PUBLIC',?,?,?,?,'ONE_TO_MANY',?,?,'HIGH',0.9,NULL)",
                oneTable, oneCol, manyTable, manyCol, status, source)

        val confirmed = rel("t1", "id", "t2", "t2_id", "NAME_MATCH", "CONFIRMED")
        val rejected = rel("t3", "id", "t4", "t4_id", "NAME_MATCH", "REJECTED")
        val candidate = rel("t5", "id", "t6", "t6_id", "SEMANTIC", "CANDIDATE")
        val manual = rel("t7", "id", "t8", "t8_id", "MANUAL", "CONFIRMED")
        // 反向存储的重复行(与 t1/t2 同一字段对),回填时应被方向无关去重掉
        rel("t2", "t2_id", "t1", "id", "NAME_MATCH", "CANDIDATE")

        SchemaInit.run(ds) // 应用 V47

        fun reviewed(id: Long): Boolean =
            jdbc.queryOne("SELECT reviewed FROM table_relation WHERE id=?", id) { it.getBoolean(1) }!!
        // 非候选态与人工补充视为人工已审核;纯候选为未审核
        assertTrue(reviewed(confirmed))
        assertTrue(reviewed(rejected))
        assertTrue(reviewed(manual))
        assertEquals(false, reviewed(candidate))

        // 原始关系快照:派生关系回填(状态 CANDIDATE),人工补充不回填,反向重复只留一条
        val originals = jdbc.query(
            "SELECT one_table, many_table, status FROM table_relation_original ORDER BY id"
        ) { Triple(it.getString(1), it.getString(2), it.getString(3)) }
        assertEquals(3, originals.size)
        assertTrue(originals.all { it.third == "CANDIDATE" })
        assertTrue(originals.none { it.first == "t7" })
        assertEquals(1, originals.count { it.first == "t1" && it.second == "t2" })
        assertEquals(0, originals.count { it.first == "t2" && it.second == "t1" })
    }
}
