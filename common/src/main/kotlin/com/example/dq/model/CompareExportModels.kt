package com.example.dq.model

/**
 * 数据比对导出(总览表)行:一个比对目标(首个系统为基准表)对应总览 sheet 的一行。
 * 字段与截图口径一致:表中文名 / 表英文名称 / 所属系统 / 条数 / 数据最新更新时间 /
 * 与基准差 / 匹配编码数 / 匹配对象数 / 差异条数 / 差异原因。
 */
data class CompareExportOverviewRow(
    /** 表英文名称:基准/目标表名 */
    val tableName: String,
    /** 表中文名:表注释(取不到留空) */
    val tableComment: String?,
    /** 所属系统:table_system 登记的系统名,未登记回落数据源名 */
    val systemName: String?,
    /** 条数:该侧表实际行数(目标侧含多余行;未采集为 null) */
    val rowCount: Int?,
    /** 数据最新更新时间:比对执行时探测时间字段(update 类优先)取 MAX 的快照;无可用字段/取数失败留空 */
    val dataUpdatedAt: String?,
    /** 与基准差:目标行数 − 基准行数(基准行本身留空) */
    val diffFromBase: Int?,
    /** 匹配编码数:编码路(第一路)命中的对象数;老任务三路未采集(NULL)时按 matchedCount 解读(旧口径兼容) */
    val matchedCount: Int?,
    /** 匹配对象数:双侧都存在的对象总数(SAME+DIFF,编码/名称/大模型三路之和) */
    val matchedTotal: Int?,
    /** 差异条数:数量差异 = 缺失(基准有目标无)+ 多余(目标有基准无)的对象数;
     * 总览只看行级数量对比,属性差异(编码/名称/字段值不一致)不计,在各明细 sheet 体现 */
    val diffCount: Int?,
    /** 差异原因:按差异构成自动生成,可导出后人工补充 */
    val diffReason: String?,
    /** 该行对应的比对目标 id;基准行为 null(明细 sheet 按此取数) */
    val targetId: Long?,
)

/**
 * 数据比对导出(明细 sheet)表头信息:每个差异行一个 sheet,sheet 头部先给上下文再列字段级差异
 */
data class CompareExportDiffSheet(
    /** sheet 开头展示:数据源/所属系统 */
    val systemName: String?,
    val datasourceId: Long,
    val dbName: String,
    val schemaName: String?,
    /** 目标表名 */
    val tableName: String,
    /** 基准表名 */
    val baseTable: String,
    /** 比对主键 */
    val keyField: String,
    /** 条数描述,如「目标 743 行 / 基准 742 行」 */
    val countDesc: String,
    /** 差异原因(与总览同一口径) */
    val diffReason: String?,
    /** 差异行:一个差异对象一行,DIFF 行逐字段展开 */
    val rows: List<CompareExportDiffRow>,
)

/** 明细 sheet 的差异行:DIFF 行逐字段展开,MISSING/EXTRA 整行一条(字段/基准值/目标值留空) */
data class CompareExportDiffRow(
    val objectKey: String?,
    val objectName: String?,
    /** 差异类型中文:缺失/多余/不一致 */
    val typeLabel: String,
    val field: String?,
    val baseValue: String?,
    val targetValue: String?,
)
