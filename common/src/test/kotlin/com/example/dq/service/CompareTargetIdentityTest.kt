package com.example.dq.service

import com.example.dq.config.AppConfig
import com.example.dq.dialect.DialectFactory
import com.example.dq.model.ColumnMeta
import com.example.dq.model.CompareTargetIdentity
import com.example.dq.model.CompareTargetSpec
import com.example.dq.model.CreateCompareJobRequest
import com.example.dq.model.DataSourceRequest
import com.example.dq.model.PendingReason
import com.example.dq.repository.CompareRepository
import com.example.dq.repository.DataSourceRepository
import com.example.dq.repository.Jdbc
import com.example.dq.repository.MetaCacheRepository
import com.example.dq.repository.ScanRepository
import com.example.dq.repository.SchemaDocRepository
import com.example.dq.repository.SchemaInit
import com.example.dq.repository.SchemaStatRepository
import com.example.dq.repository.SystemSettingsRepository
import com.example.dq.repository.TableSystemRepository
import com.example.dq.util.CryptoUtil
import org.h2.jdbcx.JdbcDataSource
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files

/**
 * 数据比对:目标级身份字段(V62)单测。
 *
 * 覆盖:①组合身份配对(两字段都等才命中、任一身份值为空不参与);②目标级 identity 覆盖
 * (连了两个身份字段、人工指定只用一个);③推导规则(无映射=全体/只连一个=自动认它/一个都没连=拦下)
 * 与提交校验报错;④老口径回归(identity_json/key_fields_json 全 NULL 与单 keyField 行为同现状)。
 * resolveIdentity/compositeKey 为文件级纯函数,直接测;提交校验走 H2 内存库 + fake 字段清单
 * (执行器异步任务连 127.0.0.1:1 会快速失败,断言只看同步校验与落库字段)。
 */
class CompareTargetIdentityTest {

    private fun rowOf(vararg pairs: Pair<String, String?>): Map<String, String?> = linkedMapOf(*pairs)

    /** 基准/目标行 map 的键就是各自的组合身份值(与 loadRows 口径一致) */
    private fun mapOf(vararg rows: Pair<String, Map<String, String?>>) = linkedMapOf(*rows)

    // ---------- ① 组合身份配对 ----------

    @Test
    fun `组合身份 两字段都等才命中`() {
        val base = mapOf(
            "C1\u0001甲" to rowOf("code" to "C1", "name" to "甲"),
            "C2\u0001乙" to rowOf("code" to "C2", "name" to "乙"))
        val target = mapOf(
            // code 相等但 name 不同:组合身份不同,不算同一对象
            "C1\u0001丙" to rowOf("code" to "C1", "name" to "丙"),
            "C2\u0001乙" to rowOf("code" to "C2", "name" to "乙"))
        val result = matchObjects(base, target, listOf("code", "name"), emptyList(), MatchMode.LEGACY)
        assertEquals(1, result.codeMatched)
        assertEquals("C2\u0001乙", result.pairs.single().baseKey)
    }

    @Test
    fun `组合身份 任一身份字段为空不参与配对`() {
        assertNull(compositeKey(rowOf("code" to "C1", "name" to "  "), listOf("code", "name")))
        assertNull(compositeKey(rowOf("code" to null, "name" to "甲"), listOf("code", "name")))
        val base = mapOf("C1\u0001甲" to rowOf("code" to "C1", "name" to "甲"))
        val target = mapOf("C1" to rowOf("code" to "C1", "name" to null))
        val result = matchObjects(base, target, listOf("code", "name"), emptyList(), MatchMode.LEGACY)
        assertEquals(0, result.codeMatched)
    }

    @Test
    fun `组合键以分隔符拼接 不撞值`() {
        // 无分隔符时 ("a","bc") 与 ("ab","c") 会拼出同一串,加分隔符后必须不同
        val k1 = compositeKey(rowOf("code" to "a", "name" to "bc"), listOf("code", "name"))
        val k2 = compositeKey(rowOf("code" to "ab", "name" to "c"), listOf("code", "name"))
        assertEquals("a\u0001bc", k1)
        assertTrue(k1 != k2)
    }

    // ---------- ② 目标级 identity 覆盖 ----------

    @Test
    fun `目标级覆盖 连了两个身份字段人工只用一个`() {
        val mapping = mapOf("code" to "v_code", "name" to "v_name")
        val identity = resolveIdentity(listOf("code", "name"), mapping, listOf("code"))
        assertEquals(listOf("code"), identity)
        // 用覆盖后的单身份配对:code 相等即同一对象,name 差异落到字段级
        val base = mapOf("C1" to rowOf("code" to "C1", "name" to "甲"))
        val target = mapOf("C1" to rowOf("code" to "C1", "name" to "丙"))
        val result = matchObjects(base, target, identity, emptyList(), MatchMode.LEGACY)
        assertEquals(1, result.codeMatched)
    }

    @Test
    fun `目标级覆盖 不在任务身份字段内报错`() {
        val e = assertThrows(IllegalArgumentException::class.java) {
            resolveIdentity(listOf("code", "name"), emptyMap(), listOf("other"))
        }
        assertTrue(e.message!!.contains("other"))
    }

    @Test
    fun `identity_json 解析 空与坏 JSON 按未覆盖处理`() {
        assertEquals(listOf("code", "name"), parseIdentityKeys("""{"keys":["code","name"]}"""))
        assertNull(parseIdentityKeys(null))
        assertNull(parseIdentityKeys(""))
        assertNull(parseIdentityKeys("not-json"))
        assertNull(parseIdentityKeys("""{"keys":[]}"""))
    }

    // ---------- ③ 推导规则 ----------

    @Test
    fun `推导 无映射为任务身份全体 只连一个自动认它`() {
        // 无映射(按字段名自动匹配老路径):任务级身份字段全体
        assertEquals(listOf("code", "name"), resolveIdentity(listOf("code", "name"), emptyMap(), null))
        // 只连上一个:自动认它为该目标身份(多数场景零配置)
        assertEquals(listOf("code"),
            resolveIdentity(listOf("code", "name"), mapOf("code" to "v_code", "info" to "v_info"), null))
        // 连上多个:默认组合身份,保持任务级顺序
        assertEquals(listOf("code", "name"),
            resolveIdentity(listOf("code", "name"), mapOf("name" to "v_name", "code" to "v_code"), null))
    }

    @Test
    fun `推导为空 一个身份字段都没连报错且点名列出`() {
        val e = assertThrows(IllegalArgumentException::class.java) {
            resolveIdentity(listOf("code", "name"), mapOf("info" to "v_info"), null)
        }
        assertTrue(e.message!!.contains("code"))
        assertTrue(e.message!!.contains("name"))
    }

    // ---------- ④ 老口径回归(NULL 与单 keyField 行为同现状) ----------

    @Test
    fun `老口径 单字段退化为 keyField 组合键即 trim 值`() {
        assertEquals(listOf("code"), resolveIdentity(listOf("code"), emptyMap(), null))
        // 单字段组合键 = 字段值 trim,与旧 loadRows 行 map 键口径一致
        assertEquals("C1", compositeKey(rowOf("code" to " C1 "), listOf("code")))
        // 单字段 LEGACY 配对与旧行为一致(编码 trim 后区分大小写)
        val base = mapOf("r001" to rowOf("code" to " r001 ", "name" to "甲"))
        val target = mapOf("r001" to rowOf("code" to "r001", "name" to "甲(改)"))
        val result = matchObjects(base, target, listOf("code"), emptyList(), MatchMode.LEGACY)
        assertEquals(1, result.codeMatched)
    }

    // ---------- 提交校验(H2 + fake 字段清单) ----------

    private val config = AppConfig(dataDir = Files.createTempDirectory("compare-identity-test"))
    private val repo: CompareRepository
    private val dataSourceService: DataSourceService
    private val service: CompareService

    /** 表字段 fake:基准 code/name/info;t_a 全连;t_b 只能连 code;t_c 一个身份字段都连不上 */
    private val fakeTables = mapOf(
        "base_t" to listOf("code", "name", "info"),
        "t_a" to listOf("v_code", "v_name", "v_info"),
        "t_b" to listOf("v_code", "v_info"),
        "t_c" to listOf("v_info"),
    )

    init {
        val h2 = JdbcDataSource()
        h2.setURL("jdbc:h2:mem:compare-identity-" + System.nanoTime() + ";DB_CLOSE_DELAY=-1")
        SchemaInit.run(h2)
        val jdbc = Jdbc(h2)
        repo = CompareRepository(jdbc)
        val metaCacheRepo = MetaCacheRepository(jdbc)
        dataSourceService = DataSourceService(DataSourceRepository(jdbc), CryptoUtil(config),
            DialectFactory, config, SchemaStatRepository(jdbc), metaCacheRepo)
        val metadataService = MetadataService(dataSourceService, DialectFactory, ScanRepository(jdbc),
            SchemaStatRepository(jdbc), SchemaDocRepository(jdbc), metaCacheRepo)
        service = CompareService(repo, dataSourceService, DialectFactory, metadataService,
            SystemSettingsService(SystemSettingsRepository(jdbc), config), TableSystemRepository(jdbc),
            columnsLister = { _, _, _, table ->
                fakeTables[table]?.map { ColumnMeta(it, "varchar(64)", 12, false, 0, false) } ?: emptyList()
            })
    }

    /** 连接必失败(端口 1 即刻拒绝)的数据源,供执行器异步任务快速落幕 */
    private fun newDs(name: String): Long = dataSourceService.create(DataSourceRequest(
        name, "jdbc:mysql://127.0.0.1:1/db_$name", "u", "p", null, null))

    private fun req(target: CompareTargetSpec, keyFields: List<String>? = listOf("code", "name"),
                    baseDsId: Long, fields: List<String> = listOf("code", "name", "info")) =
        CreateCompareJobRequest(
            name = "身份测试" + System.nanoTime(), baseDatasourceId = baseDsId, baseDb = "db",
            baseSchema = null, baseTable = "base_t", keyField = "code", keyFields = keyFields,
            fields = fields, targets = listOf(target))

    @Test
    fun `提交 组合身份默认全部连线字段 key_fields_json 落全量`() {
        val baseId = newDs("b"); val targetId = newDs("a")
        val jobId = service.submit(req(
            CompareTargetSpec(targetId, "db", null, "t_a",
                mapping = mapOf("code" to "v_code", "name" to "v_name", "info" to "v_info")),
            baseDsId = baseId))
        val job = repo.getJob(jobId)!!
        // key_field 旧列写 keys 第一项,key_fields_json 存全量;未人工覆盖 identity_json 为 NULL(推导)
        assertEquals("code", job.keyField)
        assertEquals("""["code","name"]""", job.keyFieldsJson)
        assertEquals(listOf("code", "name"), service.detail(jobId).job.keyFields)
        assertNull(repo.listTargets(jobId).single().identityJson)
    }

    @Test
    fun `提交 目标只连一个身份字段自动认它`() {
        val baseId = newDs("b"); val targetId = newDs("tb")
        // t_b 只能连 code(name 没连线):有效身份推导为 [code],提交放行
        val jobId = service.submit(req(
            CompareTargetSpec(targetId, "db", null, "t_b",
                mapping = mapOf("code" to "v_code", "info" to "v_info")),
            baseDsId = baseId))
        assertTrue(repo.getJob(jobId) != null)
    }

    @Test
    fun `提交 目标一个身份字段都没连校验报错`() {
        val baseId = newDs("b"); val targetId = newDs("tc")
        val e = assertThrows(IllegalArgumentException::class.java) {
            service.submit(req(
                CompareTargetSpec(targetId, "db", null, "t_c", mapping = mapOf("info" to "v_info")),
                baseDsId = baseId))
        }
        assertTrue(e.message!!.contains("身份字段"), e.message)
    }

    @Test
    fun `提交 目标级人工覆盖落 identity_json 且须在任务身份字段内`() {
        val baseId = newDs("b"); val targetId = newDs("a2")
        val mapping = mapOf("code" to "v_code", "name" to "v_name", "info" to "v_info")
        // 连了两个身份字段,人工指定只用 code:落 identity_json 覆盖
        val jobId = service.submit(req(
            CompareTargetSpec(targetId, "db", null, "t_a", mapping = mapping,
                identity = CompareTargetIdentity(listOf("code"))),
            baseDsId = baseId))
        val target = repo.listTargets(jobId).single()
        assertEquals("""{"keys":["code"]}""", target.identityJson)
        assertEquals(listOf("code"), service.detail(jobId).targets.single().identityKeys)
        // 覆盖 key 不在任务级身份字段内 → 400
        assertThrows(IllegalArgumentException::class.java) {
            service.submit(req(
                CompareTargetSpec(targetId, "db", null, "t_a", mapping = mapping,
                    identity = CompareTargetIdentity(listOf("info"))),
                baseDsId = baseId))
        }
    }

    @Test
    fun `提交 老口径不带 keyFields 行为同现状`() {
        val baseId = newDs("b"); val targetId = newDs("legacy")
        val jobId = service.submit(req(
            CompareTargetSpec(targetId, "db", null, "t_a",
                mapping = mapOf("code" to "v_code", "name" to "v_name")),
            keyFields = null, baseDsId = baseId, fields = listOf("code", "name")))
        val job = repo.getJob(jobId)!!
        assertEquals("code", job.keyField)
        // 读取路径统一退化为 [keyField],与 NULL 老任务口径一致
        assertEquals(listOf("code"), service.detail(jobId).job.keyFields)
    }

    // ---------- 字段审核 confirm-mapping 携带 identities ----------

    /** 造一个 PENDING 任务并把任务级身份字段扩为 [code, name](编辑提交落 key_fields_json 全量) */
    private fun newPendingWithKeyFields(): Long {
        val baseId = newDs("pb"); val targetId = newDs("pv")
        val mapping = mapOf("code" to "v_code", "name" to "v_name", "info" to "v_info")
        val jobId = service.createPending("待处理身份" + System.nanoTime(), baseId, "db", null, "base_t",
            "code", listOf("code", "name", "info"), "name",
            listOf(CompareService.PendingTargetSpec(targetId, "厂商库", "db", null, "t_a", mapping)),
            PendingReason.MAPPING_REVIEW, null, null)
        service.updatePending(jobId, req(
            CompareTargetSpec(targetId, "db", null, "t_a", mapping = mapping), baseDsId = baseId))
        return jobId
    }

    @Test
    fun `confirmMapping 只带 identities 沿用既有映射更新身份并落库`() {
        val jobId = newPendingWithKeyFields()
        val targetId = repo.listTargets(jobId).single().id
        // mappings 为空、只在 identities 里出现:沿用库中既有映射校验,只收缩身份
        service.confirmMapping(jobId, emptyMap(), mapOf(targetId to CompareTargetIdentity(listOf("code"))))
        assertEquals("""{"keys":["code"]}""", repo.listTargets(jobId).single().identityJson)
        assertEquals(listOf("code"), service.detail(jobId).targets.single().identityKeys)
        // 确认后已离开 PENDING(执行器异步连 127.0.0.1:1 会快速失败,只看状态翻转)
        assertNotEquals("PENDING", repo.getJob(jobId)!!.status)
    }

    @Test
    fun `confirmMapping identities 越子集或为空报错 任务保持 PENDING`() {
        val jobId = newPendingWithKeyFields()
        val targetId = repo.listTargets(jobId).single().id
        // keys 不在任务级身份字段内 → 400
        assertThrows(IllegalArgumentException::class.java) {
            service.confirmMapping(jobId, emptyMap(), mapOf(targetId to CompareTargetIdentity(listOf("info"))))
        }
        // keys 为空 → 400(人工覆盖必须非空)
        assertThrows(IllegalArgumentException::class.java) {
            service.confirmMapping(jobId, emptyMap(), mapOf(targetId to CompareTargetIdentity(emptyList())))
        }
        // identities 里的目标不属于该任务 → 400
        assertThrows(IllegalArgumentException::class.java) {
            service.confirmMapping(jobId, emptyMap(),
                mapOf(targetId + 9999 to CompareTargetIdentity(listOf("code"))))
        }
        assertEquals("PENDING", repo.getJob(jobId)!!.status)
        // 报错未落任何覆盖
        assertEquals(null, repo.listTargets(jobId).single().identityJson)
    }

    @Test
    fun `confirmMapping mappings 与 identities 同目标一起校验落库`() {
        val jobId = newPendingWithKeyFields()
        val targetId = repo.listTargets(jobId).single().id
        service.confirmMapping(jobId,
            mapOf(targetId to mapOf("code" to "v_code", "name" to "v_name", "info" to "v_info")),
            mapOf(targetId to CompareTargetIdentity(listOf("name"))))
        val target = repo.listTargets(jobId).single()
        assertEquals("""{"keys":["name"]}""", target.identityJson)
        assertEquals(mapOf("code" to "v_code", "name" to "v_name", "info" to "v_info"),
            service.detail(jobId).targets.single().mapping)
    }
}
