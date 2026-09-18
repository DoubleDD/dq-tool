package com.example.dq.repository

import org.slf4j.LoggerFactory
import java.util.concurrent.ExecutionException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit

/**
 * 元数据缓存统一写队列(全局单线程 FIFO)。
 *
 * 背景:结构缓存(meta_database / meta_table / meta_column / meta_index / meta_schema_column /
 * meta_ddl / meta_column_count)与库概览(schema_stat)的覆盖刷新都是「先 DELETE 后 INSERT」;
 * 扫描场景下多个 worker 会同时刷不同粒度(多表 planTable、扫描引导刷表清单、导出/推导刷字段等),
 * 直接并发写 H2 会互相等锁、甚至撞唯一键(23505)。原先按粒度分条纹锁只能挡住「同粒度」并发,
 * 挡不住「多 worker × 多粒度」。
 *
 * 这里收敛为**唯一写者**:所有元数据缓存写操作都提交到同一个工作线程串行执行。提交方式两种:
 * [submit] 阻塞等待结果(浏览/导出等读路径,保持「提交后立即可读」的同步语义与异常传播);
 * [submitAsync] 火忘提交(扫描路径,worker 不排队等写库,失败只记日志不影响扫描)。
 * 两种入口共用同一线程,FIFO 次序一致,因此对 H2 元数据表彻底禁止并发写。
 *
 * 范围:只覆盖「元数据缓存」写入。扫描结果(scan_job/scan_table/scan_chunk/scan_column)、
 * 任务表、错误中心等其他表不在其列。
 */
class MetaWriteQueue {

    private val log = LoggerFactory.getLogger(MetaWriteQueue::class.java)

    private val executor: ExecutorService = Executors.newSingleThreadExecutor { r ->
        Thread(r, "meta-cache-writer").apply { isDaemon = true }
    }

    /**
     * 提交写任务并阻塞等待结果。任务异常按原类型回抛(RuntimeException/Error 原样,
     * 受检异常包成 IllegalStateException),不把 ExecutionException 泄漏给调用方。
     */
    fun <T> submit(task: () -> T): T =
        try {
            executor.submit(task).get()
        } catch (e: ExecutionException) {
            when (val cause = e.cause) {
                is RuntimeException -> throw cause
                is Error -> throw cause
                null -> throw IllegalStateException("元数据缓存写入失败", e)
                else -> throw IllegalStateException("元数据缓存写入失败: " + cause.message, cause)
            }
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            throw IllegalStateException("元数据缓存写入被中断", e)
        }

    /**
     * 火忘提交(扫描路径用):不阻塞调用方,异常只记 warn 日志。
     * 与 [submit] 共用同一工作线程,FIFO 次序不变(先提交的任务一定先执行)。
     */
    fun submitAsync(task: () -> Unit) {
        try {
            executor.submit {
                try {
                    task()
                } catch (e: Exception) {
                    log.warn("元数据缓存异步写入失败(忽略): {}", e.message)
                }
            }
        } catch (e: RejectedExecutionException) {
            log.warn("元数据缓存写队列已关闭,丢弃异步写入(忽略): {}", e.message)
        }
    }

    /** 应用关闭:停止接收新任务并等待在途写入结束(最多 5s,超时只记日志) */
    fun shutdown() {
        executor.shutdown()
        if (!runCatching { executor.awaitTermination(5, TimeUnit.SECONDS) }.getOrDefault(false)) {
            log.warn("元数据缓存写队列未在 5s 内结束,可能有未完成的写入")
        }
    }
}
