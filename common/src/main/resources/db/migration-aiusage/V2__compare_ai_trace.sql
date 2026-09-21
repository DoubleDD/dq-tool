-- 数据比对 AI 判定留痕(独立库 dqaiusage):比对阶段每一次大模型调用(补配/同名消歧/映射推导/时间列/佐证字段)
-- 记一行,含完整 prompt、模型原始回答与逐条结构化判定结果(result_json),前端按任务 → 目标查询。
-- 任务/目标 id 为快照(不跨库 join),target_label 记录时快照,任务被删后留痕仍可看;删除比对任务时由服务层级联清理。
CREATE TABLE IF NOT EXISTS compare_ai_trace (
    id               BIGINT AUTO_INCREMENT PRIMARY KEY,
    job_id           BIGINT       NOT NULL,        -- 比对任务 id(快照,不跨库 join)
    target_id        BIGINT,                       -- 比对目标 id(任务级调用为 NULL)
    target_label     VARCHAR(512),                 -- 记录时快照:库.模式.表(展示用,任务/目标被删也能看)
    scene            VARCHAR(32)  NOT NULL,        -- COMPARE_MATCH / COMPARE_MAPPING / COMPARE_TIME / COMPARE_EVIDENCE
    stage            VARCHAR(32)  NOT NULL,        -- RESIDUE 补配 / SAME_NAME 消歧 / MAPPING 映射 / TIME 时间列 / EVIDENCE 佐证
    batch_no         INT          NOT NULL,        -- 第几批(从 1 起;单调用场景恒 1)
    model            VARCHAR(128),                 -- 调用时使用的模型名
    request_content  CLOB,                         -- 完整 prompt([system] + [user],截断 5 万字符,同 ai_usage_log 口径)
    response_content CLOB,                         -- 模型原始回答(同截断;失败批次记错误摘要)
    result_json      CLOB,                         -- 结构化逐条判定结果(按 stage 分结构;失败批次为 NULL)
    created_at       TIMESTAMP    DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_compare_ai_trace_job ON compare_ai_trace(job_id, target_id);
