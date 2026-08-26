package com.example.dq.model

import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotNull

/**
 * 通用列表导出请求:前端把当前表格所见的表头与行数据(含过滤/合并后的展示口径)提交过来,
 * 后端只负责渲染 xlsx,不做业务查询。
 *
 * @property filename 导出文件名(不含扩展名,后端追加 .xlsx)
 * @property sheets   工作表列表,通常只有一个
 */
data class ListExportRequest(
    @field:NotBlank val filename: String?,
    @field:NotNull val sheets: List<ListExportSheet>?,
)

/**
 * 单个工作表。
 *
 * @property name    sheet 名(非法字符会被替换、截断到 31 字符)
 * @property headers 表头
 * @property rows    数据行,与表头等长;元素为展示字符串,空单元格传空串
 *                   (集合元素不建模为可空:Jackson 3 Kotlin 模块对嵌套集合内的 null 一律拒绝)
 */
data class ListExportSheet(
    val name: String,
    val headers: List<String>,
    val rows: List<List<String>>,
)
