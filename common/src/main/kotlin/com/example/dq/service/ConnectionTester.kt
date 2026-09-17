package com.example.dq.service

import com.example.dq.model.TestConnectionRequest
import java.util.concurrent.Callable
import java.util.concurrent.ExecutorCompletionService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * 数据源并发实测(共享):抽样导出(SampleExportService)与比对批量导入(CompareImportService)共用。
 * 4 线程池 + 单条 [TIMEOUT_SECONDS] 超时(超时的单条记「连接超时」算失败,不拖住整批)。
 */
internal object ConnectionTester {

    /** 单数据源实测超时(比诊断实测的 40s 短:批量导入场景单个卡住不应拖住整批) */
    const val TIMEOUT_SECONDS = 20L

    private val THREAD_IDX = AtomicInteger()

    /**
     * 并发实测一组连接请求(key → 错误消息,null 值表示连通;未测的 key 无此项)。
     * 生命周期回调:[onStart] 在任务**真正进线程开始执行**时触发(还在池队列里等的不触发),
     * [onEach] 在单个完成时按完成先后触发——调用方据此把报告行从「排队中」翻「校验中」再翻最终结果。
     * 回调在多线程上触发(各自的 key 互不相干),全部完成后返回汇总 map;
     * 回调里抛出的异常(如暂停中的任务被取消)会上抛给调用方。
     * [test] 为单条实测(null=连通),由调用方提供(一般是 DataSourceService.testConnection 的包装)
     */
    fun testAll(
        toTest: Map<String, TestConnectionRequest>,
        test: (TestConnectionRequest) -> String?,
        onStart: ((String) -> Unit)? = null,
        onEach: ((String, String?) -> Unit)? = null,
    ): Map<String, String?> {
        val errors = LinkedHashMap<String, String?>()
        if (toTest.isEmpty()) return errors
        val pool = Executors.newFixedThreadPool(minOf(4, toTest.size)) { r ->
            Thread(r, "ds-test-" + THREAD_IDX.incrementAndGet()).apply { isDaemon = true }
        }
        try {
            val completion = ExecutorCompletionService<Pair<String, String?>>(pool)
            val futures = LinkedHashMap<String, Future<Pair<String, String?>>>()
            val pending = LinkedHashMap<String, Long>() // key → 提交时间(nanoTime),超时尚未完成的记超时
            for ((key, req) in toTest) {
                futures[key] = completion.submit(Callable {
                    onStart?.invoke(key)
                    key to test(req)
                })
                pending[key] = System.nanoTime()
            }
            val timeoutNanos = TimeUnit.SECONDS.toNanos(TIMEOUT_SECONDS)
            while (pending.isNotEmpty()) {
                val done = completion.poll(200, TimeUnit.MILLISECONDS)
                if (done != null) {
                    val (key, err) = done.get()
                    // 已按超时登记过的任务(被取消)可能随后出现在完成队列,跳过不重复处理
                    if (pending.remove(key) != null) {
                        errors[key] = err
                        onEach?.invoke(key, err)
                    }
                    continue
                }
                // 暂无新完成:把超过单条超时的任务记「连接超时」并取消(其线程由 shutdownNow 回收)
                val now = System.nanoTime()
                for (key in pending.filterValues { now - it > timeoutNanos }.keys.toList()) {
                    pending.remove(key)
                    futures.getValue(key).cancel(true)
                    val msg = "连接超时(${TIMEOUT_SECONDS} 秒)"
                    errors[key] = msg
                    onEach?.invoke(key, msg)
                }
            }
        } finally {
            pool.shutdownNow()
        }
        return errors
    }
}
