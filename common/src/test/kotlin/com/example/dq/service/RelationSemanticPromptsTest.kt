package com.example.dq.service

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** 语义匹配 prompt 组装与响应解析(纯函数,不依赖大模型接口) */
class RelationSemanticPromptsTest {

    // ---------- 阶段一:表级粗筛 prompt ----------

    @Test
    fun `表级prompt 原料回退 有描述用描述 无描述用注释 皆无不进prompt`() {
        val prompt = RelationSemanticPrompts.buildTableScreenPrompt(
            "reservoir", "水库信息表", "水库基础档案,记录每座水库的编码与规模", listOf(
                RelationSemanticPrompts.TableMaterial("flood_ctrl", "防汛调度表", "防汛调度记录,按水库关联"), // 描述优先
                RelationSemanticPrompts.TableMaterial("basin", "流域表", null), // 回退表注释
                RelationSemanticPrompts.TableMaterial("mystery", null, null), // 皆无:不进 prompt
                RelationSemanticPrompts.TableMaterial("blank", "  ", ""), // 空白等同无:不进 prompt
            ))

        assertTrue(prompt.contains("锚点表:reservoir"))
        assertTrue(prompt.contains("表注释:水库信息表"))
        assertTrue(prompt.contains("表描述:水库基础档案,记录每座水库的编码与规模"))
        assertTrue(prompt.contains("- flood_ctrl — 防汛调度记录,按水库关联"))
        assertFalse(prompt.contains("防汛调度表")) // 有描述时注释不出现
        assertTrue(prompt.contains("- basin — 流域表"))
        assertFalse(prompt.contains("mystery"))
        assertFalse(prompt.contains("blank"))
        assertTrue(prompt.contains("只输出 JSON 数组本身"))
    }

    @Test
    fun `表级prompt 锚点注释描述为空时不出现对应行`() {
        val prompt = RelationSemanticPrompts.buildTableScreenPrompt("t1", null, " ", listOf(
            RelationSemanticPrompts.TableMaterial("t2", "注释", null)))
        assertFalse(prompt.contains("表注释"))
        assertFalse(prompt.contains("表描述"))
        assertTrue(prompt.contains("- t2 — 注释"))
    }

    @Test
    fun `表级原料判定 描述或注释任一非空即可`() {
        assertTrue(RelationSemanticPrompts.hasTableScreenMaterial(listOf(
            RelationSemanticPrompts.TableMaterial("a", null, "描述"))))
        assertTrue(RelationSemanticPrompts.hasTableScreenMaterial(listOf(
            RelationSemanticPrompts.TableMaterial("a", "注释", null))))
        assertFalse(RelationSemanticPrompts.hasTableScreenMaterial(listOf(
            RelationSemanticPrompts.TableMaterial("a", " ", null))))
    }

    // ---------- 阶段一:响应解析 ----------

    @Test
    fun `表级解析 合法JSON 过滤无效表名且大小写归一`() {
        val valid = listOf("flood_ctrl", "basin", "Reservoir")
        assertEquals(listOf("flood_ctrl", "basin"),
            RelationSemanticPrompts.parseRelatedTables("[\"flood_ctrl\", \"basin\"]", valid))
        // 幻觉表名被过滤;大小写差异归一到实际表名;重复去重
        assertEquals(listOf("Reservoir"),
            RelationSemanticPrompts.parseRelatedTables("[\"reservoir\", \"no_such\", \"RESERVOIR\"]", valid))
        assertEquals(emptyList<String>(), RelationSemanticPrompts.parseRelatedTables("[]", valid))
    }

    @Test
    fun `表级解析 带多余文字与markdown代码块容错`() {
        val valid = listOf("flood_ctrl")
        assertEquals(listOf("flood_ctrl"),
            RelationSemanticPrompts.parseRelatedTables("相关表如下:\n```json\n[\"flood_ctrl\"]\n```\n以上。", valid))
        // 非字符串项忽略
        assertEquals(listOf("flood_ctrl"),
            RelationSemanticPrompts.parseRelatedTables("[\"flood_ctrl\", 42, null]", valid))
    }

    @Test
    fun `表级解析 坏JSON返回空不抛异常`() {
        val valid = listOf("flood_ctrl")
        assertEquals(emptyList<String>(), RelationSemanticPrompts.parseRelatedTables("[\"flood_ctrl\",", valid))
        assertEquals(emptyList<String>(), RelationSemanticPrompts.parseRelatedTables("没有相关的表", valid))
        assertEquals(emptyList<String>(), RelationSemanticPrompts.parseRelatedTables("", valid))
    }

    // ---------- 阶段二:字段级精判 prompt ----------

    @Test
    fun `字段级prompt 目标表无注释字段不进prompt 锚点字段全列`() {
        val prompt = RelationSemanticPrompts.buildColumnMatchPrompt(
            "reservoir",
            listOf(
                RelationSemanticPrompts.ColumnMaterial("res_code", "varchar(50)", "水库编码"),
                RelationSemanticPrompts.ColumnMaterial("rname", "varchar(50)", null), // 锚点字段无注释也列出
            ),
            "water_gate",
            listOf(
                RelationSemanticPrompts.ColumnMaterial("gate_res", "varchar(50)", "所属水库编码"),
                RelationSemanticPrompts.ColumnMaterial("remark", "varchar(200)", null), // 无注释:不进 prompt
            ))

        assertTrue(prompt.contains("锚点表:reservoir"))
        assertTrue(prompt.contains("- res_code varchar(50) — 水库编码"))
        assertTrue(prompt.contains("- rname varchar(50)"))
        assertTrue(prompt.contains("待匹配表:water_gate"))
        assertTrue(prompt.contains("- gate_res varchar(50) — 所属水库编码"))
        assertFalse(prompt.contains("remark"))
        assertTrue(prompt.contains("{\"anchor\":\"锚点字段名\",\"column\":\"待匹配表字段名\"}"))
        assertTrue(prompt.contains("只输出 JSON 数组本身"))
    }

    @Test
    fun `字段级prompt 锚点字段带映射名提示 无映射名不加`() {
        val prompt = RelationSemanticPrompts.buildColumnMatchPrompt(
            "d_work_order",
            listOf(
                // 锚点字段带映射名:追加常见名提示,帮 LLM 关联「其他表中引用该字段的字段」
                RelationSemanticPrompts.ColumnMaterial("id", "varchar(50)", "主键", listOf("work_order_id", "workOrderId")),
                RelationSemanticPrompts.ColumnMaterial("title", "varchar(50)", "标题"), // 无映射名:不加提示
            ),
            "f_assign",
            listOf(RelationSemanticPrompts.ColumnMaterial("work_order_id", "varchar(50)", "工单号")))

        assertTrue(prompt.contains("- id varchar(50) — 主键 (在其他表中常见名: work_order_id, workOrderId)"))
        assertTrue(prompt.contains("- title varchar(50) — 标题"))
        assertFalse(prompt.contains("标题 (在其他表中常见名"))
        // 目标表字段不带映射名提示
        assertTrue(prompt.contains("- work_order_id varchar(50) — 工单号"))
    }

    @Test
    fun `字段级原料判定 至少一个字段有注释`() {
        assertTrue(RelationSemanticPrompts.hasColumnMatchMaterial(listOf(
            RelationSemanticPrompts.ColumnMaterial("a", "int", "注释"))))
        assertFalse(RelationSemanticPrompts.hasColumnMatchMaterial(listOf(
            RelationSemanticPrompts.ColumnMaterial("a", "int", null),
            RelationSemanticPrompts.ColumnMaterial("b", "int", " "))))
    }

    // ---------- 阶段二:响应解析 ----------

    @Test
    fun `字段级解析 合法JSON 过滤无效字段且大小写归一去重`() {
        val anchors = listOf("res_code")
        val targets = listOf("gate_res", "remark")
        assertEquals(listOf("res_code" to "gate_res"),
            RelationSemanticPrompts.parseColumnPairs(
                "[{\"anchor\":\"res_code\",\"column\":\"gate_res\"}]", anchors, targets))
        // 大小写归一到实际字段名;幻觉字段/非对象项/缺键项忽略;重复对去重
        assertEquals(listOf("res_code" to "gate_res"),
            RelationSemanticPrompts.parseColumnPairs(
                "[{\"anchor\":\"RES_CODE\",\"column\":\"GATE_RES\"}," +
                        "{\"anchor\":\"res_code\",\"column\":\"gate_res\"}," +
                        "{\"anchor\":\"no_such\",\"column\":\"gate_res\"}," +
                        "{\"anchor\":\"res_code\"},\"x\"]", anchors, targets))
        assertEquals(emptyList<Pair<String, String>>(),
            RelationSemanticPrompts.parseColumnPairs("[]", anchors, targets))
    }

    @Test
    fun `字段级解析 带多余文字容错 坏JSON返回空`() {
        val anchors = listOf("res_code")
        val targets = listOf("gate_res")
        assertEquals(listOf("res_code" to "gate_res"),
            RelationSemanticPrompts.parseColumnPairs(
                "对应关系:[{\"anchor\":\"res_code\",\"column\":\"gate_res\"}] 完毕", anchors, targets))
        assertEquals(emptyList<Pair<String, String>>(),
            RelationSemanticPrompts.parseColumnPairs("[{\"anchor\":", anchors, targets))
        assertEquals(emptyList<Pair<String, String>>(),
            RelationSemanticPrompts.parseColumnPairs("无对应关系", anchors, targets))
    }
}
