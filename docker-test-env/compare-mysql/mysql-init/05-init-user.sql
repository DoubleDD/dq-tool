-- 强制客户端按 utf8mb4 解释本文件,避免镜像 locale 非 UTF-8 时中文被双重编码
SET NAMES utf8mb4;

-- =============================================================================
-- 应用连接账号:给 dq-tool 用的专用账号(避免直接暴露 root)
--   * 使用 mysql_native_password:MySQL Connector/J 9.x 默认不允许明文连接下
--     回退取 RSA 公钥(caching_sha2_password 首次认证会报 Public Key Retrieval is not allowed),
--     测试环境用 native 插件最省事;生产环境请改用 caching_sha2 + SSL。
-- =============================================================================

CREATE USER IF NOT EXISTS 'dq'@'%' IDENTIFIED WITH mysql_native_password BY 'dq123456';

GRANT ALL PRIVILEGES ON reservoir_base.*   TO 'dq'@'%';
GRANT ALL PRIVILEGES ON reservoir_vendor.* TO 'dq'@'%';
-- 表体积/行数统计可能用到 PROCESS,测试环境一并放开
GRANT PROCESS ON *.* TO 'dq'@'%';

FLUSH PRIVILEGES;
