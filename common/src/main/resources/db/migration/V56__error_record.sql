-- 错误收集系统(错误中心):统一采集前端 JS 错误 / 后端异常 / 数据库错误 / 任务与启动异常。
-- 同一错误按 fingerprint(来源+类型+归一消息+首栈帧的 SHA-256)聚合为一行,occurrences 记累计次数,
-- first_seen/last_seen 记首次与最近发生时间;detail/context 保留最近一次的完整堆栈与上下文。
-- status:OPEN 未处理 / RESOLVED 已处理 / IGNORED 已忽略(人工结论不随新发生次数改变)。
-- 保留策略由 ErrorCenterService 在启动就绪后执行(默认 30 天 + 单表上限),不在此脚本中处理。

CREATE TABLE IF NOT EXISTS error_record (
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    fingerprint VARCHAR(64)  NOT NULL,                  -- SHA-256(source|kind|归一消息|首栈帧),同错同类唯一
    source      VARCHAR(16)  NOT NULL,                  -- BACKEND/FRONTEND/DATABASE/TASK/STARTUP
    kind        VARCHAR(128) NOT NULL,                  -- 异常类名 / JS 错误类型 / API_ERROR 等
    level       VARCHAR(8)   NOT NULL,                  -- WARN/ERROR/FATAL
    message     VARCHAR(2000),                          -- 最近一次错误消息(超长截断)
    detail      CLOB,                                   -- 最近一次完整堆栈
    context     CLOB,                                   -- 最近一次上下文(前端路由/接口/浏览器或后端线程等)
    logger      VARCHAR(255),                           -- 后端 logger 名(前端为上报来源脚本名)
    thread      VARCHAR(160),                           -- 后端线程名(前端为 null)
    route       VARCHAR(512),                           -- 前端路由 / 接口路径 / 任务标识,便于定位
    occurrences INT NOT NULL DEFAULT 1,                 -- 累计发生次数
    first_seen  TIMESTAMP NOT NULL,                     -- 首次发生时间
    last_seen   TIMESTAMP NOT NULL,                     -- 最近发生时间
    app_version VARCHAR(32),                            -- 发生时软件版本
    status      VARCHAR(16) NOT NULL DEFAULT 'OPEN',    -- OPEN/RESOLVED/IGNORED
    note        VARCHAR(2000)                           -- 处理备注(人工填写)
);

CREATE UNIQUE INDEX IF NOT EXISTS uk_error_record_fp ON error_record(fingerprint);
CREATE INDEX IF NOT EXISTS idx_error_record_last ON error_record(last_seen);
CREATE INDEX IF NOT EXISTS idx_error_record_src ON error_record(source, level, status);
