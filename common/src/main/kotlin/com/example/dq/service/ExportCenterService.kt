package com.example.dq.service

import com.example.dq.model.ExportCenterItem
import com.example.dq.model.ExportCenterPage
import com.example.dq.model.ExportKind
import com.example.dq.repository.ExportRecordRepository
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import java.time.LocalDateTime

/**
 * 导出中心(V66 登记 / V67 push / 点击即登记的状态机):**点击导出,导出中心立刻出一条「生成中」记录**;
 * 文件落盘翻成功,失败/服务重启各有终态。关联口径:
 * - 同步流式导出:端点入口 `recordStart`(params 记原路径)→ `downloadFile` 成功 `landed` 按文件名翻 SUCCESS,
 *   前端失败 `failByPath` 按路径标 FAILED;
 * - 异步任务(报告 Word/抽样 zip):提交时 `recordStart`(params 记 `key: 业务:<id>`)→ 完成 `finalizeByKey`
 *   (成功补文件名/路径/大小,失败带 error);取消删除/重启中断同样走 finalize。
 * 同名覆盖导出(比对报告等固定文件名直存):新记录落盘成功后清除同 rel_path 旧记录,列表只留最新一条。
 * 登记失败只记日志,绝不影响导出本身。
 */
class ExportCenterService(
    private val repo: ExportRecordRepository,
    /** 数据目录:landed() 据此实测 exports/<文件名> 的大小;单测可空 */
    private val dataDir: Path? = null,
) {

    private val log = LoggerFactory.getLogger(javaClass)
    private val objectMapper = jacksonObjectMapper()

    /** 组装 params_json(格式固定,repository 按精确串匹配) */
    private fun params(key: String?, path: String?): String? = when {
        key != null -> objectMapper.writeValueAsString(mapOf("key" to key))
        path != null -> objectMapper.writeValueAsString(mapOf("path" to path))
        else -> null
    }

    /**
     * 点击即登记:插入 RUNNING 记录,返回 id(失败返回 null 不阻断导出)。
     * @param key  异步任务关联键(如 "report-export:12");同步导出传 null 用 path
     * @param path 同步导出的完整 API 路径(失败标记/潜在重放用)
     */
    @JvmOverloads
    fun recordStart(kind: ExportKind, title: String, fileName: String? = null,
                    key: String? = null, path: String? = null): Long? {
        return try {
            repo.insert(kind.name, title.take(500), fileName?.take(500), null,
                params(key, path), null, "RUNNING")
        } catch (e: Exception) {
            log.error("导出中心登记失败(导出本身不受影响): kind={}, title={}", kind, title, e)
            null
        }
    }

    /** 统一直存(downloadFile)成功后的终态:按文件名找最新 RUNNING 记录,
     * 回填 rel_path + 实测大小 + SHA-256 + SUCCESS */
    fun landed(fileName: String) {
        val name = fileName.trim()
        if (name.isEmpty() || name.contains('/') || name.contains('\\')) return
        try {
            val id = repo.findLatestRunningByFileName(name) ?: return
            val abs = dataDir?.resolve("exports")?.resolve(name)
            val size = abs?.let { if (Files.isRegularFile(it)) Files.size(it) else null }
            val checksum = abs?.let { if (Files.isRegularFile(it)) sha256(it) else null }
            repo.updateLanded(id, "exports/$name", size, checksum)
            log.info("导出中心回填: file={}, relPath=exports/{}, size={}, sha256={}", name, name, size, checksum)
            purgeOverwritten("exports/$name", id)
        } catch (e: Exception) {
            log.error("导出中心回填失败: file={}", name, e)
        }
    }

    /**
     * 异步任务/同步导出终态:按 key(或 path)找最新记录;成功补 文件名/路径/大小/校验和,
     * 失败带 error。artifact 提供时自动实测大小 + SHA-256(Kotlin 调用方优先用);
     * Java 调用方(比对)已知 checksum 时直接传 checksum。
     */
    @JvmOverloads
    fun finalize(kind: ExportKind, key: String? = null, path: String? = null,
                 fileName: String? = null, relPath: String? = null,
                 artifact: Path? = null, checksum: String? = null, error: String? = null) {
        try {
            val params = params(key, path) ?: return
            val id = repo.findLatestByParams(params) ?: return
            val size = artifact?.let { Files.size(it) }
            val sha = checksum ?: artifact?.let { sha256(it) }
            if (error == null) {
                repo.updateFinal(id, "SUCCESS", fileName, relPath, size, sha, null)
                log.info("导出中心终态 SUCCESS: kind={}, file={}", kind, fileName)
                if (relPath != null) purgeOverwritten(relPath, id)
            } else {
                repo.updateFinal(id, "FAILED", fileName, relPath, size, sha, error.take(1000))
                log.info("导出中心终态 FAILED: kind={}, error={}", kind, error)
            }
        } catch (e: Exception) {
            log.error("导出中心终态更新失败: kind={}, key={}", kind, key ?: path, e)
        }
    }

    /** 服务重启:残留 RUNNING 一律 FAILED(报告/抽样等异步任务的执行体已随 JVM 消亡) */
    fun recoverInterrupted() {
        try {
            val n = repo.failRunning("服务重启,导出中断")
            if (n > 0) log.warn("服务重启,{} 条「生成中」导出记录已置为失败", n)
        } catch (e: Exception) {
            log.error("导出中心重启恢复失败", e)
        }
    }

    /** 导出失败终态:按 params.path(点击登记时的原路径)找最新记录标 FAILED;无记录静默跳过 */
    fun failByPath(path: String, error: String) {
        try {
            val params = objectMapper.writeValueAsString(mapOf("path" to path))
            val id = repo.findLatestByParams(params) ?: return
            repo.updateFinal(id, "FAILED", null, null, null, null, error.take(1000))
            log.info("导出中心终态 FAILED: path={}, error={}", path, error)
        } catch (e: Exception) {
            log.error("导出中心失败标记出错: path={}", path, e)
        }
    }

    /** 同名覆盖导出:新记录落盘成功后,同 rel_path 的旧记录一并清除(旧文件已被覆盖,留着只会校验失配) */
    private fun purgeOverwritten(relPath: String, keepId: Long) {
        val n = repo.deleteOthersByRelPath(relPath, keepId)
        if (n > 0) log.info("导出中心同名覆盖清理: relPath={}, 清除旧记录 {} 条", relPath, n)
    }

    /** SHA-256 hex(流式读盘,landed/finalize 共用) */
    private fun sha256(file: Path): String {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        Files.newInputStream(file).buffered().use { input ->
            val buf = ByteArray(1 shl 20)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                digest.update(buf, 0, n)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    /** 统一分页列表;keyword 对描述/文件名模糊,start/end(含当日)按创建时间过滤;
     *  每行做完整性校验(文件现算 SHA-256 与记录比对,带 size+mtime 缓存)透出 fileState */
    fun list(kind: ExportKind?, keyword: String?, start: LocalDateTime?, end: LocalDateTime?,
             page: Int, size: Int): ExportCenterPage {
        val p = repo.page(kind?.name, keyword?.trim()?.takeIf { it.isNotEmpty() }, start,
            end?.plusDays(1), page.coerceAtLeast(1), size.coerceIn(1, 200))
        return p.copy(items = p.items.map { it.copy(fileState = verifyState(it)) })
    }

    /** 完整性校验缓存:绝对路径 → (size, mtime, state);导出件写后不变,按 size+mtime 失效,列表轮询不反复哈希 */
    private val verifyCache = java.util.concurrent.ConcurrentHashMap<String, Triple<Long, Long, String>>()

    /** OK/TAMPERED(被改)/MISSING(被删);null=无 relPath 不可判定(老记录) */
    private fun verifyState(item: ExportCenterItem): String? {
        val rel = item.relPath ?: return null
        val base = dataDir?.toAbsolutePath()?.normalize() ?: return null
        return try {
            val file = base.resolve(rel).normalize()
            if (!file.startsWith(base)) return "MISSING"
            if (!Files.isRegularFile(file)) return "MISSING"
            if (item.checksum == null) return "OK"
            val abs = file.toString()
            val size = Files.size(file)
            val mtime = Files.getLastModifiedTime(file).toMillis()
            val hit = verifyCache[abs]
            if (hit != null && hit.first == size && hit.second == mtime) return hit.third
            val state = if (sha256(file) == item.checksum) "OK" else "TAMPERED"
            verifyCache[abs] = Triple(size, mtime, state)
            state
        } catch (e: Exception) {
            log.warn("导出中心完整性校验失败: rel={}, {}", rel, e.message)
            "MISSING"
        }
    }

    /** 删除登记记录(仅删记录不动磁盘文件);未知 kind 400、不存在 id 400 */
    fun delete(kindKey: String, id: Long) {
        val kind = ExportKind.parse(kindKey)
            ?: throw IllegalArgumentException("未知导出类型: $kindKey")
        if (repo.delete(id) == 0) throw IllegalArgumentException("导出记录不存在: $id")
    }
}
