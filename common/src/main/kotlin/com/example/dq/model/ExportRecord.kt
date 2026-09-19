package com.example.dq.model

import java.time.LocalDateTime

/**
 * 导出中心统一模型(V66 登记 / V67 push:导出一个文件出一条记录,含相对数据目录路径)。
 * 同步流式导出端点埋点 + downloadFile 成功后 landed 回填;落盘类(报告/抽样/比对)由写入方直推。
 * 诊断报告 .md(纯前端 Blob)与局域网 share 出口(实例间通道)不纳入。
 */
enum class ExportKind(val label: String) {
    SCAN_EXCEL("扫描 Excel"),
    SCAN_WORD("扫描 Word"),
    DBSTRUCT_WORD("库结构 Word"),
    PREVIEW_XLSX("预览 Excel"),
    COMPARE_XLSX("比对报告"),
    LIST_XLSX("列表 Excel"),
    TRANSFER_DATASOURCE("数据源 JSON"),
    TRANSFER_METADATA("元数据 JSON"),
    TRANSFER_SCAN("扫描记录 JSON"),
    TRANSFER_ANNOTATION("标注 JSON"),
    TEMPLATE("导入模版"),
    ERROR_EXPORT("错误中心导出"),
    IMPORT_FILE("导入原件"),
    REPORT_DOCX("调研报告"),
    SAMPLE_ZIP("抽样导出"),
    ;

    companion object {
        @JvmStatic
        fun parse(key: String?): ExportKind? = entries.firstOrNull { it.name == key }
    }
}

/** 同步类导出落库行(export_record;V67 起落盘类也由写入方推送,relPath 恒有) */
data class ExportRecord(
    val id: Long,
    val kind: ExportKind,
    val title: String,
    val fileName: String,
    val fileSize: Long?,
    val paramsJson: String?,
    /** 相对数据目录的详细路径(exports/…、compare/…、reports/…、sample-exports/…);打开/定位的唯一依据 */
    val relPath: String?,
    /** 文件 SHA-256 hex(同步类 landed 实测、落盘类 finalize 计算;失败/老记录为空) */
    val checksum: String?,
    val status: String,
    val error: String?,
    val createdAt: LocalDateTime?,
)

/** 导出中心统一列表项;id 为「kind:源id」字符串 */
data class ExportCenterItem(
    val id: String,
    val kind: ExportKind,
    val title: String,
    val fileName: String?,
    val fileSize: Long?,
    /** NONE=同步流式(文件直存 exports/)/DISK=任务产物;打开/定位统一按 [relPath] */
    val storage: String,
    /** 相对数据目录的详细路径;老记录(V66 及以前未 enrich)可能为 null */
    val relPath: String?,
    /** 文件 SHA-256 hex,前端文件列副行短显 + 悬浮全量;无则空 */
    val checksum: String?,
    /** 完整性(列表时按 relPath 现算 SHA-256 与记录比对,带 size+mtime 缓存):
     *  OK=可打开 / TAMPERED=源文件被改动(置灰禁点)/ MISSING=文件已删(删除线禁点);null=无 relPath 不可判定。
     *  两种异常「打开目录」都仍可用(MISSING 时前端降级为打开所在目录) */
    val fileState: String? = null,
    /** 统一状态:RUNNING(生成中)/SUCCESS/FAILED */
    val status: String,
    val error: String?,
    val createdAt: LocalDateTime?,
    val finishedAt: LocalDateTime?,
)

/** 导出落盘后回填路径/大小的请求体(fileName 关联最新一条 RUNNING 登记记录) */
data class ExportLandedRequest(val fileName: String?)

/** 导出失败的标记请求体(path 关联登记的 params.path;error 仅留档,前端已另行弹提示) */
data class ExportFailRequest(val path: String?, val error: String?)

data class ExportCenterPage(val items: List<ExportCenterItem>, val total: Long, val page: Int, val size: Int)
