package com.example.dq.license

/**
 * 授权码菜单清单(授权码 10 段新格式第 9 段,逗号分隔 key 列表)。
 * key 与前端一级路由一致(去掉前导斜杠),勾选即客户实例侧边栏可见 —— 不再区分基础/受控功能。
 *
 * 兼容旧格式授权码(无菜单段,菜单字段解码为 null):全部菜单除 数据比对/授权管理 外默认开放,
 * 数据比对(compare)/授权管理(license-admin)按旧功能段是否显式包含恢复
 * (旧功能 key:compare、license_admin),与旧版「基础业务功能恒有 + 受控功能显式包含」的行为完全一致。
 */
enum class LicenseMenu(val key: String, val label: String) {
    DATASOURCE("datasource", "数据源"),
    DASHBOARD("dashboard", "扫描记录"),
    TAGS("tags", "标记统计"),
    MANUAL_COLLECTS("manual-collects", "人工采集"),
    REPORT_EXPORTS("report-exports", "报告列表"),
    SAMPLE_EXPORTS("sample-exports", "抽样导出"),
    EXPORT_CENTER("export-center", "导出中心"),
    COMPARE("compare", "数据比对"),
    RELATIONS("relations", "ER 关系"),
    OBJECT_MANAGE("object-manage", "对象管理"),
    SQL_CONSOLE("sql-console", "SQL 控制台"),
    LAN_SHARE("lan-share", "局域网共享"),
    AI_USAGE("ai-usage", "模型用量统计"),
    SETTINGS("settings", "系统设置"),
    DIAGNOSTICS("diagnostics", "系统诊断"),
    ERROR_CENTER("error-center", "错误中心"),
    LOGS("logs", "运行日志"),
    LICENSE_ADMIN("license-admin", "授权管理"),
    ;

    companion object {
        /** 全部菜单(签发对话框勾选用),按声明顺序稳定输出 */
        val ALL: List<LicenseMenu> = entries.toList()

        private val byKey: Map<String, LicenseMenu> = entries.associateBy { it.key }

        /**
         * 解析授权码菜单段(逗号分隔 key 列表);null/空白返回空集。
         * 未知 key 静默忽略(向前兼容:旧程序遇到未来新增菜单不报错)。
         */
        fun parse(encoded: String?): Set<LicenseMenu> {
            if (encoded.isNullOrBlank()) return emptySet()
            return encoded.split(",")
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .mapNotNull { byKey[it] }
                .toSet()
        }

        /** 序列化为逗号分隔字符串(按枚举声明顺序,稳定输出;空集返回空串) */
        fun encode(menus: Set<LicenseMenu>): String =
            entries.filter { it in menus }.joinToString(",") { it.key }

        /**
         * 计算最终开放菜单集:新格式按菜单段;旧格式(菜单段为 null)按旧功能段推导,
         * 见 [fromLegacyFeatures]。导出中心为纯查询功能,恒显(任何授权码都并上)。
         */
        fun granted(payloadFeatures: String?, payloadMenus: String?): Set<LicenseMenu> =
            (if (payloadMenus != null) parse(payloadMenus) else fromLegacyFeatures(payloadFeatures)) + EXPORT_CENTER

        /** 旧格式(无菜单段)授权码的菜单推导:全部菜单除 数据比对/授权管理 外默认开放,两者按旧功能段显式包含恢复 */
        fun fromLegacyFeatures(payloadFeatures: String?): Set<LicenseMenu> {
            val menus = ALL.filter { it != COMPARE && it != LICENSE_ADMIN }.toMutableSet()
            if (legacyHas(payloadFeatures, "compare")) menus += COMPARE
            if (legacyHas(payloadFeatures, "license_admin")) menus += LICENSE_ADMIN
            return menus
        }

        /** 旧功能段是否显式包含某旧功能 key(compare/license_admin) */
        private fun legacyHas(payloadFeatures: String?, key: String): Boolean =
            !payloadFeatures.isNullOrBlank() &&
                payloadFeatures.split(",").any { it.trim() == key }
    }
}
