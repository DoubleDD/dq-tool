-- 授权码管理(仅管理员实例=配置了签发私钥):每条签发的授权码留档,可查看/删除,不可编辑
-- 删除仅删除留档记录,离线验签方案无法吊销已分发的授权码

CREATE TABLE IF NOT EXISTS license_record (
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    app_version VARCHAR(64)  NOT NULL,          -- 授权码绑定的软件版本号(payload 版本段)
    customer    VARCHAR(256) NOT NULL,
    expires_at  DATE,                           -- 到期日(当天仍有效);NULL=永久授权
    server_url  VARCHAR(1024),                  -- 敏感信息,仅管理员实例可见,不下发用户实例状态接口
    username    VARCHAR(256),
    sid         VARCHAR(256),
    issued_at   BIGINT       NOT NULL,          -- 签发时间戳(epoch 毫秒,与 payload 内一致)
    code_enc    VARCHAR(4096) NOT NULL,         -- AES-GCM 加密后的完整授权码
    created_at  TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);
