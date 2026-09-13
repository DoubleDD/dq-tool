package com.example.dq.service

import com.example.dq.config.AppConfig
import com.example.dq.dialect.DialectFactory
import com.example.dq.model.DataSourceRequest
import com.example.dq.model.TagSource
import com.example.dq.model.TagType
import com.example.dq.repository.DataSourceRepository
import com.example.dq.repository.Jdbc
import com.example.dq.repository.ManualCollectRepository
import com.example.dq.repository.MetaCacheRepository
import com.example.dq.repository.MetaSyncRepository
import com.example.dq.repository.ObjectCatalogRepository
import com.example.dq.repository.SchemaDocRepository
import com.example.dq.repository.SchemaInit
import com.example.dq.repository.SchemaStatRepository
import com.example.dq.repository.TableDocRepository
import com.example.dq.repository.TableRelationRepository
import com.example.dq.repository.TableSystemRepository
import com.example.dq.repository.TagRepository
import com.example.dq.util.CryptoUtil
import com.example.dq.util.TransferCrypto
import org.h2.jdbcx.JdbcDataSource
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.file.Files

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue

/** 元数据导入导出:结构缓存回环、派生数据合并与幂等、数据源按名匹配/新建、运行中同步拦截、行袋前向兼容 */
class MetadataTransferServiceTest {

    private lateinit var jdbc: Jdbc
    private lateinit var dsRepo: DataSourceRepository
    private lateinit var crypto: CryptoUtil
    private lateinit var dataSourceService: DataSourceService
    private lateinit var tagRepo: TagRepository
    private lateinit var tableDocRepo: TableDocRepository
    private lateinit var schemaDocRepo: SchemaDocRepository
    private lateinit var tableSystemRepo: TableSystemRepository
    private lateinit var manualCollectRepo: ManualCollectRepository
    private lateinit var tableRelationRepo: TableRelationRepository
    private lateinit var objectCatalogRepo: ObjectCatalogRepository
    private lateinit var metaSyncRepo: MetaSyncRepository
    private lateinit var service: MetadataTransferService

    @BeforeEach
    fun setUp() {
        val ds = JdbcDataSource()
        ds.setURL("jdbc:h2:mem:metadata-transfer-" + System.nanoTime() + ";DB_CLOSE_DELAY=-1")
        SchemaInit.run(ds)
        jdbc = Jdbc(ds)
        dsRepo = DataSourceRepository(jdbc)
        val config = AppConfig(dataDir = Files.createTempDirectory("metadata-transfer-test"))
        crypto = CryptoUtil(config)
        dataSourceService = DataSourceService(dsRepo, crypto, DialectFactory, config, SchemaStatRepository(jdbc), MetaCacheRepository(jdbc))
        tagRepo = TagRepository(jdbc)
        tableDocRepo = TableDocRepository(jdbc)
        schemaDocRepo = SchemaDocRepository(jdbc)
        tableSystemRepo = TableSystemRepository(jdbc)
        manualCollectRepo = ManualCollectRepository(jdbc)
        tableRelationRepo = TableRelationRepository(jdbc)
        objectCatalogRepo = ObjectCatalogRepository(jdbc)
        metaSyncRepo = MetaSyncRepository(jdbc)
        service = MetadataTransferService(jdbc, dsRepo, crypto, dataSourceService, metaSyncRepo,
            tagRepo, tableDocRepo, schemaDocRepo, tableSystemRepo, manualCollectRepo, tableRelationRepo, objectCatalogRepo)
    }

    private fun createDs(name: String, password: String): Long =
        dataSourceService.create(
            DataSourceRequest(name, "jdbc:mysql://localhost:3306/prod", "root", password, null, null))

    /** 造一份覆盖全部数据段的数据源元数据 */
    private fun seed(dsId: Long) {
        // 结构缓存
        jdbc.update("INSERT INTO meta_database(datasource_id, db_name, name, ordinal) VALUES (?,?,?,?)", dsId, "", "prod", 0)
        jdbc.update("INSERT INTO meta_table(datasource_id, db_name, schema_name, table_name, comment, est_rows, size_bytes) " +
                "VALUES (?,?,?,?,?,?,?)", dsId, "", "public", "orders", "订单表,超长注释", 1000L, 2048L)
        jdbc.update("INSERT INTO meta_column(datasource_id, db_name, schema_name, table_name, ordinal, column_name, type_name, nullable, comment) " +
                "VALUES (?,?,?,?,?,?,?,?,?)", dsId, "", "public", "orders", 0, "id", "BIGINT", false, "主键")
        jdbc.update("INSERT INTO meta_index(datasource_id, db_name, schema_name, table_name, index_name, is_unique, ordinal, column_name) " +
                "VALUES (?,?,?,?,?,?,?,?)", dsId, "", "public", "orders", "uk_orders_id", true, 0, "id")
        jdbc.update("INSERT INTO meta_schema_column(datasource_id, db_name, schema_name, table_name, ordinal, column_name, col_type, comment) " +
                "VALUES (?,?,?,?,?,?,?,?)", dsId, "", "public", "orders", 0, "id", "BIGINT", null)
        jdbc.update("INSERT INTO meta_ddl(datasource_id, db_name, schema_name, table_name, ddl) VALUES (?,?,?,?,?)",
            dsId, "", "public", "orders", "CREATE TABLE orders(id BIGINT PRIMARY KEY)")
        jdbc.update("INSERT INTO meta_column_count(datasource_id, db_name, schema_name, column_count) VALUES (?,?,?,?)",
            dsId, "", "public", 1)
        jdbc.update("INSERT INTO meta_cache_flag(datasource_id, db_name, schema_name, table_name, kind) VALUES (?,?,?,?,?)",
            dsId, "", "public", "", "TABLE")
        jdbc.update("INSERT INTO schema_stat(datasource_id, db_name, schema_name, table_count, size_bytes) VALUES (?,?,?,?,?)",
            dsId, null, "public", 1, 2048L)
        // 派生数据
        tableDocRepo.upsert(dsId, "", "public", "orders", "订单主表", "deepseek-chat")
        schemaDocRepo.upsert(dsId, "", "public", "核心库")
        val tag = tagRepo.create("核心表", "#F56C6C", "核心", TagType.AI)
        tagRepo.ensureTableTag(tag.id, dsId, "", "public", "orders", TagSource.MANUAL)
        tableSystemRepo.upsert(dsId, "", "public", "orders", "交易核心")
        manualCollectRepo.insert(dsId, "", "public", "orders", "订单表")
        tableRelationRepo.insertIfAbsent(dsId, "", "public", "orders", "id", "order_items", "order_id",
            "ONE_TO_MANY", "CONFIRMED", "NAME_MATCH", "HIGH", 0.98, null)
        // 数据目录:两级目录 + 挂载 + 登记的关系表
        val dirA = objectCatalogRepo.insertDir(dsId, 0, "交易域")
        val dirB = objectCatalogRepo.insertDir(dsId, dirA, "订单")
        val mount = objectCatalogRepo.insertTable(dirB, "", "public", "orders", "挂载备注", "INCLUDE")
        objectCatalogRepo.insertRel(mount, "", "public", "order_items", null, "ASSOC")
    }

    private fun wipeAll() {
        dsRepo.findAll().forEach { dsRepo.delete(it.id!!) }
        listOf("meta_database", "meta_table", "meta_column", "meta_index", "meta_schema_column",
            "meta_ddl", "meta_column_count", "meta_cache_flag", "schema_stat",
            "table_doc", "schema_doc", "table_tag", "tag_def", "table_system", "manual_collect",
            "table_relation", "object_table_rel", "object_table", "object_dir")
            .forEach { jdbc.update("DELETE FROM $it") }
    }

    private fun count(table: String): Long =
        jdbc.queryOne("SELECT COUNT(*) FROM $table") { it.getLong(1) }!!

    @Test
    fun `导出导入全量回环覆盖结构缓存与派生数据`() {
        val dsId = createDs("生产库", "secret-1")
        seed(dsId)

        val out = ByteArrayOutputStream()
        service.export(listOf(dsId), out)
        val json = out.toString(Charsets.UTF_8)
        assertTrue(json.contains("\"app\" : \"dq-tool-metadata\""), json)
        // 导出文件里不出现明文密码
        assertTrue(!json.contains("secret-1"), json)

        // 清空后导入(等价于跨实例:本机无任何记录)
        wipeAll()
        val result = service.importJson(ByteArrayInputStream(out.toByteArray()))
        assertEquals(1, result.total)
        assertEquals(1, result.imported.size, result.failed.toString())
        assertTrue(result.failed.isEmpty(), result.failed.toString())
        // 数据源按文件配置新建并提示
        assertTrue(result.warnings.any { it.contains("新建数据源") }, result.warnings.toString())

        val imported = dsRepo.findAll().single()
        assertEquals("生产库", imported.name)
        assertEquals("secret-1", crypto.decrypt(imported.password))

        // 结构缓存逐表回环
        assertEquals(1, count("meta_database"))
        assertEquals(1, count("meta_table"))
        assertEquals(1, count("meta_column"))
        assertEquals(1, count("meta_index"))
        assertEquals(1, count("meta_schema_column"))
        assertEquals(1, count("meta_ddl"))
        assertEquals(1, count("meta_column_count"))
        assertEquals(1, count("meta_cache_flag"))
        assertEquals(1, count("schema_stat"))
        val tableComment = jdbc.queryOne("SELECT comment FROM meta_table") { it.getString(1) }
        assertEquals("订单表,超长注释", tableComment)
        val ddl = jdbc.queryOne("SELECT ddl FROM meta_ddl") { it.getString(1) }
        assertEquals("CREATE TABLE orders(id BIGINT PRIMARY KEY)", ddl)

        // 派生数据回环:说明/库描述/标记/所属系统/采集/ER 关系
        assertEquals(1, count("table_doc"))
        assertEquals("订单主表", jdbc.queryOne("SELECT description FROM table_doc") { it.getString(1) })
        assertEquals("deepseek-chat", jdbc.queryOne("SELECT model FROM table_doc") { it.getString(1) })
        assertEquals(1, count("schema_doc"))
        assertEquals(1, count("tag_def"))
        assertEquals("核心表", jdbc.queryOne("SELECT name FROM tag_def") { it.getString(1) })
        assertEquals(1, count("table_tag"))
        assertEquals(1, count("table_system"))
        assertEquals(1, count("manual_collect"))
        assertEquals(1, count("table_relation"))
        assertEquals("CONFIRMED", jdbc.queryOne("SELECT status FROM table_relation") { it.getString(1) })

        // 数据目录:两级目录重建 + 挂载与关系表挂到重映射后的目录/挂载 id 下
        assertEquals(2, count("object_dir"))
        assertEquals(1, count("object_table"))
        assertEquals(1, count("object_table_rel"))
        val dirB = jdbc.queryOne("SELECT id, parent_id FROM object_dir WHERE name='订单'") { it.getLong(1) to it.getLong(2) }!!
        val dirA = jdbc.queryOne("SELECT id FROM object_dir WHERE name='交易域'") { it.getLong(1) }!!
        assertEquals(dirA, dirB.second)
        val mount = jdbc.queryOne("SELECT id, dir_id, remark, rel_kind FROM object_table WHERE table_name='orders'") { rs ->
            Triple(rs.getLong(1), rs.getLong(2), rs.getString(3) to rs.getString(4))
        }!!
        assertEquals(dirB.first, mount.second)
        assertEquals("挂载备注", mount.third.first)
        assertEquals("INCLUDE", mount.third.second)
        val rel = jdbc.queryOne("SELECT object_table_id, rel_kind FROM object_table_rel WHERE table_name='order_items'") { rs ->
            rs.getLong(1) to rs.getString(2)
        }!!
        assertEquals(mount.first, rel.first)
        assertEquals("ASSOC", rel.second)
    }

    @Test
    fun `重复导入幂等且不覆盖本机ER否决决策`() {
        val dsId = createDs("生产库", "secret-1")
        seed(dsId)
        val out = ByteArrayOutputStream()
        service.export(listOf(dsId), out)
        val bytes = out.toByteArray()

        val first = service.importJson(ByteArrayInputStream(bytes))
        assertEquals(1, first.imported.size, first.failed.toString())
        val snapshot = listOf("meta_table", "meta_column", "table_tag", "table_relation",
            "object_dir", "object_table", "object_table_rel").associateWith { count(it) }

        // 本机把关系否决掉,再导入同一份文件:否决决策必须保留
        val newDsId = dsRepo.findAll().single().id!!
        val relId = jdbc.queryOne("SELECT id FROM table_relation") { it.getLong(1) }!!
        tableRelationRepo.updateStatus(relId, "REJECTED")

        val second = service.importJson(ByteArrayInputStream(bytes))
        assertEquals(1, second.imported.size, second.failed.toString())
        snapshot.forEach { (table, cnt) -> assertEquals(cnt, count(table), "$table 重复导入产生了重复行") }
        assertEquals("REJECTED", jdbc.queryOne("SELECT status FROM table_relation") { it.getString(1) })

        // 结构缓存是替换语义:同一份文件重导,行数不变且内容不翻倍
        assertEquals(1, count("meta_table"))
        assertEquals(newDsId, jdbc.queryOne("SELECT datasource_id FROM meta_table") { it.getLong(1) })
    }

    @Test
    fun `按连接身份自动匹配已改名的本地数据源`() {
        val dsId = createDs("生产库", "secret-1")
        seed(dsId)
        val out = ByteArrayOutputStream()
        service.export(listOf(dsId), out)

        // 本机同名数据源已改名(连接信息不变):按连接身份匹配仍应命中,不按名称
        wipeAll()
        val localId = dataSourceService.create(
            DataSourceRequest("现场生产库", "jdbc:mysql://localhost:3306/prod", "root", "local-secret", null, null))
        val result = service.importJson(ByteArrayInputStream(out.toByteArray()))
        assertEquals(1, result.imported.size, result.failed.toString())
        assertEquals(1, dsRepo.findAll().size)
        val local = dsRepo.findAll().single()
        assertEquals(localId, local.id)
        // 本机连接配置与名称不被文件覆盖
        assertEquals("现场生产库", local.name)
        assertEquals("local-secret", crypto.decrypt(local.password))
        assertEquals(localId, jdbc.queryOne("SELECT datasource_id FROM meta_table") { it.getLong(1) })
        // 命中匹配时不产生「新建数据源」警告
        assertTrue(result.warnings.none { it.contains("新建数据源") }, result.warnings.toString())
    }

    @Test
    fun `预检返回规模与连接身份自动匹配结果`() {
        val dsId = createDs("生产库", "secret-1")
        seed(dsId)
        val out = ByteArrayOutputStream()
        service.export(listOf(dsId), out)
        wipeAll()
        val localId = dataSourceService.create(
            DataSourceRequest("现场生产库", "jdbc:mysql://localhost:3306/prod", "root", "x", null, null))
        // 连接信息不同的数据源不应被匹配
        dataSourceService.create(DataSourceRequest("别的库", "jdbc:mysql://other:3306/prod", "root", "x", null, null))

        val preview = service.preview(ByteArrayInputStream(out.toByteArray()))
        assertEquals(1, preview.items.size)
        val item = preview.items.single()
        assertEquals("生产库", item.name)
        assertEquals("MYSQL", item.dbType)
        assertEquals(1, item.tables)
        assertEquals(1, item.columns)
        assertEquals(1, item.relations)
        assertEquals(localId, item.matchedId)
        assertEquals("现场生产库", item.matchedName)
        assertEquals(2, preview.datasources.size)
    }

    @Test
    fun `显式映射优先于自动匹配且映射零可强制新建`() {
        val dsId = createDs("生产库", "secret-1")
        seed(dsId)
        val out = ByteArrayOutputStream()
        service.export(listOf(dsId), out)
        wipeAll()
        // 自动匹配会命中的本地数据源(连接信息与文件一致)
        val auto = dataSourceService.create(
            DataSourceRequest("自动匹配库", "jdbc:mysql://localhost:3306/prod", "root", "x", null, null))
        // 显式指定另一个连接不同的数据源
        val target = dataSourceService.create(
            DataSourceRequest("目标库", "jdbc:mysql://other:3306/prod", "root", "x", null, null))

        // 显式映射到目标库:自动匹配被覆盖
        val mapped = service.importJson(ByteArrayInputStream(out.toByteArray()), mapOf("生产库" to target))
        assertEquals(1, mapped.imported.size, mapped.failed.toString())
        assertEquals(target, jdbc.queryOne("SELECT datasource_id FROM meta_table") { it.getLong(1) })
        assertEquals(2, dsRepo.findAll().size)

        // 映射为 0 = 强制新建:即使自动匹配命中也建新数据源
        val forced = service.importJson(ByteArrayInputStream(out.toByteArray()), mapOf("生产库" to 0L))
        assertEquals(1, forced.imported.size, forced.failed.toString())
        assertEquals(3, dsRepo.findAll().size)
        val created = dsRepo.findAll().first { it.id != auto && it.id != target }
        assertEquals("生产库", created.name)
        assertEquals("secret-1", crypto.decrypt(created.password))

        // 映射到不存在的本机数据源:该条失败,不中断整批
        val bad = service.importJson(ByteArrayInputStream(out.toByteArray()), mapOf("生产库" to 9999L))
        assertEquals(1, bad.failed.size)
        assertTrue(bad.failed[0].reason.contains("不存在"), bad.failed[0].reason)
    }

    @Test
    fun `无密码元数据文件导入建源并提示补充密码`() {
        val json = """
            {"app":"dq-tool-metadata","version":1,"exportedAt":"t","items":[
              {"datasource":{"name":"无密码库","jdbcUrl":"jdbc:mysql://h:3306/d","username":"u","passwordEnc":"",
               "rowThreshold":null,"sizeThresholdBytes":null},
               "tables":[{"datasource_id":999,"db_name":"","schema_name":"public","table_name":"t1","comment":"表一"}]}
            ]}
        """.trimIndent()
        val result = service.importJson(ByteArrayInputStream(json.toByteArray()))
        assertEquals(1, result.imported.size, result.failed.toString())
        assertTrue(result.warnings.any { it.contains("未包含密码") }, result.warnings.toString())
        val c = dsRepo.findAll().single()
        assertEquals("无密码库", c.name)
        assertTrue(crypto.decrypt(c.password).isNullOrEmpty())
        assertEquals(false, dataSourceService.list().single().hasPassword)
        // 行内 datasource_id 被强制改写为新建数据源 id
        assertEquals(c.id, jdbc.queryOne("SELECT datasource_id FROM meta_table") { it.getLong(1) })
    }

    @Test
    fun `元数据同步进行中拒绝导入该数据源`() {
        val dsId = createDs("生产库", "secret-1")
        seed(dsId)
        val out = ByteArrayOutputStream()
        service.export(listOf(dsId), out)
        wipeAll()
        val localId = createDs("生产库", "local-secret")

        // 模拟正在运行的同步任务与该数据源明细
        val jobId = metaSyncRepo.insertJob(1)
        metaSyncRepo.markRunning(jobId)
        metaSyncRepo.insertItems(jobId, listOf(localId to "生产库"))

        val result = service.importJson(ByteArrayInputStream(out.toByteArray()))
        assertTrue(result.imported.isEmpty())
        assertEquals(1, result.failed.size)
        assertTrue(result.failed[0].reason.contains("同步"), result.failed[0].reason)
        assertEquals(0, count("meta_table"))
    }

    @Test
    fun `行袋多余键被忽略且坏行不中断整批`() {
        val json = """
            {"app":"dq-tool-metadata","version":1,"exportedAt":"t","items":[
              {"datasource":{"name":"前向兼容库","jdbcUrl":"jdbc:mysql://h:3306/d","username":"u",
               "passwordEnc":"${TransferCrypto.encrypt("p")}","rowThreshold":null,"sizeThresholdBytes":null},
               "tables":[
                 {"datasource_id":1,"db_name":"","schema_name":"public","table_name":"t1","future_col":"未来列","comment":null},
                 {"datasource_id":1,"db_name":"","schema_name":"public","comment":"缺表名"}
               ]}
            ]}
        """.trimIndent()
        val result = service.importJson(ByteArrayInputStream(json.toByteArray()))
        assertEquals(1, result.imported.size, result.failed.toString())
        assertEquals(1, count("meta_table"))
        assertTrue(result.warnings.any { it.contains("跳过") }, result.warnings.toString())
    }

    @Test
    fun `错误 app 标记版本与空条目抛参数错误`() {
        val badApp = """{"app":"dq-tool","version":1,"exportedAt":"t","items":[]}"""
        assertThrows(IllegalArgumentException::class.java) {
            service.importJson(ByteArrayInputStream(badApp.toByteArray()))
        }
        val badVersion = """{"app":"dq-tool-metadata","version":2,"exportedAt":"t","items":[]}"""
        assertThrows(IllegalArgumentException::class.java) {
            service.importJson(ByteArrayInputStream(badVersion.toByteArray()))
        }
        val empty = """{"app":"dq-tool-metadata","version":1,"exportedAt":"t","items":[]}"""
        assertThrows(IllegalArgumentException::class.java) {
            service.importJson(ByteArrayInputStream(empty.toByteArray()))
        }
        val notJson = "这不是 JSON"
        assertThrows(IllegalArgumentException::class.java) {
            service.importJson(ByteArrayInputStream(notJson.toByteArray()))
        }
    }

    @Test
    fun `导出不存在的数据源抛参数错误`() {
        val e = assertThrows(IllegalArgumentException::class.java) {
            service.export(listOf(9999L), ByteArrayOutputStream())
        }
        assertTrue(e.message!!.contains("数据源不存在"))
    }

    @Test
    fun `时间列按 ISO 字符串往返`() {
        val dsId = createDs("生产库", "secret-1")
        seed(dsId)
        val out = ByteArrayOutputStream()
        service.export(listOf(dsId), out)
        // 导出文件里 updated_at/refreshed_at 是 ISO 字符串而非时间戳数字
        assertTrue(out.toString(Charsets.UTF_8).contains("updated_at"), out.toString(Charsets.UTF_8))
        assertTrue(out.toString(Charsets.UTF_8).contains("T"), out.toString(Charsets.UTF_8))

        wipeAll()
        val result = service.importJson(ByteArrayInputStream(out.toByteArray()))
        assertEquals(1, result.imported.size, result.failed.toString())
        val updatedAt = jdbc.queryOne("SELECT updated_at FROM meta_table") { it.getTimestamp(1) }
        assertNotNull(updatedAt)
    }
}
