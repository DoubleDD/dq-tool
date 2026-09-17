package com.example.dq.env

import org.h2.tools.Recover
import org.h2.tools.RunScript
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.sql.DriverManager
import java.sql.SQLException
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.function.Consumer
import kotlin.io.path.exists
import kotlin.io.path.fileSize
import kotlin.io.path.name

/**
 * H2 文件库启动前健康预检与损坏自愈。
 *
 * 背景:客户跨多个版本升级后启动报 `MVStoreException: File corrupted in chunk ...`
 * (异常关机/断电/强杀进程留下的半截写入),Hikari 池初始化失败导致进程直接退出,
 * 用户没有任何自救入口。此处赶在 ServiceEnv 打开连接池之前:
 * 1. 探测打开一次 `*.mv.db`(URL 与正式启动同走 AUTO_SERVER=TRUE,可自动接管上次强杀留下的残留锁文件);
 * 2. 探测失败且异常链确认为 MVStore 损坏 → 损坏文件重命名备份(永不删除,可手工排查),
 *    用 H2 自带 [Recover] 尽力导出 SQL,再 [RunScript] 重建成新库;
 * 3. 导出/重建失败则以空库启动(Flyway 迁移建表,数据可从导出文件重新导入),保证程序起得来。
 *
 * 必须在单实例锁获取之后调用(独占数据目录,无其他进程持有文件)。
 * 非损坏类失败(文件被占用等)不碰任何文件,原样上抛。
 */
object H2StoreRepair {

    /** 启动预检入口:主库 dqconfig + AI 用量库 dqaiusage 逐一处理。log 由壳层注入(server 落 startup.log) */
    @JvmStatic
    fun checkAll(dataDir: Path, log: Consumer<String>) {
        repairIfCorrupted(dataDir, "dqconfig", log)
        repairIfCorrupted(dataDir, "dqaiusage", log)
    }

    /** 单库预检:健康直接返回;确认损坏则备份 + 尽力修复。非损坏类打开失败原样上抛 */
    fun repairIfCorrupted(dir: Path, db: String, log: Consumer<String>) {
        val mvdb = dir.resolve("$db.mv.db")
        if (!mvdb.exists()) return
        val base = dir.toAbsolutePath()
        // 探测 URL 与正式启动同走 AUTO_SERVER=TRUE:上次进程被强杀会留下残留 .lock.db,
        // 不带 AUTO_SERVER 的 URL 会直接报 "Database may be already in use"(90020)把启动卡死;
        // AUTO_SERVER 探测到锁文件里的 TCP 端口无响应后自动接管残留锁。IFEXISTS 防止误建新库
        try {
            DriverManager.getConnection("jdbc:h2:file:$base/$db;IFEXISTS=TRUE;AUTO_SERVER=TRUE", "sa", "").use { }
            return
        } catch (e: SQLException) {
            if (!isCorruption(e)) throw e
            log.accept("检测到 H2 库 $db 文件损坏(${rootMessage(e)}),启动自动修复...")
        }

        // 备份损坏文件:保留 .mv.db 后缀,Recover 工具可直接按库名读取
        val ts = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"))
        val backupName = "$db-corrupted-$ts"
        Files.move(mvdb, dir.resolve("$backupName.mv.db"), StandardCopyOption.REPLACE_EXISTING)
        log.accept("损坏文件已备份为 $backupName.mv.db(永久保留,不自动删除)")

        // Recover 尽力导出 SQL 脚本;坏得太狠(如文件头全毁)导出会失败或只出空脚本。
        // 注意 H2 工具类内部有裸 assert,损坏文件可能抛 AssertionError,必须按 Throwable 兜
        val sqlFile = dir.resolve("$backupName.h2.sql")
        var dumped = false
        try {
            Recover.execute(base.toString(), backupName)
            dumped = sqlFile.exists() && sqlFile.fileSize() > 0
        } catch (e: Throwable) {
            log.accept("Recover 导出失败(${e.message ?: e.javaClass.simpleName})")
        }
        if (!dumped) {
            log.accept("未能从损坏文件导出有效数据,H2 库 $db 将以空库启动(数据可经导入功能恢复)")
            return
        }

        // 重建:continueOnError=true 跳过导出资产生断的语句,尽量多救数据
        try {
            log.accept("Recover 导出完成($backupName.h2.sql, ${sqlFile.fileSize() / 1024}KB),开始重建数据库...")
            RunScript.execute("jdbc:h2:file:$base/$db;AUTO_SERVER=TRUE", "sa", "", sqlFile.toString(), StandardCharsets.UTF_8, true)
            log.accept("H2 库 $db 重建完成,已尽量恢复数据")
        } catch (e: Throwable) {
            // 重建失败不留半截新库:删掉让 Flyway 从空库建表,备份与导出脚本保留在数据目录
            log.accept("H2 库 $db 重建失败(${e.message ?: e.javaClass.simpleName}),以空库启动;损坏备份与导出脚本保留在数据目录")
            Files.deleteIfExists(dir.resolve("$db.mv.db"))
        }
    }

    /** 异常链上是否确认为 MVStore 存储层损坏(仅此情形才允许动数据文件) */
    private fun isCorruption(e: Throwable): Boolean {
        var cur: Throwable? = e
        while (cur != null) {
            if (cur.javaClass.name == "org.h2.mvstore.MVStoreException") return true
            if (cur.message?.contains("File corrupted") == true) return true
            cur = cur.cause
        }
        return false
    }

    /** 取异常链最底层消息,拼进启动日志便于排障 */
    private fun rootMessage(e: Throwable): String {
        var cur = e
        while (cur.cause != null && cur.cause != cur) cur = cur.cause!!
        return cur.message ?: cur.javaClass.simpleName
    }
}
