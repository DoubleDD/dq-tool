-- 元数据浏览/刷新回源失败降级本地缓存:给数据源连接状态补「错误分类」列
-- conn_status=ERROR 时区分网络不可达(UNREACHABLE)/认证失败(AUTH)/其他(OTHER),供前端给出准确提示;
-- 回源成功或编辑连接信息后随 conn_status 一并清空
ALTER TABLE data_source ADD COLUMN IF NOT EXISTS conn_kind VARCHAR(32);
