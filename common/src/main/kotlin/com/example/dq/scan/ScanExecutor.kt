package com.example.dq.scan

import com.example.dq.config.AppConfig
import org.slf4j.LoggerFactory
import java.sql.Statement
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/** 全局扫描线程池:调度单元为分段(ChunkTask) */
class ScanExecutor(config: AppConfig) {

    private val executor: ThreadPoolExecutor
    /** chunkId -> 正在执行的 Statement,用于取消 */
    private val activeStatements = ConcurrentHashMap<Long, Statement>()

    init {
        val workers = config.scan.workers
        executor = ThreadPoolExecutor(
            workers, workers, 60L, TimeUnit.SECONDS,
            LinkedBlockingQueue()
        ) { r ->
            val t = Thread(r, "dq-scan-worker")
            t.isDaemon = true
            t
        }
    }

    fun submit(task: Runnable) {
        executor.submit {
            try {
                task.run()
            } catch (t: Throwable) {
                log.error("扫描任务执行异常", t)
            }
        }
    }

    /** 动态调整线程池大小;新值小于当前值时先缩 max 再缩 core,避免 IllegalArgumentException */
    fun resize(workers: Int) {
        if (workers <= 0) return
        synchronized(executor) {
            if (workers < executor.maximumPoolSize) {
                executor.corePoolSize = workers
                executor.maximumPoolSize = workers
            } else if (workers > executor.maximumPoolSize) {
                executor.maximumPoolSize = workers
                executor.corePoolSize = workers
            }
        }
    }
    fun registerStatement(chunkId: Long, stmt: Statement) {
        activeStatements[chunkId] = stmt
    }

    fun unregisterStatement(chunkId: Long) {
        activeStatements.remove(chunkId)
    }

    /** 取消指定分段正在执行的 SQL */
    fun cancelStatement(chunkId: Long) {
        val stmt = activeStatements[chunkId]
        if (stmt != null) {
            try {
                stmt.cancel()
            } catch (e: Exception) {
                log.warn("取消 Statement 失败 chunkId={}: {}", chunkId, e.message)
            }
        }
    }

    companion object {
        private val log = LoggerFactory.getLogger(ScanExecutor::class.java)
    }
}
