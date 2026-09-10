package com.example.dq.model

import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotEmpty
import jakarta.validation.constraints.NotNull

/**
 * 对象管理(数据目录):目录树节点视图。
 * 目录按数据源隔离;根是虚拟节点(id=0,name 空串,tables 恒空),真实顶层目录挂在 children 下。
 * children 按 name 排序,tables 按 tableName 排序。
 */
data class ObjectDirNode(
    val id: Long,
    val datasourceId: Long,
    val parentId: Long,
    val name: String,
    val children: List<ObjectDirNode> = emptyList(),
    val tables: List<ObjectTableView> = emptyList(),
)

/**
 * 挂载到目录下的一张表(四元组定位,dbName 空串=无库概念方言);
 * comment 从 meta_table 缓存实时补齐(缓存未覆盖给空串),remark 为挂载备注;
 * relKind 为挂载表与目录的关系类型(INCLUDE=包含 / ASSOC=关联,null=未指定),在目录图连线上展示
 */
data class ObjectTableView(
    val id: Long,
    val dirId: Long,
    val dbName: String,
    val schemaName: String,
    val tableName: String,
    val comment: String,
    val remark: String?,
    val relKind: String? = null,
    val relations: List<ObjectTableRelView> = emptyList(),
)

/** 挂载表登记的一张关系表;comment 同样从 meta_table 补齐,remark 为关系备注(如关联字段/业务含义);
 * relKind 为关系表与挂载表的关系类型(INCLUDE=包含 / ASSOC=关联,null=未指定),在目录图连线上展示 */
data class ObjectTableRelView(
    val id: Long,
    val dbName: String,
    val schemaName: String,
    val tableName: String,
    val comment: String,
    val remark: String?,
    val relKind: String? = null,
)

/** 新建目录请求;parentId 可空,null=根(等价 0,即挂在虚拟根下的顶层目录) */
data class ObjectDirCreateRequest(
    @field:NotNull val datasourceId: Long?,
    val parentId: Long?,
    @field:NotBlank val name: String?,
)

/** 重命名目录请求 */
data class ObjectDirRenameRequest(
    @field:NotBlank val name: String?,
)

/** 挂载表单项:relKind 可空,INCLUDE=包含 / ASSOC=关联(非法值由内核 400) */
data class ObjectTableMountItem(
    @field:NotBlank val tableName: String?,
    val relKind: String?,
)

/** 批量挂载表到目录请求(同一 db/schema 下多张表,逐表可选关系类型);dbName 可空,内核归一为空串(与 manual_collect 口径一致) */
data class ObjectTableMountRequest(
    val dbName: String?,
    @field:NotBlank val schemaName: String?,
    @field:Valid @field:NotEmpty val items: List<ObjectTableMountItem>?,
    val remark: String?,
)

/** 批量登记关系表请求;字段口径同挂载请求(items 逐表可选关系类型 relKind) */
data class ObjectTableRelRequest(
    val dbName: String?,
    @field:NotBlank val schemaName: String?,
    @field:Valid @field:NotEmpty val items: List<ObjectTableMountItem>?,
    val remark: String?,
)

/** 挂载/登记关系结果:existing=true 表示命中唯一键的已存在记录(幂等返回原 id,未新插) */
data class ObjectMountResult(val id: Long, val existing: Boolean)

/** 批量挂载/登记结果:mounted 新插条数,existing 命中唯一键跳过的条数 */
data class ObjectBatchResult(val mounted: Int, val existing: Int)

/** 删除目录的级联统计:删除的目录数/挂载表数/关系表数 */
data class ObjectDirDeleteResult(val dirs: Int, val tables: Int, val rels: Int)
