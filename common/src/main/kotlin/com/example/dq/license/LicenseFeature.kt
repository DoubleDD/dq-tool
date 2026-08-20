package com.example.dq.license

/**
 * 授权码功能清单。payload 第 8 段为逗号分隔的功能 key 列表(8 段新格式;2/6/7 段旧格式无该段)。
 *
 * 功能分两类:
 * - 基础功能(业务功能):默认拥有,即使授权码中未列出也视为已授权(旧格式授权码不受影响);
 *   未来若需收紧某项业务功能,只需把它从 [BASE_FEATURES] 移出,已按新格式签发的码仍会显式包含它(向后兼容)。
 * - 受控功能(系统/管理功能):必须授权码显式包含才可用;未授权时前端隐藏入口、后端接口返回 403。
 */
enum class LicenseFeature(val key: String, val label: String) {
    /** 表级/字段级扫描检测、分段扫描、断点续扫、扫描记录/详情、任务看板 */
    SCAN("scan", "扫描检测"),
    /** 数据源管理:增删改/测试连接/SSH 隧道/库过滤/导入导出配置 */
    DATASOURCE("datasource", "数据源管理"),
    /** 扫描结果 Excel 导出 */
    EXCEL("excel", "Excel导出"),
    /** Word 数据调研报告导出 */
    REPORT("report", "Word报告"),
    /** AI 表说明(生成/编辑表描述) */
    AI_DOC("ai_doc", "AI表说明"),
    /** AI 自动打标 */
    AI_TAG("ai_tag", "AI自动打标"),
    /** 表标记与统计 */
    TAG("tag", "表标记"),
    /** 运行日志(SSE 实时日志页;开发者排障功能) */
    LOGS("logs", "运行日志"),
    /** 授权码管理(生成/管理授权码页;管理员实例专属) */
    LICENSE_ADMIN("license_admin", "授权码管理"),
    ;

    companion object {
        /** 基础功能集:业务功能默认拥有,授权码中未列出也视为已授权 */
        val BASE_FEATURES: Set<LicenseFeature> = setOf(
            SCAN, DATASOURCE, EXCEL, REPORT, AI_DOC, AI_TAG, TAG
        )

        /** 受控功能集:必须授权码显式包含 */
        val CONTROLLED_FEATURES: Set<LicenseFeature> = setOf(LOGS, LICENSE_ADMIN)

        /** 全部功能(签发对话框勾选用),按声明顺序稳定输出 */
        val ALL: List<LicenseFeature> = entries.toList()

        private val byKey: Map<String, LicenseFeature> = entries.associateBy { it.key }

        /**
         * 解析授权码 payload 的功能段(逗号分隔 key 列表);null/空白返回空集。
         * 未知 key 静默忽略(向前兼容:旧程序遇到未来新增功能不报错)。
         */
        fun parse(encoded: String?): Set<LicenseFeature> {
            if (encoded.isNullOrBlank()) return emptySet()
            return encoded.split(",")
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .mapNotNull { byKey[it] }
                .toSet()
        }

        /** 序列化为逗号分隔字符串(按枚举声明顺序,稳定输出;空集返回空串) */
        fun encode(features: Set<LicenseFeature>): String =
            entries.filter { it in features }.joinToString(",") { it.key }

        /**
         * 计算最终已授权功能集 = 基础功能集 ∪ 授权码显式包含的功能。
         * 即业务功能永远可用,受控功能必须授权码里明确写出。
         */
        fun granted(payloadFeatures: String?): Set<LicenseFeature> =
            BASE_FEATURES + parse(payloadFeatures)
    }
}
