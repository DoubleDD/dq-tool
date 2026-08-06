package com.example.dq.model;

/**
 * 分段范围。start 含,end 不含;null 表示无界。值已按分段键类型字符串化。
 *
 * @param nullChunk true 表示这是"分段键 IS NULL"的补充分段
 */
public record Range(String start, String end, boolean nullChunk) {

    /** 无分段键表的唯一分段(全表) */
    public static Range whole() {
        return new Range(null, null, false);
    }
}
