package com.example.dq.model

/**
 * 库级字段清单条目(SQL 控制台智能提示用,一次查整个 schema,避免逐表请求)。
 *
 * @property table   所属表名
 * @property name    字段名
 * @property type    展示类型(如 varchar(50)、decimal(10,2))
 * @property comment 字段备注(数据库 COMMENT,可能为空串;Oracle 驱动默认不返回 REMARKS,与表结构页同口径)
 */
data class SchemaColumn(val table: String, val name: String, val type: String, val comment: String = "")
