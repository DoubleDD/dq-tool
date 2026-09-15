package com.example.dq.service

/**
 * 备份表判定(纯函数,全项目唯一口径):表名以 `_copy` / `_bak` / `_backup` / `_tmp` + 可选序号结尾即视为
 * 备份/临时表(不区分大小写,如 `t_order_copy`、`t_order_copy12`、`t_order_bak2`、`tmp_table` 不算)。
 *
 * 使用方:
 * - 扫描后系统联动直接打「备份表」标记(ChunkRunner → TagService.syncBackupTag);
 * - AI 自动打标对这类表直接跳过,不消耗大模型调用(AutoTagService);
 * - Word 报告「数据冗余分析」的备份表统计(WordReportService)。
 */
object BackupTableRule {

    /** 备份表后缀正则;与 Word 报告历史口径一致(`_copy/_bak/_backup/_tmp` + 可选序号结尾) */
    private val BACKUP_RE = Regex("_(copy|bak|backup|tmp)\\d*$", RegexOption.IGNORE_CASE)

    fun isBackupTable(tableName: String?): Boolean =
        !tableName.isNullOrBlank() && BACKUP_RE.containsMatchIn(tableName)
}
