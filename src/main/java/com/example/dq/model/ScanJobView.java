package com.example.dq.model;

import java.time.LocalDateTime;
import java.util.List;

/** 任务进度视图 */
public record ScanJobView(
        long id,
        long datasourceId,
        String datasourceName,
        DbType dbType,
        String dbName,
        String schemaName,
        ScanStatus status,
        boolean forceFull,
        List<NullRule> nullRules,
        int totalTables,
        int doneTables,
        double progressPercent,
        String error,
        LocalDateTime createdAt,
        LocalDateTime startedAt,
        LocalDateTime finishedAt,
        List<ScanTableView> tables      // 仅详情接口填充
) {
}
