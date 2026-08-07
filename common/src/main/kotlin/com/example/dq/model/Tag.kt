package com.example.dq.model

/** 标记类型:USER 用户标记 / EMPTY 系统空表标记(扫描自动驱动,用户不可编辑) */
enum class TagKind { USER, EMPTY }

/** 表标记定义(全局共享);tableCount 为打该标记的表数(多标记重复计入) */
data class Tag(
    val id: Long,
    val name: String,
    val color: String,
    val kind: TagKind,
    val tableCount: Long = 0,
)
