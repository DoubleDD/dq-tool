-- 导出中心(V66):同步类导出(请求即生成、响应完即消失)统一登记元数据,供「导出中心」页查询/重新导出/删除;
-- 纯增量,不碰导出文件本身(向后兼容铁律);落盘类导出(报告 Word/抽样 zip/比对 xlsx)不双写,
-- 查询层由 repository 把 report_export/sample_export/compare_job.export_* UNION 成统一模型。
CREATE TABLE IF NOT EXISTS export_record (
  id           BIGINT AUTO_INCREMENT PRIMARY KEY,
  kind         VARCHAR(32)  NOT NULL,  -- 导出类别,见 ExportKind 枚举
  title        VARCHAR(512) NOT NULL,  -- 语义化描述(数据源/表/任务),前端展示
  file_name    VARCHAR(512) NOT NULL,  -- Content-Disposition 文件名(解码后)
  file_size    BIGINT,                -- 同步流式导出未知大小为 NULL;list-exports 内存数组有大小
  params_json  VARCHAR(2048),         -- 重新导出所需 API 路径等,{"path":"/api/scans/12/export"}
  status       VARCHAR(16)  NOT NULL DEFAULT 'SUCCESS',
  error        VARCHAR(1024),
  created_at   TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_export_record_created ON export_record(created_at DESC, id DESC);
