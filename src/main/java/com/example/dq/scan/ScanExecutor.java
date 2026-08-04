package com.example.dq.scan;

import com.example.dq.config.DqProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.sql.Statement;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/** 全局扫描线程池:调度单元为分段(ChunkTask) */
@Component
public class ScanExecutor {

    private static final Logger log = LoggerFactory.getLogger(ScanExecutor.class);

    private final ThreadPoolExecutor executor;
    /** chunkId -> 正在执行的 Statement,用于取消 */
    private final Map<Long, Statement> activeStatements = new ConcurrentHashMap<>();

    public ScanExecutor(DqProperties props) {
        int workers = props.getScan().getWorkers();
        this.executor = new ThreadPoolExecutor(
                workers, workers, 60L, TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(),
                r -> {
                    Thread t = new Thread(r, "dq-scan-worker");
                    t.setDaemon(true);
                    return t;
                });
    }

    public void submit(Runnable task) {
        executor.submit(() -> {
            try {
                task.run();
            } catch (Throwable t) {
                log.error("扫描任务执行异常", t);
            }
        });
    }

    public void registerStatement(long chunkId, Statement stmt) {
        activeStatements.put(chunkId, stmt);
    }

    public void unregisterStatement(long chunkId) {
        activeStatements.remove(chunkId);
    }

    /** 取消指定分段正在执行的 SQL */
    public void cancelStatement(long chunkId) {
        Statement stmt = activeStatements.get(chunkId);
        if (stmt != null) {
            try {
                stmt.cancel();
            } catch (Exception e) {
                log.warn("取消 Statement 失败 chunkId={}: {}", chunkId, e.getMessage());
            }
        }
    }
}
