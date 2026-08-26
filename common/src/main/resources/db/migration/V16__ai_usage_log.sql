-- AI 调用 Token/费用统计:每次大模型调用成功后记一行(场景/模型/输入输出 token/费用/峰谷时段)
CREATE TABLE IF NOT EXISTS ai_usage_log (
    id               BIGINT AUTO_INCREMENT PRIMARY KEY,
    scene            VARCHAR(32)  NOT NULL,          -- TABLE_DOC / AUTO_TAG / WORD_REPORT / TEST
    model            VARCHAR(128) NOT NULL,          -- 调用时实际使用的模型名
    prompt_tokens    BIGINT       NOT NULL DEFAULT 0,
    completion_tokens BIGINT      NOT NULL DEFAULT 0,
    total_tokens     BIGINT       NOT NULL DEFAULT 0,
    cost             DECIMAL(14,6) NOT NULL DEFAULT 0,  -- 按当时价格配置算出的费用(元)
    period           VARCHAR(8)   NOT NULL DEFAULT 'VALLEY',  -- PEAK(峰时段)/ VALLEY(低谷时段)
    created_at       TIMESTAMP    DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_ai_usage_created ON ai_usage_log(created_at);
