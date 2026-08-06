package com.example.dq.model;

import jakarta.validation.constraints.NotNull;

import java.util.List;

/**
 * 发起扫描请求。
 *
 * @param database          目标数据库;仅支持多库选择的方言(如 SQL Server)使用,其他方言为 null
 * @param tables            选中的表;null 或空表示全库
 * @param forceFull         超阈值表也强制全量统计
 * @param nullRules         自定义空值规则
 * @param maxTableSizeBytes 表大小上限(字节);只扫描不超过该大小的表,null 表示不限制
 */
public record ScanRequest(
        @NotNull Long datasourceId,
        @NotNull String schema,
        String database,
        List<String> tables,
        boolean forceFull,
        List<NullRule> nullRules,
        Long maxTableSizeBytes
) {
}
