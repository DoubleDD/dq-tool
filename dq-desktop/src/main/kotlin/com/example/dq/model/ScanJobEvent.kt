package com.example.dq.model

import java.time.LocalDateTime

/**
 * 任务状态变更事件。
 *
 * @property status 事件发生后的任务状态(RUNNING 首次为"开始",后续为"继续")
 * @property at     事件发生时间
 */
data class ScanJobEvent(val status: ScanStatus?, val at: LocalDateTime?)
