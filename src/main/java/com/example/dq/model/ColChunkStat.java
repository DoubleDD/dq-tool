package com.example.dq.model;

/** 单个分段内某列的统计结果 */
public record ColChunkStat(String column, long nullCount, long emptyCount, long ruleHitCount) {
}
