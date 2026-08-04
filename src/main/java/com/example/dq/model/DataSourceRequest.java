package com.example.dq.model;

import jakarta.validation.constraints.NotBlank;

/** 数据源新增/编辑请求。password 为空表示不修改(编辑时)。 */
public record DataSourceRequest(
        @NotBlank String name,
        @NotBlank String jdbcUrl,
        String username,
        String password,
        Long rowThreshold,
        Long sizeThresholdBytes
) {
}
