-- 导出中心:记录文件 SHA-256 checksum(同步类 landed 实测、落盘类 finalize 计算/带入),前端文件列副行展示。
ALTER TABLE export_record ADD COLUMN IF NOT EXISTS checksum VARCHAR(64);
