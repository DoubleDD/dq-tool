package com.example.dq.model;

/** 任务/表/分段状态。INTERRUPTED 仅用于任务。 */
public enum ScanStatus {
    PENDING,
    RUNNING,
    DONE,
    FAILED,
    CANCELED,
    INTERRUPTED
}
