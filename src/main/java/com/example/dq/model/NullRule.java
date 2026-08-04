package com.example.dq.model;

import java.util.List;

/**
 * 自定义空值规则:某列(或 * 表示所有列)的额外"视为空"取值。
 */
public record NullRule(String column, List<String> values) {

    public boolean matches(String columnName) {
        return "*".equals(column) || column != null && column.equalsIgnoreCase(columnName);
    }
}
