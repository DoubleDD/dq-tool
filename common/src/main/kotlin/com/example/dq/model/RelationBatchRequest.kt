package com.example.dq.model

import jakarta.validation.constraints.NotEmpty

/** 批量操作关系的入参(批量确认/否决/删除共用);非空由 @NotEmpty 校验,数量上限与去重在内核兜底 */
data class RelationBatchRequest(@field:NotEmpty val ids: List<Long>?)
