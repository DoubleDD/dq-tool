package com.example.dq.service

import com.example.dq.util.ConnectionFailureClassifier
import org.slf4j.LoggerFactory

/**
 * 元数据回源失败降级本地 H2 缓存的通用编排(库/schema/表/字段/索引浏览与刷新路径共用)。
 *
 * - 回源成功 → 回调 `onSuccess` 让数据源连接状态自愈(此前被标记 ERROR 则恢复 OK)
 * - 回源抛连接级异常 → 回调 `onFailure` 写数据源标记;本地有缓存则降级返回缓存并置降级标志,
 *   无缓存可兜则原样抛出(前端仍看到错误)
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
     */
    fun <R> fetch(
        datasourceId: Long,
        hasCache: () -> Boolean,
        readCache: () -> R,
        fetch: () -> R,
    ): R {
        fellBack.set(false)
        return try {
            fetch().also { runCatching { onSuccess(datasourceId) }.onFailure { log.warn("恢复数据源 {} 连接状态失败(忽略)", datasourceId, it) } }
        } catch (e: Exception) {
            if (!ConnectionFailureClassifier.isConnectionFailure(e)) throw e
            record(datasourceId, e)
            if (!hasCache()) throw e
            fellBack.set(true)
            readCache()
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
