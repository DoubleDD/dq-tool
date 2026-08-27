package com.example.dq.service

import com.deepoove.poi.XWPFTemplate
import com.deepoove.poi.config.Configure
import com.deepoove.poi.plugin.table.LoopRowTableRenderPolicy
import com.deepoove.poi.policy.RenderPolicy
import com.deepoove.poi.template.ElementTemplate
import com.deepoove.poi.template.run.RunTemplate
import com.deepoove.poi.xwpf.BodyContainerFactory
import com.example.dq.dialect.DialectFactory
import com.example.dq.repository.TagRepository
import org.apache.poi.xwpf.usermodel.XWPFDocument
import org.apache.poi.xwpf.usermodel.XWPFParagraph
import org.apache.poi.xwpf.usermodel.XWPFTable
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTFldChar
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTP
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTR
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTRPr
import java.io.OutputStream
import javax.xml.namespace.QName

/**
 * 数据源整库表结构 Word 导出:poi-tl 模板渲染,同步写流(与扫描结果 Word 导出同链路)。
 * 与 ScanWordExportService(单库扫描快照版)的差异:面向「数据源下所有库(白名单过滤后)」,
 * 数据走 MetadataService 实时元数据(meta_cache 缓存优先,未缓存回源业务库并落缓存),不要求先扫描;
 * 行数/体积为元数据估算值。三章表清单按 库→模式 两级、四章表结构按 模式→表 两级循环,
 * 由 TableListSectionsPolicy/StructSectionsPolicy 克隆模板原型生成;
 * 模板由 scripts/make-word-template-fulldb.py 改造。
 */
class DbStructExportService(
    private val metadataService: MetadataService,
    private val dataSourceService: DataSourceService,
    private val dialectFactory: DialectFactory,
    private val tagRepository: TagRepository,
) {

    fun export(datasourceId: Long, out: OutputStream) {
        val config = Configure.builder()
            .bind("dbs", LoopRowTableRenderPolicy())
            .bind("tableSections", TableListSectionsPolicy())
            .bind("structSections", StructSectionsPolicy())
            .build()
        javaClass.getResourceAsStream(TEMPLATE_PATH).use { input ->
            requireNotNull(input) { "模板缺失: $TEMPLATE_PATH" }
            val data = buildRenderData(datasourceId)
            val template = XWPFTemplate.compile(input, config).render(data)
            @Suppress("UNCHECKED_CAST")
            rewriteTocEntries(template.xwpfDocument, data["tocEntries"] as List<TocEntry>)
            template.writeAndClose(out)
        }
    }

    private fun buildRenderData(datasourceId: Long): Map<String, Any> {
        val ds = dataSourceService.get(datasourceId)
        val dialect = dialectFactory.get(ds.dbType!!)
        // 多库方言(SQL Server)按库展开,库列表已按白名单过滤;单库方言 schema 即用户眼中的「库」
        val databases: List<String?> =
            if (dialect.supportsMultiDatabase()) metadataService.listDatabases(datasourceId)
            else listOf(null)
        require(databases.isNotEmpty()) { "该数据源下没有可导出的库(库过滤白名单生效后为空)" }

        var schemaCount = 0
        var tableCount = 0L
        var totalRows = 0L
        var totalSize = 0L
        val dbRows = ArrayList<Map<String, Any>>()          // 第二章数据库清单(每 库+模式 一行)
        val tableSections = ArrayList<DbTableSection>()     // 第三章表清单(按库分节)
        val structSections = ArrayList<SchemaStructSection>() // 第四章表结构(按 库+模式 分节)

        for (db in databases) {
            val stats = metadataService.listSchemaStats(datasourceId, db)
            // 多库方言:同一库的 schema 归入同一个「数据库:X」H2 节;单库方言没有库名,
            // 每个 schema 自成一节(口径与扫描结果 Word 导出一致:dbName = schemaName)
            val schemasOfDb = ArrayList<SchemaTableSection>()
            for (stat in stats) {
                val schema = stat.name ?: continue
                val dbLabel = db ?: schema
                schemaCount++
                dbRows += mapOf(
                    "dbName" to dbLabel,
                    "schemaName" to schema,
                    "schemaDesc" to dash(stat.description),
                    "tableCount" to (stat.tableCount?.let { String.format("%,d", it.toLong()) } ?: "-"),
                    "totalSize" to (stat.sizeBytes?.let { ScanWordExportService.formatBytes(it) } ?: "-"),
                )

                val tables = metadataService.listTables(datasourceId, db, schema)
                val tagsByTable = tagRepository.tableTagsBySchema(datasourceId, db ?: "", schema)
                val tableRows = ArrayList<List<String>>(tables.size)
                val structs = ArrayList<TableStruct>(tables.size)
                for (t in tables) {
                    tableCount++
                    totalRows += t.estRows ?: 0
                    totalSize += t.sizeBytes ?: 0
                    val tags = tagsByTable[t.name].orEmpty().joinToString(",") { it.name }
                    tableRows += listOf(
                        t.name ?: "", dash(t.comment),
                        String.format("%,d", t.estRows ?: 0), tags.ifBlank { "-" },
                    )
                    val cols = metadataService.listTableColumns(datasourceId, db, schema, t.name ?: "").map { c ->
                        listOf(
                            c.name,
                            dash(c.comment),
                            c.displayType,
                            if (c.primaryKey) "是" else "",
                            if (!c.nullable) "是" else "",
                            c.defaultValue ?: "—",
                        )
                    }
                    structs += TableStruct(
                        title = if (t.comment.isNullOrBlank()) t.name ?: "" else "${t.comment}:${t.name}",
                        columns = cols,
                    )
                }

                if (db != null) {
                    schemasOfDb += SchemaTableSection("模式：$schema", tableRows)
                } else {
                    tableSections += DbTableSection("数据库：$dbLabel", listOf(SchemaTableSection("模式：$schema", tableRows)))
                }
                structSections += SchemaStructSection("数据库：${dbLabel}中模式：$schema", structs)
            }
            if (db != null) {
                tableSections += DbTableSection("数据库：$db", schemasOfDb.toList())
            }
        }
        require(schemaCount > 0) { "该数据源下没有可导出的库(库过滤白名单生效后为空)" }

        // 目录条目(编号格式与模板多级编号一致:H1「N.」/H2「N.M.」/H3「N.M.K.」;页码由客户端刷新域生成)
        val tocEntries = ArrayList<TocEntry>()
        tocEntries += TocEntry(1, "1. 总体情况")
        tocEntries += TocEntry(1, "2. 数据库清单")
        tocEntries += TocEntry(1, "3. 表清单")
        tableSections.forEachIndexed { i, sec ->
            tocEntries += TocEntry(2, "3.${i + 1}. ${sec.heading2}")
            sec.schemas.forEachIndexed { j, s -> tocEntries += TocEntry(3, "3.${i + 1}.${j + 1}. ${s.heading3}") }
        }
        tocEntries += TocEntry(1, "4. 表结构")
        structSections.forEachIndexed { i, sec ->
            tocEntries += TocEntry(2, "4.${i + 1}. ${sec.heading2}")
            sec.structs.forEachIndexed { j, s -> tocEntries += TocEntry(3, "4.${i + 1}.${j + 1}. ${s.title}") }
        }

        return mapOf(
            "dbCount" to String.format("%,d", tableSections.size.toLong()),
            "schemaCount" to String.format("%,d", schemaCount.toLong()),
            "tableCount" to String.format("%,d", tableCount),
            "totalRows" to String.format("%,d", totalRows),
            "totalSize" to ScanWordExportService.formatBytes(totalSize),
            "dbs" to dbRows,
            "tableSections" to tableSections,
            "structSections" to structSections,
            "tocEntries" to tocEntries,
        )
    }

    private fun dash(s: String?): String = if (s.isNullOrBlank()) "-" else s

    companion object {
        const val TEMPLATE_PATH = "/templates/db-structure-full.docx"
    }
}

/** 三章表清单分节:一个库(单库方言 = 一个 schema)下若干模式的表清单 */
data class DbTableSection(val heading2: String, val schemas: List<SchemaTableSection>)

/** 三章表清单模式小节:H3 标题 + 表行(表英文/中文/数据量/标签) */
data class SchemaTableSection(val heading3: String, val tables: List<List<String>>)

/** 四章表结构分节:一个 库+模式 组合下逐表的结构小节 */
data class SchemaStructSection(val heading2: String, val structs: List<TableStruct>)

/** 目录条目(渲染后由 rewriteTocEntries 重写 sdt 缓存内容;页码仍由 Word/WPS 刷新域生成) */
data class TocEntry(val level: Int, val text: String)

private const val W_NS = "http://schemas.openxmlformats.org/wordprocessingml/2006/main"
private val W_P = QName(W_NS, "p")

/**
 * 目录(sdt)缓存内容重写:条目文字在渲染后按标题层级直接生成对(编号与多级列表一致),
 * 不自动刷域的查看器(WPS)也能看到正确目录;TOC 域结构与 dirty/updateFields 标记保留,
 * 页码与跳转链接仍由 Word/WPS 刷新域时重建(页码只有排版引擎能算,程序生成期不可知)。
 * 模板目录结构:p0=「目录」标题段(保留),首条目段含 TOC 域 begin/instrText/separate,
 * 中间为缓存条目段(样式按首次出现顺序对应 1/2/3 级),末段含域 end(保留)。
 */
internal fun rewriteTocEntries(doc: XWPFDocument, entries: List<TocEntry>) {
    if (entries.isEmpty()) return
    val ns = "declare namespace w='$W_NS';"
    val toc = doc.document.body.selectPath("$ns./w:sdt")
        .firstOrNull { it.xmlText().contains("TOC \\o") } ?: return
    val paras = toc.selectPath("$ns./w:sdtContent/w:p").filterIsInstance<CTP>()
    if (paras.size < 3) return
    val first = paras[1]
    val last = paras.last()           // 含 TOC 域 end 的收尾段(保留)
    val oldEntries = paras.subList(1, paras.size - 1)

    // 条目原型:按样式首次出现顺序对应 1/2/3 级(模板目录固定 \o "1-3")
    val protoByStyle = LinkedHashMap<String, CTP>()
    for (p in oldEntries) {
        val style = p.pPr?.pStyle?.`val` ?: continue
        protoByStyle.putIfAbsent(style, p.copy() as CTP)
    }
    val protos = protoByStyle.values.toList()
    if (protos.isEmpty()) return

    // 域前缀 run(begin+instrText+separate,含模板脚本置的 dirty 标记),从首条目段提取
    val prefixRuns = ArrayList<CTR>()
    for (r in first.selectPath("$ns./w:r").filterIsInstance<CTR>()) {
        prefixRuns += r.copy() as CTR
        val fld = r.selectPath("$ns./w:fldChar").filterIsInstance<CTFldChar>().firstOrNull()
        if (fld != null && fld.fldCharType.toString() == "separate") break
    }

    // 删除旧的缓存条目段(游标 removeXml,不直接动 DOM 避免 store 失同步)
    for (p in oldEntries) {
        val c = p.newCursor()
        c.removeXml()
        c.dispose()
    }
    // 在收尾段(含域 end)前插入等量空段落:游标固定在其 START,逐次插入顺序天然正确
    val cursor = last.newCursor()
    try {
        repeat(entries.size) { cursor.insertElement(W_P) }
    } finally {
        cursor.dispose()
    }

    // 重新查询并填充(新段落已在文档 store 内,可直接用 XMLBeans API)
    val newParas = toc.selectPath("$ns./w:sdtContent/w:p").filterIsInstance<CTP>()
    entries.forEachIndexed { idx, entry ->
        val p = newParas[idx + 1]
        val proto = protos.getOrElse(entry.level - 1) { protos.last() }
        if (proto.pPr != null) p.addNewPPr().set(proto.pPr.copy())
        if (idx == 0) {
            prefixRuns.forEachIndexed { i, pr -> p.insertNewR(i).set(pr.copy()) }
        }
        val r = p.addNewR()
        protoRPr(ns, proto)?.let { r.addNewRPr().set(it) }
        r.addNewT().stringValue = entry.text
    }
}

/** 原型条目首个文本 run 的字符格式(字体/字号随克隆保留) */
private fun protoRPr(ns: String, proto: CTP): CTRPr? {
    // 不用 /w:r[w:t] 谓词:运行环境无 Saxon,xmlbeans 内置 XPath 只支持简单路径
    val r = proto.selectPath("$ns./w:r").filterIsInstance<CTR>().firstOrNull { it.sizeOfTArray() > 0 }
        ?: return null
    return r.rPr?.copy() as CTRPr?
}

/**
 * 第三章「表清单」渲染策略。模板在锚点 {{tableSections}} 段落后保留一套原型(H2 数据库标题段 +
 * H3 模式标题段 + 表清单表,表 = 表头 + 一行数据原型),渲染时按 库→模式 两级深拷贝原型并填充,
 * 最后删除原型与锚点段——标题编号(numPr)、表格样式全部随克隆保留。空 schema 的表清单只留表头。
 */
class TableListSectionsPolicy : RenderPolicy {

    override fun render(eleTemplate: ElementTemplate, data: Any?, template: XWPFTemplate) {
        val run = (eleTemplate as RunTemplate).run
        run.setText("", 0)
        val doc = run.paragraph.document as XWPFDocument
        val anchor = run.paragraph
        val elements = doc.bodyElements
        val anchorIdx = elements.indexOfFirst { it is XWPFParagraph && (it === anchor || it.ctp === anchor.ctp) }
        require(anchorIdx >= 0) { "表清单模板缺少 {{tableSections}} 锚点段" }
        val paras = elements.drop(anchorIdx + 1).filterIsInstance<XWPFParagraph>()
        val protoH2 = paras.getOrNull(0)
        val protoH3 = paras.getOrNull(1)
        val protoT = elements.drop(anchorIdx + 1).filterIsInstance<XWPFTable>().firstOrNull()
        require(protoH2 != null && protoH3 != null && protoT != null) { "表清单模板缺少原型(H2 + H3 + 表)" }
        val protoH2Ctp = protoH2.ctp.copy()
        val protoH3Ctp = protoH3.ctp.copy()
        val protoTbl = protoT.ctTbl.copy()

        val sections = (data as? List<*>)?.filterIsInstance<DbTableSection>().orEmpty()
        val body = BodyContainerFactory.getBodyContainer(run)
        if (sections.isEmpty()) {
            val p = body.insertNewParagraph(run)
            setParagraphText(p.ctp, "(无数据)")
        }
        for (section in sections) {
            // BodyContainer 依调用顺序在锚点段后插入;插入后整体替换为原型克隆
            val h2 = body.insertNewParagraph(run)
            h2.ctp.set(protoH2Ctp.copy())
            setParagraphText(h2.ctp, section.heading2)
            for (schema in section.schemas) {
                val h3 = body.insertNewParagraph(run)
                h3.ctp.set(protoH3Ctp.copy())
                setParagraphText(h3.ctp, schema.heading3)
                val table = body.insertNewTable(run, 2, 4)
                table.ctTbl.set(protoTbl.copy())
                fillRows(table.ctTbl, schema.tables)
            }
        }

        removePrototype(doc, anchor, protoH2, protoH3, protoT)
    }
}

/**
 * 第四章「表结构」渲染策略。模板在锚点 {{structSections}} 段落后保留一套原型(H2「数据库:X中模式:Y」
 * 标题段 + H3 表标题段 + 字段表,字段表 = 表头 + 一行数据原型),渲染时按 模式→表 两级深拷贝原型并填充,
 * 最后删除原型与锚点段。无表 schema 在 H2 后输出「(无数据表)」;零字段表只留表头。
 */
class StructSectionsPolicy : RenderPolicy {

    override fun render(eleTemplate: ElementTemplate, data: Any?, template: XWPFTemplate) {
        val run = (eleTemplate as RunTemplate).run
        run.setText("", 0)
        val doc = run.paragraph.document as XWPFDocument
        val anchor = run.paragraph
        val elements = doc.bodyElements
        val anchorIdx = elements.indexOfFirst { it is XWPFParagraph && (it === anchor || it.ctp === anchor.ctp) }
        require(anchorIdx >= 0) { "表结构模板缺少 {{structSections}} 锚点段" }
        val paras = elements.drop(anchorIdx + 1).filterIsInstance<XWPFParagraph>()
        val protoH2 = paras.getOrNull(0)
        val protoH3 = paras.getOrNull(1)
        val protoT = elements.drop(anchorIdx + 1).filterIsInstance<XWPFTable>().firstOrNull()
        require(protoH2 != null && protoH3 != null && protoT != null) { "表结构模板缺少原型(H2 + H3 + 字段表)" }
        val protoH2Ctp = protoH2.ctp.copy()
        val protoH3Ctp = protoH3.ctp.copy()
        val protoTbl = protoT.ctTbl.copy()

        val sections = (data as? List<*>)?.filterIsInstance<SchemaStructSection>().orEmpty()
        val body = BodyContainerFactory.getBodyContainer(run)
        if (sections.isEmpty()) {
            val p = body.insertNewParagraph(run)
            setParagraphText(p.ctp, "(无数据)")
        }
        for (section in sections) {
            val h2 = body.insertNewParagraph(run)
            h2.ctp.set(protoH2Ctp.copy())
            setParagraphText(h2.ctp, section.heading2)
            if (section.structs.isEmpty()) {
                val p = body.insertNewParagraph(run)
                setParagraphText(p.ctp, "(无数据表)")
            }
            for (s in section.structs) {
                val h3 = body.insertNewParagraph(run)
                h3.ctp.set(protoH3Ctp.copy())
                setParagraphText(h3.ctp, s.title)
                val table = body.insertNewTable(run, 2, 6)
                table.ctTbl.set(protoTbl.copy())
                fillRows(table.ctTbl, s.columns)
            }
        }

        removePrototype(doc, anchor, protoH2, protoH3, protoT)
    }
}

/** 删除原型小节(H2/H3/表)与锚点段落(先删靠后的,避免下标前移) */
private fun removePrototype(
    doc: XWPFDocument, anchor: XWPFParagraph,
    protoH2: XWPFParagraph, protoH3: XWPFParagraph, protoT: XWPFTable,
) {
    val all = doc.bodyElements
    val idxT = all.indexOfFirst { it === protoT }
    val idxH3 = all.indexOfFirst { it === protoH3 }
    val idxH2 = all.indexOfFirst { it === protoH2 }
    val idxA = all.indexOfFirst { it is XWPFParagraph && (it === anchor || it.ctp === anchor.ctp) }
    listOf(idxT, idxH3, idxH2, idxA).sortedDescending().forEach { if (it >= 0) doc.removeBodyElement(it) }
}
