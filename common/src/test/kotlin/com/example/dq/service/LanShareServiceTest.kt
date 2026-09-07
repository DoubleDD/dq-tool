package com.example.dq.service

import com.example.dq.config.AppConfig
import com.example.dq.config.LanConfig
import com.example.dq.dialect.DialectFactory
import com.example.dq.discovery.LanDiscoveryService
import com.example.dq.model.AnnotationExportFile
import com.example.dq.model.AnnotationTableDocItem
import com.example.dq.model.AnnotationTableSystemItem
import com.example.dq.model.AnnotationTableTagItem
import com.example.dq.model.AnnotationTagItem
import com.example.dq.model.DataSourceRequest
import com.example.dq.model.LanAnnotationSelection
import com.example.dq.model.LanSettingsRequest
import com.example.dq.repository.DataSourceRepository
import com.example.dq.repository.Jdbc
import com.example.dq.repository.MetaCacheRepository
import com.example.dq.repository.ScanRepository
import com.example.dq.repository.SchemaInit
import com.example.dq.repository.SchemaStatRepository
import com.example.dq.repository.SystemSettingsRepository
import com.example.dq.repository.TableDocRepository
import com.example.dq.repository.TableSystemRepository
import com.example.dq.repository.TagRepository
import com.example.dq.util.CryptoUtil
import com.example.dq.util.TransferCrypto
import org.h2.jdbcx.JdbcDataSource
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.net.DatagramSocket
import java.nio.file.Files

/**
 * 局域网共享编排:实例 id 生成与持久化、开关/实例名保存与有效值合并、发现服务按开关启停。
 * 拉取导入的合并语义由 AnnotationTransferService / ScanTransferService 各自测试覆盖,这里不重复。
 */
class LanShareServiceTest {

    private lateinit var settingsRepo: SystemSettingsRepository
    private lateinit var tagRepo: TagRepository
    private lateinit var tableDocRepo: TableDocRepository
    private lateinit var dataSourceService: DataSourceService
    private lateinit var discovery: LanDiscoveryService
    private lateinit var service: LanShareService
    private lateinit var config: AppConfig

    private fun freeUdpPort(): Int = DatagramSocket(0).use { it.localPort }

    @BeforeEach
    fun setUp() {
        val ds = JdbcDataSource()
        ds.setURL("jdbc:h2:mem:lan_share_${System.nanoTime()};DB_CLOSE_DELAY=-1")
        ds.user = "sa"
        SchemaInit.run(ds)
        val jdbc = Jdbc(ds)
        settingsRepo = SystemSettingsRepository(jdbc)
        tagRepo = TagRepository(jdbc)
        tableDocRepo = TableDocRepository(jdbc)
        val dsRepo = DataSourceRepository(jdbc)
        config = AppConfig(
            dataDir = Files.createTempDirectory("lan-share-test"),
            lan = LanConfig(enabled = true, discoveryPort = freeUdpPort(), announceIntervalSeconds = 1),
        )
        discovery = LanDiscoveryService(config.lan, listOf())
        val crypto = CryptoUtil(config)
        dataSourceService = DataSourceService(dsRepo, crypto, DialectFactory, config,
            SchemaStatRepository(jdbc), MetaCacheRepository(jdbc))
        service = LanShareService(
            discovery, settingsRepo,
            AnnotationTransferService(tagRepo, tableDocRepo, TableSystemRepository(jdbc), dsRepo),
            ScanTransferService(ScanRepository(jdbc), dsRepo, TagRepository(jdbc),
                TableDocRepository(jdbc)),
            config, dsRepo, dataSourceService, crypto,
        )
    }

    @Test
    fun `status 首次访问即生成并持久化实例 id,默认名称非空`() {
        val s1 = service.status()
        assertTrue(s1.instanceId.isNotBlank())
        assertTrue(s1.instanceName.isNotBlank())
        assertTrue(s1.enabled) // 配置默认开
        assertFalse(s1.running) // 未 start

        val s2 = service.status()
        assertEquals(s1.instanceId, s2.instanceId) // 幂等,不重生成
        assertEquals(s1.instanceId, settingsRepo.get()!!.instanceId)
    }

    @Test
    fun `保存开关与实例名,DB 覆盖配置默认,名称空白存空走主机名兜底`() {
        service.saveSettings(LanSettingsRequest(enabled = false, instanceName = "小王的电脑"))
        val row = settingsRepo.get()!!
        assertEquals(false, row.lanEnabled)
        assertEquals("小王的电脑", row.instanceName)

        val s = service.status()
        assertFalse(s.enabled)
        assertEquals("小王的电脑", s.instanceName)

        // null 字段不修改已存值
        service.saveSettings(LanSettingsRequest(enabled = null, instanceName = null))
        assertEquals("小王的电脑", settingsRepo.get()!!.instanceName)
    }

    @Test
    fun `开关开时 start 启动发现,保存关闭即停,再开自动重启(start 已记录端口)`() {
        service.start(18080)
        assertTrue(discovery.isRunning())
        assertTrue(service.status().running)

        service.saveSettings(LanSettingsRequest(enabled = false))
        assertFalse(discovery.isRunning())
        assertFalse(service.status().running)

        service.saveSettings(LanSettingsRequest(enabled = true))
        assertTrue(discovery.isRunning())

        service.stop()
        assertFalse(discovery.isRunning())
    }

    @Test
    fun `开关关时 start 不启动发现,但实例 id 仍生成`() {
        service.saveSettings(LanSettingsRequest(enabled = false))
        service.start(18081)
        assertFalse(discovery.isRunning())
        assertNotNull(settingsRepo.get()!!.instanceId)
        service.stop()
    }

    @Test
    fun `按勾选过滤标注导出文件,未勾选标记的表级打标一并剔除,类别开关整体取舍`() {
        val file = AnnotationExportFile(
            app = "dq-tool-annotations", version = 1, exportedAt = "t",
            tags = listOf(AnnotationTagItem("核心业务", "#f00"), AnnotationTagItem("需治理")),
            tableTags = listOf(
                AnnotationTableTagItem("ds1", "", "public", "t1", "核心业务"),
                AnnotationTableTagItem("ds1", "", "public", "t2", "需治理"),
            ),
            tableDocs = listOf(AnnotationTableDocItem("ds1", "", "public", "t1", "描述")),
            tableSystems = listOf(AnnotationTableSystemItem("ds1", "", "public", "t1", "平台")),
        )

        val out = service.filterAnnotations(
            file, LanAnnotationSelection(tagNames = listOf("核心业务"),
                includeTableTags = true, includeDocs = false, includeSystems = true))

        assertEquals(listOf("核心业务"), out.tags.map { it.name })
        assertEquals(listOf("t1"), out.tableTags.map { it.tableName }) // 需治理未勾选,其打标关系不带
        assertTrue(out.tableDocs.isEmpty())   // 类别关
        assertEquals(1, out.tableSystems.size) // 类别开
        // 表级打标类别关时一条不带
        val noTableTags = service.filterAnnotations(file,
            LanAnnotationSelection(tagNames = listOf("核心业务", "需治理"), includeTableTags = false))
        assertTrue(noTableTags.tableTags.isEmpty())
        assertEquals(2, noTableTags.tags.size)
    }

    @Test
    fun `本机标注预览复用导出口径,标记清单与逐行明细齐全`() {
        val tag = tagRepo.create("核心业务", "#f00", "核心", com.example.dq.model.TagType.AI)
        tagRepo.create("需治理", "", null, com.example.dq.model.TagType.MANUAL)
        tagRepo.ensureTableTag(tag.id!!, 1L, "", "public", "t1", "核心业务")
        tableDocRepo.upsert(1L, "", "public", "t1", "描述一", "test")

        val preview = service.localAnnotationsPreview()
        assertEquals(listOf("核心业务", "需治理"), preview.tags.map { it.name })
        // 数据源已删除的行名字兜底为空串,计数仍按行归组
        assertEquals(1, preview.datasources.size)
        assertEquals(1, preview.datasources[0].tableDocs)
        // 逐行明细:打标关系与描述原文都在预览里
        assertEquals(1, preview.tableTags.size)
        assertEquals("t1", preview.tableTags[0].tableName)
        assertEquals("核心业务", preview.tableTags[0].tagName)
        assertEquals(1, preview.tableDocs.size)
        assertEquals("描述一", preview.tableDocs[0].description)
    }

    @Test
    fun `本机数据源预览带密码,TransferCrypto 密文(与导出文件同口径,不明文,可解密)`() {
        dataSourceService.create(
            DataSourceRequest("生产库", "jdbc:mysql://db.internal:3306/app", "reader", "TopSecret123", null, null))

        val list = service.localDatasourcesPreview()
        assertEquals(1, list.size)
        val item = list[0]
        assertEquals("生产库", item.name)
        assertEquals("jdbc:mysql://db.internal:3306/app", item.jdbcUrl)
        assertEquals("reader", item.username)
        // 密码以 TransferCrypto 密文随行:不明文、可解密还原(与数据源导出文件同一口径)
        assertNotNull(item.passwordEnc)
        assertTrue(item.passwordEnc!!.isNotBlank())
        assertTrue(item.passwordEnc != "TopSecret123")
        assertEquals("TopSecret123", TransferCrypto.decrypt(item.passwordEnc))
        val json = com.fasterxml.jackson.module.kotlin.jacksonObjectMapper().writeValueAsString(list)
        assertFalse(json.contains("TopSecret123"), json)
    }
}
