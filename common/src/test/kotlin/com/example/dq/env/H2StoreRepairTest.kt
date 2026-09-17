package com.example.dq.env

import com.example.dq.repository.SchemaInit
import org.h2.jdbcx.JdbcDataSource
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.RandomAccessFile
import java.nio.file.Files
import java.nio.file.Path
import java.sql.DriverManager
import java.util.function.Consumer
import kotlin.io.path.name

/** H2 文件库损坏自愈:健康库不动、损坏库备份 + 尽力重建 */
class H2StoreRepairTest {

    @TempDir
    lateinit var dir: Path

    private val logs = mutableListOf<String>()
    private val log = Consumer<String> { logs.add(it) }

    /** 建一个带数据的文件库并关闭;bigPayload=true 时每行塞 1KB 随机内容把文件撑大 */
    private fun createDb(db: String, rows: Int = 10, bigPayload: Boolean = false) {
        DriverManager.getConnection("jdbc:h2:file:${dir.toAbsolutePath()}/$db", "sa", "").use { conn ->
            conn.createStatement().use { st ->
                st.execute("CREATE TABLE t(id INT PRIMARY KEY, note VARCHAR)")
                if (bigPayload) {
                    st.execute("INSERT INTO t SELECT x, REPEAT('x', 800) || x FROM SYSTEM_RANGE(1, $rows)")
                } else {
                    repeat(rows) { st.execute("INSERT INTO t VALUES($it, 'note-$it')") }
                }
            }
        }
    }

    private fun countRows(db: String): Int =
        DriverManager.getConnection("jdbc:h2:file:${dir.toAbsolutePath()}/$db", "sa", "").use { conn ->
            conn.createStatement().use { st ->
                st.executeQuery("SELECT COUNT(*) FROM t").use { rs -> rs.next(); rs.getInt(1) }
            }
        }

    /** 用垃圾字节覆盖文件的指定区段 */
    private fun corrupt(file: Path, offset: Long, length: Int) {
        RandomAccessFile(file.toFile(), "rw").use { raf ->
            raf.seek(offset)
            raf.write(ByteArray(length) { 0xAB.toByte() })
        }
    }

    private fun backupFiles(): List<String> =
        Files.list(dir).use { it.map { p -> p.name }.filter { n -> n.contains("-corrupted-") }.toList() }

    @Test
    fun `健康库不做任何处理,数据原样保留`() {
        createDb("dqconfig", 20)
        H2StoreRepair.repairIfCorrupted(dir, "dqconfig", log)
        assertTrue(logs.isEmpty(), "健康库不应产生修复日志: $logs")
        assertTrue(backupFiles().isEmpty(), "健康库不应产生备份文件")
        assertEquals(20, countRows("dqconfig"))
    }

    @Test
    fun `库文件不存在直接跳过`() {
        H2StoreRepair.repairIfCorrupted(dir, "dqconfig", log)
        assertTrue(logs.isEmpty())
        assertTrue(backupFiles().isEmpty())
    }

    @Test
    fun `残留锁文件不误判损坏,健康库正常放行`() {
        createDb("dqconfig", 20)
        // 复刻上次进程被强杀留下的残留锁:先以 AUTO_SERVER 打开拿到真实锁文件内容,关闭后写回
        DriverManager.getConnection(
            "jdbc:h2:file:${dir.toAbsolutePath()}/dqconfig;AUTO_SERVER=TRUE", "sa", ""
        ).use {
            Files.write(dir.resolve("dqconfig.lock.db.stale"), Files.readAllBytes(dir.resolve("dqconfig.lock.db")))
        }
        Files.move(dir.resolve("dqconfig.lock.db.stale"), dir.resolve("dqconfig.lock.db"))

        H2StoreRepair.repairIfCorrupted(dir, "dqconfig", log)

        assertTrue(logs.isEmpty(), "残留锁不应触发修复: $logs")
        assertTrue(backupFiles().isEmpty(), "残留锁不应产生备份文件")
        assertEquals(20, countRows("dqconfig"))
    }

    @Test
    fun `文件头损毁的库备份后以空库重建,程序可正常启动`() {
        createDb("dqconfig")
        val mvdb = dir.resolve("dqconfig.mv.db")
        // 文件头(前两个块)全毁:Recover 也救不出数据,走空库兜底路径
        corrupt(mvdb, 0, 8192)

        H2StoreRepair.repairIfCorrupted(dir, "dqconfig", log)

        val backups = backupFiles()
        assertTrue(backups.any { it.endsWith(".mv.db") }, "损坏文件必须备份保留: $backups")
        assertTrue(logs.any { it.contains("文件损坏") }, "修复过程要落日志: $logs")
        // 新库能正常打开(空库),Flyway 迁移可正常执行——程序起得来
        val ds = JdbcDataSource()
        ds.setURL("jdbc:h2:file:${dir.toAbsolutePath()}/dqconfig")
        ds.user = "sa"
        SchemaInit.run(ds)
        DriverManager.getConnection("jdbc:h2:file:${dir.toAbsolutePath()}/dqconfig", "sa", "").use { }
    }

    @Test
    fun `中部数据区损坏的库经 Recover 重建后可打开,数据尽量保留`() {
        // 写足量数据把文件撑大,确保中段确有数据页
        createDb("dqconfig", 2000, bigPayload = true)
        val mvdb = dir.resolve("dqconfig.mv.db")
        val size = Files.size(mvdb)
        assertTrue(size > 100_000, "测试库文件过小($size),损坏位置可能不命中数据页")
        // 同时污染中段与尾部(最新 chunk 元数据所在),保证打开即报 File corrupted
        corrupt(mvdb, size / 2, 64 * 1024)
        corrupt(mvdb, size - 8192, 4096)

        H2StoreRepair.repairIfCorrupted(dir, "dqconfig", log)

        assertTrue(backupFiles().any { it.endsWith(".mv.db") }, "损坏文件必须备份保留")
        assertTrue(logs.any { it.contains("文件损坏") }, "修复过程要落日志: $logs")
        // 重建后新库可正常打开,且 Flyway 迁移能跑(模拟真实启动流程)
        val ds = JdbcDataSource()
        ds.setURL("jdbc:h2:file:${dir.toAbsolutePath()}/dqconfig")
        ds.user = "sa"
        SchemaInit.run(ds)
        DriverManager.getConnection("jdbc:h2:file:${dir.toAbsolutePath()}/dqconfig", "sa", "").use { }
    }
}
