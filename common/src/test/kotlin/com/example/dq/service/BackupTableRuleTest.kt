package com.example.dq.service

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** 备份表判定纯函数:与 Word 报告、扫描系统联动、AI 打标跳过共用同一份口径 */
class BackupTableRuleTest {

    @Test
    fun `备份后缀命中并支持序号与大小写`() {
        for (name in listOf(
            "t_order_copy", "t_order_copy1", "t_order_copy12",
            "t_order_bak", "t_order_bak2",
            "t_order_backup", "t_order_backup3",
            "t_order_tmp", "t_order_tmp9",
            "T_ORDER_COPY", "T_Order_Bak",
            "订单表_copy",
        )) {
            assertTrue(BackupTableRule.isBackupTable(name), "应判为备份表:$name")
        }
    }

    @Test
    fun `非备份表名不命中`() {
        for (name in listOf(
            "t_order", "copy_order", "bak_order", "t_backup_log", "tmp_order",
            "t_order_copy_log", "t_orderholder", "t_copycat", null, "", "   ",
        )) {
            assertFalse(BackupTableRule.isBackupTable(name), "不应判为备份表:$name")
        }
    }
}
