package com.example.dq.model;

import java.time.LocalDateTime;

/** 表级扫描状态视图 */
public record ScanTableView(
        long id,
        long jobId,
        String tableName,
        ScanStatus status,
        boolean sampled,
        Long sampleRows,
        Long estRows,
        Long sizeBytes,
        String comment,     // 表注释
        String storageInfo, // 引擎或表空间
        String chunkKey,
        int totalChunks,
        int doneChunks,
        long scannedRows,
        Long totalRows,
        String error,
        LocalDateTime startedAt,
        LocalDateTime finishedAt
) {

    /** 表级进度 0-100;无分段表运行中返回 null */
    public Double progressPercent() {
        if (status == ScanStatus.DONE) return 100.0;
        if (totalChunks > 0) return doneChunks * 100.0 / totalChunks;
        return null;
    }
}
