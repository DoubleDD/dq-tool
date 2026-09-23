-- 批量 AI 打标后台任务(表列表批量打标弹窗 AI 页签):提交后立即返回任务 id,后台固定 2 线程池逐表执行,
-- 前端经后台任务中心(/ai-tag-batch/active 1s 轮询)跟踪进度,终态汇总计数落行供完成通知展示

CREATE TABLE IF NOT EXISTS ai_tag_batch_task (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    datasource_id   BIGINT NOT NULL,
    db_name         VARCHAR(256) NOT NULL DEFAULT '',   -- 空串兜底口径与 table_tag 一致
    schema_name     VARCHAR(256) NOT NULL,
    table_count     INT NOT NULL,                       -- 打标表数(任务定位展示)
    status          VARCHAR(16) NOT NULL DEFAULT 'PENDING', -- PENDING/RUNNING/DONE/FAILED
    stage           VARCHAR(256),                       -- 当前正在打标的表名(逐表推进)
    progress_done   INT NOT NULL DEFAULT 0,
    progress_total  INT NOT NULL DEFAULT 0,
    tagged_count    INT,                                -- 终态汇总:成功打标
    unmatched_count INT,                                -- 终态汇总:模型未匹配(NONE/幻觉标记)
    skipped_count   INT,                                -- 终态汇总:跳过(备份表/已有同标)+ 单表失败
    error           VARCHAR(2048),
    created_at      TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    started_at      TIMESTAMP,
    finished_at     TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_ai_tag_batch_task_ds ON ai_tag_batch_task(datasource_id, id);
