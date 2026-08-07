package com.example.dq.model

/** 单个分段内某列的统计结果 */
data class ColChunkStat(val column: String, val nullCount: Long, val emptyCount: Long, val ruleHitCount: Long)
