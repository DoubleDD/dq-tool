-- AI 调用用量流水(独立库 dqaiusage):每次大模型调用成功记一行,含请求/响应内容供 prompt 调优回溯
-- 2026-08 起从主库 dqconfig 拆出(调用量大);老数据由启动时一次性搬迁(AiUsageRepository.migrateLegacyIfEmpty)
CREATE TABLE IF NOT EXISTS ai_usage_log (
    id               BIGINT AUTO_INCREMENT PRIMARY KEY,
    scene            VARCHAR(32)  NOT NULL,          -- TABLE_DOC / AUTO_TAG / WORD_REPORT / TEST
    model            VARCHAR(128) NOT NULL,          -- 调用时实际使用的模型名
    prompt_tokens    BIGINT       NOT NULL DEFAULT 0,
    completion_tokens BIGINT      NOT NULL DEFAULT 0,
    total_tokens     BIGINT       NOT NULL DEFAULT 0,
    cost             DECIMAL(14,6) NOT NULL DEFAULT 0,  -- 按当时价格配置算出的费用(元)
    prompt_cost      DECIMAL(14,6),                   -- 输入分项费用(老数据 NULL=无分项)
    completion_cost  DECIMAL(14,6),                   -- 输出分项费用
    period           VARCHAR(8)   NOT NULL DEFAULT 'VALLEY',  -- PEAK(峰时段)/ VALLEY(低谷时段)/ FLAT(峰谷计价关闭)
    scan_job_id      BIGINT,                          -- 扫描触发的调用关联的扫描任务 id,其余为 NULL
    scan_label       VARCHAR(512),                    -- 记录时快照:数据源名 库/schema(任务/数据源被删不影响统计)
    scan_created_at  TIMESTAMP,                       -- 记录时快照:扫描任务创建时间(按扫描维度排序)
    request_content  CLOB,                            -- 请求内容([system] + [user],超长截断)
    response_content CLOB,                            -- 模型返回正文(超长截断)
    created_at       TIMESTAMP    DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_ai_usage_created ON ai_usage_log(created_at);
CREATE INDEX IF NOT EXISTS idx_ai_usage_scan_job ON ai_usage_log(scan_job_id);
