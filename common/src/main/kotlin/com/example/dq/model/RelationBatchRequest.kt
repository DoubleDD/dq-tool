package com.example.dq.model

import jakarta.validation.constraints.NotEmpty

/**
 * 批量操作关系的入参(批量确认/否决共用);非空由 @NotEmpty 校验,数量上限与去重在内核兜底。
 *
 * @property ids     关系 id 列表(单次上限由内核校验)
 * @property remarks 逐条备注(可选):键为关系 id 的字符串形态,值为人工填写的否决原因;
 *                   只有本次真正修改过的行才带上(缺省的行保留原备注),随人工审核一并落库
 */
data class RelationBatchRequest(
    @field:NotEmpty val ids: List<Long>?,
    val remarks: Map<String, String>? = null,
)
