package com.example.dq.service

import com.example.dq.util.ConnectionFailureClassifier
import org.slf4j.LoggerFactory

/**
 * 元数据回源失败降级本地 H2 缓存的通用编排(库/schema/表/字段/索引浏览与刷新路径共用)。
 *
 * - 回源成功 → 回调 `onSuccess` 让数据源连接状态自愈(此前被标记 ERROR 则恢复 OK)
 * - 回源抛连接级异常 → 回调 `onFailure` 写数据源标记;本地有缓存则降级返回缓存并置降级标志,
 *   无缓存时调用方可再挂一级「最新扫描快照」降级(fetch 的 readSnapshot 参数),都兜不住才原样抛出
 * - 非连接级异常(SQL 语法/对象不存在等)原样抛出,不降级,避免掩盖真实错误
 *
 * 数据源状态标记是辅助观测,写失败只记 warn 日志、不改变主流程结果(否则标记失败会把一次成功的
 * 浏览变成报错);降级标志按线程存放:Javalin handler 与 service 同线程,壳层写响应前读取并回写响应头,
 * 让前端知道「本次是缓存降级数据」。每次 fetch 入口复位、读取即清,避免线程复用串味。
 */
class CacheFallback(
    private val onFailure: (datasourceId: Long, message: String, kind: String) -> Unit,
    private val onSuccess: (datasourceId: Long) -> Unit,
) {

    private val log = LoggerFactory.getLogger(CacheFallback::class.java)

    private val fellBack = ThreadLocal.withInitial { false }

    /**
     * 回源并在连接失败时降级读缓存。
     * `hasCache`/`readCache` 只在失败路径调用,正常情况下没有额外开销。
     *
     * 降级链:回源连接失败 → 本地缓存(有则返回)→ [readSnapshot] 最新扫描快照(传入且快照存在时返回,
     * 由调用方顺带把快照回填进缓存)→ 都兜不住则原样抛出。快照降级同样置降级标志。
     */
    fun <R> fetch(
        datasourceId: Long,
        hasCache: () -> Boolean,
        readCache: () -> R,
        readSnapshot: (() -> R?)? = null,
        fetch: () -> R,
    ): R {
        fellBack.set(false)
        return try {
            fetch().also { runCatching { onSuccess(datasourceId) }.onFailure { log.warn("恢复数据源 {} 连接状态失败(忽略)", datasourceId, it) } }
        } catch (e: Exception) {
            if (!ConnectionFailureClassifier.isConnectionFailure(e)) throw e
            record(datasourceId, e)
            if (hasCache()) {
                fellBack.set(true)
                return readCache()
            }
            // 无缓存可兜:再试最新扫描快照(断网但本机扫过该库,快照还原结构后由调用方回填缓存)
            if (readSnapshot != null) {
                val snap = runCatching { readSnapshot() }
                    .onFailure { log.warn("数据源 {} 读扫描快照降级失败(忽略): {}", datasourceId, it.message) }
                    .getOrNull()
                if (snap != null) {
                    fellBack.set(true)
                    return snap
                }
            }
            throw e
        }
    }

    /** 读取并复位本次调用的降级标志 */
    fun consumeFallback(): Boolean {
        val v = fellBack.get()
        fellBack.set(false)
        return v
    }

    private fun record(datasourceId: Long, e: Exception) {
        runCatching {
            onFailure(datasourceId, ConnectionFailureClassifier.describe(e), ConnectionFailureClassifier.classify(e))
        }.onFailure { log.warn("写数据源 {} 连接失败标记失败(忽略)", datasourceId, it) }
    }
}
