package com.example.dq.model

/** 表索引结构元数据(结构明细展示用,不含扫描统计) */
data class IndexMeta(
    val name: String,
    val unique: Boolean,
    val columns: List<String>  // 按 ORDINAL_POSITION 排序的索引列;表达式/函数索引列名为空时该列不展示
)
