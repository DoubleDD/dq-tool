package com.example.dq.service

import com.example.dq.model.DataSourceConfig
import com.example.dq.model.DbType
import com.example.dq.model.ObjectTableMountItem
import com.example.dq.repository.DataSourceRepository
import com.example.dq.repository.Jdbc
import com.example.dq.repository.MetaCacheRepository
import com.example.dq.repository.ObjectCatalogRepository
import com.example.dq.repository.SchemaInit
import org.h2.jdbcx.JdbcDataSource
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue

/** 对象管理(数据目录):组树/同级重名/级联删除统计/挂载与关系幂等/关系不指向自身 */
class ObjectCatalogServiceTest {

    private lateinit var jdbc: Jdbc
    private lateinit var catalogRepo: ObjectCatalogRepository
    private lateinit var dsRepo: DataSourceRepository
    private lateinit var metaCacheRepo: MetaCacheRepository
    private lateinit var service: ObjectCatalogService

    @BeforeEach
    fun setUp() {
        val ds = JdbcDataSource()
        ds.setURL("jdbc:h2:mem:object-catalog-" + System.nanoTime() + ";DB_CLOSE_DELAY=-1")
        SchemaInit.run(ds)
        jdbc = Jdbc(ds)
        catalogRepo = ObjectCatalogRepository(jdbc)
        dsRepo = DataSourceRepository(jdbc)
        metaCacheRepo = MetaCacheRepository(jdbc)
        service = ObjectCatalogService(catalogRepo, dsRepo, metaCacheRepo)
    }

    private fun newDs(name: String): Long = dsRepo.insert(DataSourceConfig().apply {
        this.name = name
        dbType = DbType.MYSQL
        jdbcUrl = "jdbc:mysql://localhost:3306/x"
    })

    @Test
    fun `三层目录挂载关系后loadTree结构正确且补注释`() {
        val dsId = newDs("生产库")
        val d1 = service.createDir(dsId, 0, "业务域")
        val d2 = service.createDir(dsId, d1, "客户")
        val d3 = service.createDir(dsId, d2, "核心")
        // 数据源隔离:另一数据源的同名目录不参与本树
        val otherDs = newDs("测试库")
        service.createDir(otherDs, 0, "业务域")

        val t1 = service.mountTable(d3, null, "public", "cust", "客户主表")
        val t2 = service.mountTable(d3, null, "public", "addr", null)
        service.addRelation(t1.id, null, "public", "ord", "订单关联")
        // meta_table 缓存注释:cust 有注释,ord 有,addr 缓存未覆盖
        metaCacheRepo.replaceTables(dsId, "", "public", listOf(
            MetaCacheRepository.CachedTable("cust", "客户信息表", null, null, null),
            MetaCacheRepository.CachedTable("ord", "订单表", null, null, null)))

        val tree = service.loadTree(dsId)
        assertEquals(0, tree.id)                       // 虚拟根
        assertEquals("", tree.name)
        assertTrue(tree.tables.isEmpty())
        assertEquals(1, tree.children.size)            // 数据源隔离
        val n1 = tree.children[0]
        assertEquals("业务域", n1.name)
        val n2 = n1.children[0]
        assertEquals("客户", n2.name)
        val n3 = n2.children[0]
        assertEquals("核心", n3.name)
        assertEquals(2, n3.tables.size)                // 按挂载时间排序:cust 先挂载,在前
        val cust = n3.tables[0]
        assertEquals("cust", cust.tableName)
        assertEquals("客户信息表", cust.comment)        // meta_table 补注释
        assertEquals("客户主表", cust.remark)
        assertEquals(1, cust.relations.size)
        assertEquals("ord", cust.relations[0].tableName)
        assertEquals("订单表", cust.relations[0].comment)
        assertEquals("订单关联", cust.relations[0].remark)
        assertEquals("", cust.dbName)                  // db null 归一空串
        assertEquals("addr", n3.tables[1].tableName)   // addr 后挂载,排在后面
        assertEquals("", n3.tables[1].comment)         // 缓存未覆盖给空串
        assertFalse(t1.existing)
        assertFalse(service.mountTable(d2, null, "public", "tmp", null).existing)
    }

    @Test
    fun `同级重名与父目录校验`() {
        val dsId = newDs("生产库")
        val d1 = service.createDir(dsId, 0, "业务域")
        service.createDir(dsId, 0, "归档")
        assertThrows(IllegalArgumentException::class.java) { service.createDir(dsId, 0, "业务域") }
        // 不同级/不同数据源可重名
        service.createDir(dsId, d1, "业务域")
        service.createDir(newDs("测试库"), 0, "业务域")
        // 父目录不存在或不属本数据源
        assertThrows(IllegalArgumentException::class.java) { service.createDir(dsId, 9999, "x") }
        assertThrows(IllegalArgumentException::class.java) { service.createDir(newDs("库三"), d1, "x") }
        // 数据源不存在
        assertThrows(IllegalArgumentException::class.java) { service.createDir(9999, 0, "x") }
        assertThrows(IllegalArgumentException::class.java) { service.loadTree(9999) }
        // 重命名:同名自身放行,撞同级兄弟 400,目录不存在 400
        service.renameDir(d1, "业务域")
        assertThrows(IllegalArgumentException::class.java) { service.renameDir(d1, "归档") }
        assertThrows(IllegalArgumentException::class.java) { service.renameDir(9999, "x") }
        service.renameDir(d1, "业务域二")
        assertEquals("业务域二", service.loadTree(dsId).children.map { it.name }.first { it.startsWith("业务域") })
    }

    @Test
    fun `parentId 为 null 等价根下顶层目录与 0 同口径`() {
        val dsId = newDs("生产库")
        val d1 = service.createDir(dsId, null, "业务域")   // null = 根下顶层目录
        val d2 = service.createDir(dsId, 0, "归档")        // 0 口径不变
        // 都落在虚拟根下,parent_id 统一为 0
        assertEquals(0, catalogRepo.findDir(d1)!!.parentId)
        assertEquals(0, catalogRepo.findDir(d2)!!.parentId)
        // 顶层目录默认按创建时间排序(此处 业务域 先建、归档 后建,恰好与 name 排序同序)
        assertEquals(listOf("业务域", "归档"), service.loadTree(dsId).children.map { it.name })
        // null 与 0 属于同一父级作用域:撞名按同级重名拦截
        assertThrows(IllegalArgumentException::class.java) { service.createDir(dsId, null, "归档") }
        assertThrows(IllegalArgumentException::class.java) { service.createDir(dsId, 0, "业务域") }
        // 子目录与父目录归属语义不受影响
        val child = service.createDir(dsId, d1, "客户")
        assertEquals(d1, catalogRepo.findDir(child)!!.parentId)
    }

    @Test
    fun `目录默认按创建时间排序且支持同级拖动重排`() {
        val dsId = newDs("生产库")
        val a = service.createDir(dsId, 0, "b目录")   // 名称码点大,创建在前(与 name 排序相反)
        val b = service.createDir(dsId, 0, "a目录")
        val c = service.createDir(dsId, 0, "c目录")
        // 默认按创建时间(而非 name):b目录 在前、a目录 居中
        assertEquals(listOf("b目录", "a目录", "c目录"), service.loadTree(dsId).children.map { it.name })
        // 同级作用域独立:父目录下的子目录各排各的
        val x = service.createDir(dsId, a, "x")
        val y = service.createDir(dsId, a, "y")
        assertEquals(listOf("x", "y"), service.loadTree(dsId).children.first { it.id == a }.children.map { it.name })

        // 根级重排(倒序):c,b,a → 显示 c目录,a目录,b目录
        service.reorderDirs(dsId, null, listOf(c, b, a))
        assertEquals(listOf("c目录", "a目录", "b目录"), service.loadTree(dsId).children.map { it.name })
        // 重排后新建目录追加同级末尾(默认顺序口径不变)
        service.createDir(dsId, 0, "d目录")
        assertEquals(listOf("c目录", "a目录", "b目录", "d目录"), service.loadTree(dsId).children.map { it.name })
        // 子目录重排只影响该父目录,不影响根级
        service.reorderDirs(dsId, a, listOf(y, x))
        assertEquals(listOf("y", "x"), service.loadTree(dsId).children.first { it.id == a }.children.map { it.name })
        assertEquals(listOf("c目录", "a目录", "b目录", "d目录"), service.loadTree(dsId).children.map { it.name })

        // 非法:跨父级 id / 列表内重复 / 数据源不存在 均 400
        assertThrows(IllegalArgumentException::class.java) { service.reorderDirs(dsId, null, listOf(x)) }
        assertThrows(IllegalArgumentException::class.java) { service.reorderDirs(dsId, null, listOf(a, a)) }
        assertThrows(IllegalArgumentException::class.java) { service.reorderDirs(9999, null, listOf(a)) }
    }

    @Test
    fun `挂载表与关系表按挂载登记时间排序且不随目录重排变化`() {
        val dsId = newDs("生产库")
        val dir = service.createDir(dsId, 0, "业务域")
        // 故意逆表名顺序挂载:zzz 先挂、aaa 后挂
        val zzz = service.mountTable(dir, null, "s", "zzz", null)
        service.mountTable(dir, null, "s", "aaa", null)
        // 关系表同样逆表名顺序登记:r-zzz 先登记
        service.addRelation(zzz.id, null, "s", "r-zzz", null)
        service.addRelation(zzz.id, null, "s", "r-aaa", null)

        val node = service.loadTree(dsId).children[0]
        assertEquals(listOf("zzz", "aaa"), node.tables.map { it.tableName })                    // 挂载时间,而非表名
        assertEquals(listOf("r-zzz", "r-aaa"), node.tables[0].relations.map { it.tableName })   // 登记时间,而非表名
        // 重复挂载命中幂等不改变顺序
        service.mountTable(dir, null, "s", "zzz", "再次挂载")
        assertEquals(listOf("zzz", "aaa"), service.loadTree(dsId).children[0].tables.map { it.tableName })
        // 目录重排不影响表顺序(表序只看挂载时间)
        service.reorderDirs(dsId, null, listOf(dir))
        assertEquals(listOf("zzz", "aaa"), service.loadTree(dsId).children[0].tables.map { it.tableName })
    }

    @Test
    fun `删除目录级联统计正确`() {
        val dsId = newDs("生产库")
        val d1 = service.createDir(dsId, 0, "业务域")
        val d2 = service.createDir(dsId, d1, "客户")
        val d3 = service.createDir(dsId, d2, "核心")
        val keep = service.createDir(dsId, 0, "保留")
        val t1 = service.mountTable(d2, null, "s", "t1", null)
        service.mountTable(d3, null, "s", "t2", null)
        service.mountTable(d3, null, "s", "t3", null)
        service.addRelation(t1.id, null, "s", "r1", null)
        service.addRelation(t1.id, null, "s", "r2", null)
        val tKeep = service.mountTable(keep, null, "s", "k1", null)
        service.addRelation(tKeep.id, null, "s", "k2", null)

        // 删 d1 级联 d2/d3:3 目录、3 挂载(t1/t2/t3)、2 关系(r1/r2);keep 子树不受影响
        val result = service.deleteDir(d1)
        assertEquals(3, result.dirs)
        assertEquals(3, result.tables)
        assertEquals(2, result.rels)
        val tree = service.loadTree(dsId)
        assertEquals(listOf("保留"), tree.children.map { it.name })
        assertEquals(1, tree.children[0].tables.size)
        assertEquals(1, tree.children[0].tables[0].relations.size)
        assertThrows(IllegalArgumentException::class.java) { service.deleteDir(d1) }
    }

    @Test
    fun `取消挂载级联删关系且重复挂载幂等`() {
        val dsId = newDs("生产库")
        val dir = service.createDir(dsId, 0, "业务域")
        val first = service.mountTable(dir, null, "s", "t1", "备注")
        val dup = service.mountTable(dir, "", "s", "t1", "别的备注")   // db 空串与 null 同口径
        assertTrue(dup.existing)
        assertEquals(first.id, dup.id)
        service.addRelation(first.id, null, "s", "r1", null)

        service.unmount(first.id)
        val tree = service.loadTree(dsId)
        assertTrue(tree.children[0].tables.isEmpty())
        // 关系已级联删除;挂载与关系记录均不存在
        assertThrows(IllegalArgumentException::class.java) { service.unmount(first.id) }
        assertThrows(IllegalArgumentException::class.java) { service.addRelation(first.id, null, "s", "r1", null) }
        assertThrows(IllegalArgumentException::class.java) { service.removeRelation(9999) }
        assertThrows(IllegalArgumentException::class.java) { service.mountTable(9999, null, "s", "t", null) }
    }

    @Test
    fun `关系幂等且不允许指向自身`() {
        val dsId = newDs("生产库")
        val dir = service.createDir(dsId, 0, "业务域")
        val mount = service.mountTable(dir, null, "s", "t1", null)
        // 指向自身(同四元组,db null 与空串同口径)
        assertThrows(IllegalArgumentException::class.java) {
            service.addRelation(mount.id, "", "s", "t1", null)
        }
        val rel = service.addRelation(mount.id, null, "s", "t2", null)
        assertFalse(rel.existing)
        val dup = service.addRelation(mount.id, null, "s", "t2", null)
        assertTrue(dup.existing)
        assertEquals(rel.id, dup.id)
        assertEquals(1, service.loadTree(dsId).children[0].tables[0].relations.size)

        service.removeRelation(rel.id)
        assertTrue(service.loadTree(dsId).children[0].tables[0].relations.isEmpty())
    }

    @Test
    fun `批量挂载与批量关系统计正确`() {
        val dsId = newDs("生产库")
        val dir = service.createDir(dsId, 0, "业务域")
        // 批量挂载:3 新 + 1 重复 + 列表内去重
        val r1 = service.mountTables(dir, null, "s",
            listOf(ObjectTableMountItem("a", null), ObjectTableMountItem("b", null), ObjectTableMountItem("c", null)), null)
        assertEquals(3, r1.mounted)
        assertEquals(0, r1.existing)
        val r2 = service.mountTables(dir, null, "s",
            listOf(ObjectTableMountItem("a", null), ObjectTableMountItem("d", null), ObjectTableMountItem("d", null)), null)
        assertEquals(1, r2.mounted)
        assertEquals(1, r2.existing)
        assertThrows(IllegalArgumentException::class.java) {
            service.mountTables(9999, null, "s", listOf(ObjectTableMountItem("x", null)), null)
        }

        // 批量关系:含指向自身(抛错)与重复
        val mount = service.mountTable(dir, null, "s", "m", null)
        val r3 = service.addRelations(mount.id, null, "s",
            listOf(ObjectTableMountItem("r1", null), ObjectTableMountItem("r2", null)), null)
        assertEquals(2, r3.mounted)
        val r4 = service.addRelations(mount.id, null, "s",
            listOf(ObjectTableMountItem("r1", null), ObjectTableMountItem("r3", null)), null)
        assertEquals(1, r4.mounted)
        assertEquals(1, r4.existing)
        assertThrows(IllegalArgumentException::class.java) {
            service.addRelations(mount.id, null, "s", listOf(ObjectTableMountItem("m", null)), null)
        }
    }

    @Test
    fun `移动目录变更所属且防环防重名`() {
        val dsId = newDs("生产库")
        val a = service.createDir(dsId, 0, "a")
        val b = service.createDir(dsId, 0, "b")
        val a1 = service.createDir(dsId, a, "a1")
        val a1x = service.createDir(dsId, a1, "a1x")

        // 移动到其它目录/根,parentId 随之更新
        service.moveDir(a1x, b)
        assertEquals(b, catalogRepo.findDir(a1x)!!.parentId)
        service.moveDir(a1x, 0)
        assertEquals(0, catalogRepo.findDir(a1x)!!.parentId)
        service.moveDir(a1x, null)   // 已在根下:null 等价 0,no-op
        assertEquals(0, catalogRepo.findDir(a1x)!!.parentId)
        // 移动到原父目录 no-op
        service.moveDir(a1, a)
        assertEquals(a, catalogRepo.findDir(a1)!!.parentId)
        // 移动后追加为新同级末尾(默认同级顺序=创建时间口径,重排接口可再调)
        val c = service.createDir(dsId, 0, "c")
        service.moveDir(c, a)
        assertEquals(listOf("a1", "c"), service.loadTree(dsId).children.first { it.id == a }.children.map { it.name })

        // 非法:目录不存在/目标是自身/目标是子孙(含间接)/跨数据源/同级重名
        assertThrows(IllegalArgumentException::class.java) { service.moveDir(9999, 0) }
        assertThrows(IllegalArgumentException::class.java) { service.moveDir(a, a) }
        service.moveDir(a1x, a1)   // 先放回 a 的子孙链,再验证移向子孙被防环拦截
        assertThrows(IllegalArgumentException::class.java) { service.moveDir(a, a1) }
        assertThrows(IllegalArgumentException::class.java) { service.moveDir(a, a1x) }
        val otherDir = service.createDir(newDs("库二"), 0, "x")
        assertThrows(IllegalArgumentException::class.java) { service.moveDir(a, otherDir) }
        // 新同级重名 400:b 下已有同名目录 a
        service.createDir(dsId, b, "a")
        assertThrows(IllegalArgumentException::class.java) { service.moveDir(a, b) }
    }

    @Test
    fun `移动挂载表变更所属目录`() {
        val dsId = newDs("生产库")
        val d1 = service.createDir(dsId, 0, "d1")
        val d2 = service.createDir(dsId, 0, "d2")
        val t = service.mountTable(d1, null, "s", "t1", null)
        service.addRelation(t.id, null, "s", "r1", null)

        // 移动到其它目录,其关系表跟随挂载记录
        service.moveTable(t.id, d2)
        assertEquals(d2, catalogRepo.findTableById(t.id)!!.dirId)
        val node = service.loadTree(dsId).children.first { it.id == d2 }
        assertEquals("t1", node.tables[0].tableName)
        assertEquals(listOf("r1"), node.tables[0].relations.map { it.tableName })
        // 同目录 no-op
        service.moveTable(t.id, d2)
        assertEquals(d2, catalogRepo.findTableById(t.id)!!.dirId)

        // 非法:挂载不存在/目标目录不存在/跨数据源/目标已挂载同四元组表
        assertThrows(IllegalArgumentException::class.java) { service.moveTable(9999, d2) }
        assertThrows(IllegalArgumentException::class.java) { service.moveTable(t.id, 9999) }
        val otherDs = newDs("库二")
        val otherDir = service.createDir(otherDs, 0, "x")
        assertThrows(IllegalArgumentException::class.java) { service.moveTable(t.id, otherDir) }
        service.mountTable(d1, null, "s", "t1", null)
        assertThrows(IllegalArgumentException::class.java) { service.moveTable(t.id, d1) }
    }

    @Test
    fun `移动关系表变更所属挂载表`() {
        val dsId = newDs("生产库")
        val d1 = service.createDir(dsId, 0, "d1")
        val m1 = service.mountTable(d1, null, "s", "t1", null)
        val m2 = service.mountTable(d1, null, "s", "t2", null)
        val r = service.addRelation(m1.id, null, "s", "r1", null)

        // 移动到其它挂载表
        service.moveRelation(r.id, m2.id)
        assertEquals(m2.id, catalogRepo.findRelById(r.id)!!.objectTableId)
        val tables = service.loadTree(dsId).children[0].tables
        assertTrue(tables.first { it.tableName == "t1" }.relations.isEmpty())
        assertEquals(listOf("r1"), tables.first { it.tableName == "t2" }.relations.map { it.tableName })
        // 同挂载表 no-op
        service.moveRelation(r.id, m2.id)
        assertEquals(m2.id, catalogRepo.findRelById(r.id)!!.objectTableId)

        // 非法:关系不存在/目标挂载不存在/跨数据源/指向目标自身/目标已登记同四元组
        assertThrows(IllegalArgumentException::class.java) { service.moveRelation(9999, m1.id) }
        assertThrows(IllegalArgumentException::class.java) { service.moveRelation(r.id, 9999) }
        val otherDs = newDs("库二")
        val otherMount = service.mountTable(service.createDir(otherDs, 0, "x"), null, "s", "t9", null)
        assertThrows(IllegalArgumentException::class.java) { service.moveRelation(r.id, otherMount.id) }
        val mRel = service.mountTable(d1, null, "s", "r1", null)
        assertThrows(IllegalArgumentException::class.java) { service.moveRelation(r.id, mRel.id) }
        service.addRelation(m1.id, null, "s", "r1", null)
        assertThrows(IllegalArgumentException::class.java) { service.moveRelation(r.id, m1.id) }
    }

    @Test
    fun `挂载表关系类型relKind校验与重复挂载修正`() {
        val dsId = newDs("生产库")
        val dir = service.createDir(dsId, 0, "业务域")
        // 挂载时带关系类型(大小写不敏感,归一大写)
        val t1 = service.mountTable(dir, null, "s", "t1", null, "include")
        assertFalse(t1.existing)
        assertEquals("INCLUDE", service.loadTree(dsId).children[0].tables[0].relKind)
        // 不带 relKind 默认 null
        service.mountTable(dir, null, "s", "t2", null)
        assertEquals(null, service.loadTree(dsId).children[0].tables[1].relKind)
        // 非法值 400
        assertThrows(IllegalArgumentException::class.java) { service.mountTable(dir, null, "s", "t3", null, "父子") }
        // 重复挂载幂等:relKind 非空则修正现值
        val dup = service.mountTable(dir, null, "s", "t1", null, "ASSOC")
        assertTrue(dup.existing)
        assertEquals("ASSOC", service.loadTree(dsId).children[0].tables[0].relKind)
        // 重复挂载 relKind 为空不覆盖现值
        service.mountTable(dir, null, "s", "t1", null, null)
        assertEquals("ASSOC", service.loadTree(dsId).children[0].tables[0].relKind)
    }

    @Test
    fun `关系表关系类型relKind校验与重复登记修正`() {
        val dsId = newDs("生产库")
        val dir = service.createDir(dsId, 0, "业务域")
        val mount = service.mountTable(dir, null, "s", "m", null)
        // 登记时带关系类型(大小写不敏感,归一大写)
        service.addRelation(mount.id, null, "s", "r1", null, "assoc")
        assertEquals("ASSOC", service.loadTree(dsId).children[0].tables[0].relations[0].relKind)
        // 不带 relKind 默认 null
        service.addRelation(mount.id, null, "s", "r2", null)
        assertEquals(null, service.loadTree(dsId).children[0].tables[0].relations[1].relKind)
        // 非法值 400
        assertThrows(IllegalArgumentException::class.java) { service.addRelation(mount.id, null, "s", "r3", null, "父子") }
        // 重复登记幂等:relKind 非空则修正现值,为空不覆盖
        val dup = service.addRelation(mount.id, null, "s", "r1", null, "INCLUDE")
        assertTrue(dup.existing)
        assertEquals("INCLUDE", service.loadTree(dsId).children[0].tables[0].relations[0].relKind)
        service.addRelation(mount.id, null, "s", "r1", null, null)
        assertEquals("INCLUDE", service.loadTree(dsId).children[0].tables[0].relations[0].relKind)
    }
}
