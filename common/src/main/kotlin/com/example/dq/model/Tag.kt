package com.example.dq.model

/** 标记类型:USER 用户标记 / EMPTY 系统空表标记(扫描自动驱动,用户不可编辑) */
enum class TagKind { USER, EMPTY }

/**
 * 标记类型(用途),code 落库 tag_def.tag_type:
 * SYSTEM(0) 系统标记(空表,kind=EMPTY)/ AI(1) 可用于 AI 打标(自动打标候选)/ MANUAL(2) 仅用于人工打标
 */
enum class TagType(val code: Int) {
    SYSTEM(0), AI(1), MANUAL(2);

    companion object {

        /** 按落库 code 解析;未知 code 兜底 AI(与旧默认一致) */
        fun fromCode(code: Int): TagType = entries.firstOrNull { it.code == code } ?: AI
    }
}

/** 表级打标关系的来源(table_tag.source):MANUAL 人工打标 / AI 自动打标 / SYSTEM 系统联动(空表标记) */
object TagSource {
    const val MANUAL = "MANUAL"
    const val AI = "AI"
    const val SYSTEM = "SYSTEM"
}

/** 批量打标结果:added 新增打标关系数 / skipped 已存在跳过数 */
data class BatchTagResult(val added: Int, val skipped: Int)

/** 表标记定义(全局共享);tableCount 为打该标记的表数(多标记重复计入);source 为关系级打标来源(仅按表查询打标关系时填充) */
data class Tag(
    val id: Long,
    val name: String,
    val color: String,
    val kind: TagKind,
    /** 标记含义说明(可空);AI 自动打标时随候选清单一并发给大模型 */
    val description: String? = null,
    /** 标记类型(用途);缺省 AI 与升级前行为一致(全部 USER 标记都是 AI 候选),系统空表标记为 SYSTEM */
    val tagType: TagType = TagType.AI,
    val tableCount: Long = 0,
    /** 打标来源(TagSource.*,关系级);仅 tableTagsBySchema 按表查询时填充,其余场景为 null */
    val source: String? = null,
)
