package com.example.dq.service

import com.example.dq.model.DataSourceExportFile
import com.example.dq.model.DataSourceExportItem
import com.example.dq.model.DataSourceRequest
import com.example.dq.model.ImportFailure
import com.example.dq.model.ImportResult
import com.example.dq.repository.DataSourceRepository
import com.example.dq.util.CryptoUtil
import com.example.dq.util.NavicatCrypto
import com.example.dq.util.TransferCrypto
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.io.OutputStream
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import javax.xml.parsers.DocumentBuilderFactory

/** 数据源配置导入/导出:自有 JSON 格式(密码 TransferCrypto 固定密钥加密,跨实例可导入)+ Navicat .ncx + DataGrip 剪贴板 XML */
class DataSourceTransferService(
    private val repo: DataSourceRepository,
    private val crypto: CryptoUtil,
    private val dataSourceService: DataSourceService,
) {

    private val objectMapper = jacksonObjectMapper()

    /** 导出指定数据源为 JSON 文件;密码从实例密钥解密后用导出文件固定密钥重新加密 */
    fun export(ids: List<Long>, out: OutputStream) {
        val items = ids.map { id ->
            val c = repo.findById(id)
                ?: throw IllegalArgumentException("数据源不存在: $id")
            DataSourceExportItem(
                name = c.name ?: "",
                jdbcUrl = c.jdbcUrl ?: "",
                username = c.username,
                passwordEnc = TransferCrypto.encrypt(crypto.decrypt(c.password)),
                rowThreshold = c.rowThreshold,
                sizeThresholdBytes = c.sizeThresholdBytes,
                schemaFilter = c.schemaFilter,
                sshEnabled = c.sshEnabled,
                sshHost = c.sshHost,
                sshPort = c.sshPort,
                sshUsername = c.sshUsername,
                sshAuthMethod = c.sshAuthMethod,
                sshPasswordEnc = TransferCrypto.encrypt(crypto.decrypt(c.sshPassword)),
                sshPrivateKeyEnc = TransferCrypto.encrypt(crypto.decrypt(c.sshPrivateKey)),
                sshPassphraseEnc = TransferCrypto.encrypt(crypto.decrypt(c.sshPassphrase)),
            )
        }
        val file = DataSourceExportFile(
            app = "dq-tool",
            version = 1,
            exportedAt = OffsetDateTime.now().format(DateTimeFormatter.ISO_OFFSET_DATE_TIME),
            items = items,
        )
        objectMapper.writerWithDefaultPrettyPrinter().writeValue(out, file)
    }

    /** 导入自有 JSON 导出文件 */
    fun importJson(input: InputStream): ImportResult {
        val file: DataSourceExportFile = try {
            objectMapper.readValue(input)
        } catch (e: Exception) {
            throw IllegalArgumentException("不是有效的数据源导出文件", e)
        }
        if (file.app != "dq-tool" || file.version != 1) {
            throw IllegalArgumentException("不是有效的数据源导出文件")
        }
        if (file.items.isEmpty()) {
            throw IllegalArgumentException("导出文件中没有数据源")
        }
        val result = ImportResult(total = file.items.size)
        file.items.forEach { item ->
            try {
                val plain = TransferCrypto.decrypt(item.passwordEnc)
                val sshPassword = TransferCrypto.decrypt(item.sshPasswordEnc)
                val sshPrivateKey = TransferCrypto.decrypt(item.sshPrivateKeyEnc)
                val sshPassphrase = TransferCrypto.decrypt(item.sshPassphraseEnc)
                val importedBefore = result.imported.size
                importOne(item.name, item.jdbcUrl, item.username, plain,
                    item.rowThreshold, item.sizeThresholdBytes, result,
                    schemaFilter = item.schemaFilter,
                    sshEnabled = item.sshEnabled, sshHost = item.sshHost, sshPort = item.sshPort,
                    sshUsername = item.sshUsername, sshAuthMethod = item.sshAuthMethod,
                    sshPassword = sshPassword, sshPrivateKey = sshPrivateKey, sshPassphrase = sshPassphrase)
                // 导出文件里密码为空时静默导入会留下无密码数据源,提示用户补充(列表页也会以「未设密码」标出)
                if (plain.isNullOrEmpty() && result.imported.size > importedBefore) {
                    result.warnings.add("「${result.imported.last()}」导入成功但未包含密码,请编辑数据源补充密码")
                }
            } catch (e: IllegalArgumentException) {
                result.failed.add(ImportFailure(item.name, e.message ?: "密文无法解密"))
                return@forEach
            }
        }
        return result
    }

    /** 导入 Navicat .ncx 导出文件(Ver 1.x,XML);密码用 NavicatCrypto 解密 */
    fun importNcx(input: InputStream): ImportResult {
        val doc = try {
            newSafeDocumentBuilderFactory().newDocumentBuilder().parse(input)
        } catch (e: Exception) {
            throw IllegalArgumentException("不是有效的 Navicat 导出文件(.ncx)", e)
        }

        val connections = doc.getElementsByTagName("Connection")
        val result = ImportResult(total = connections.length)
        if (connections.length == 0) {
            throw IllegalArgumentException("导出文件中没有数据源")
        }
        for (i in 0 until connections.length) {
            val el = connections.item(i) as org.w3c.dom.Element
            val name = el.getAttribute("ConnectionName")
            val host = el.getAttribute("Host")
            val port = el.getAttribute("Port")
            val database = el.getAttribute("Database")
            val connType = el.getAttribute("ConnType").uppercase()

            val jdbcUrl = when {
                host.isBlank() || port.isBlank() || database.isBlank() ->
                    null
                connType == "MYSQL" || connType == "MARIADB" ->
                    "jdbc:mysql://$host:$port/$database"
                connType == "POSTGRESQL" ->
                    "jdbc:postgresql://$host:$port/$database"
                connType == "SQLSERVER" ->
                    "jdbc:sqlserver://$host:$port;databaseName=$database"
                connType == "ORACLE" ->
                    "jdbc:oracle:thin:@//$host:$port/$database"
                else -> null
            }
            if (jdbcUrl == null) {
                val reason =
                    if (host.isBlank() || port.isBlank() || database.isBlank()) "Host/Port/Database 信息不完整"
                    else "不支持的连接类型: $connType"
                result.failed.add(ImportFailure(name, reason))
                continue
            }

            val passwordHex = el.getAttribute("Password")
            val plain: String? = if (el.getAttribute("SavePassword") == "true" && passwordHex.isNotBlank()) {
                try {
                    NavicatCrypto.decrypt(passwordHex)
                } catch (e: IllegalArgumentException) {
                    result.failed.add(ImportFailure(name, e.message ?: "密码解密失败"))
                    continue
                }
            } else {
                result.failed.add(ImportFailure(name, "未保存密码"))
                continue
            }

            // SSH 隧道配置随数据源一并导入;秘密字段(密码/私钥口令)为 Navicat 密文,解密失败计入 failed
            var sshEnabled = false
            var sshHost: String? = null
            var sshPort: Int? = null
            var sshUsername: String? = null
            var sshAuthMethod: String? = null
            var sshPassword: String? = null
            var sshPassphrase: String? = null
            if (el.getAttribute("SSH") == "true") {
                sshEnabled = true
                sshHost = el.getAttribute("SSH_Host").ifBlank { null }
                sshPort = el.getAttribute("SSH_Port").toIntOrNull()
                sshUsername = el.getAttribute("SSH_UserName").ifBlank { null }
                sshAuthMethod = when (el.getAttribute("SSH_AuthenMethod").uppercase()) {
                    "PASSWORD" -> "password"
                    "PUBLICKEY" -> "publickey"
                    else -> null
                }
                val sshPasswordHex = el.getAttribute("SSH_Password")
                if (sshPasswordHex.isNotBlank()) {
                    sshPassword = try {
                        NavicatCrypto.decrypt(sshPasswordHex)
                    } catch (e: IllegalArgumentException) {
                        result.failed.add(ImportFailure(name, e.message ?: "SSH 密码解密失败"))
                        continue
                    }
                }
                val sshPassphraseHex = el.getAttribute("SSH_Passphrase")
                if (sshPassphraseHex.isNotBlank()) {
                    sshPassphrase = try {
                        NavicatCrypto.decrypt(sshPassphraseHex)
                    } catch (e: IllegalArgumentException) {
                        result.failed.add(ImportFailure(name, e.message ?: "SSH 私钥口令解密失败"))
                        continue
                    }
                }
                // SSH_PrivateKey 是用户机器上的私钥文件路径,导出文件里没有内容,只能提示用户手动粘贴
                if (el.getAttribute("SSH_PrivateKey").isNotBlank()) {
                    result.warnings.add("$name 使用私钥认证,请导入后手动粘贴私钥内容")
                }
            }
            importOne(name, jdbcUrl, el.getAttribute("UserName"), plain, null, null, result,
                sshEnabled = sshEnabled, sshHost = sshHost, sshPort = sshPort, sshUsername = sshUsername,
                sshAuthMethod = sshAuthMethod, sshPassword = sshPassword, sshPassphrase = sshPassphrase)
        }
        return result
    }

    /**
     * 导入 DataGrip(JetBrains IDE)「复制数据源到剪贴板」的 XML。
     * 剪贴板原文带 `#DataSourceSettings#` / `#BEGIN#` 等非 XML 注释行,可能含多个 `<data-source>` 块;
     * 密码存在 IDE 主密码保险箱,格式里永远拿不到,导入后需用户逐个编辑补充。
     */
    fun importDataGrip(text: String): ImportResult {
        // 剥掉 # 注释行后包一层根节点,把多个 <data-source> 块合成合法 XML
        val xmlBody = text.lineSequence()
            .filter { !it.trimStart().startsWith("#") }
            .joinToString("\n")
            .trim()
        val doc = try {
            newSafeDocumentBuilderFactory().newDocumentBuilder()
                .parse(ByteArrayInputStream("<root>$xmlBody</root>".toByteArray(Charsets.UTF_8)))
        } catch (e: Exception) {
            throw IllegalArgumentException("不是有效的 DataGrip 数据源剪贴板内容", e)
        }

        val nodes = doc.getElementsByTagName("data-source")
        if (nodes.length == 0) {
            throw IllegalArgumentException("内容中没有 <data-source> 数据源定义")
        }
        val result = ImportResult(total = nodes.length)
        var passwordWarned = false
        for (i in 0 until nodes.length) {
            val el = nodes.item(i) as org.w3c.dom.Element
            val name = el.getAttribute("name").trim()
            val jdbcUrl = childText(el, "jdbc-url")
            val username = childText(el, "user-name").ifBlank { null }
            if (name.isBlank()) {
                result.failed.add(ImportFailure("(未命名)", "缺少数据源名称"))
                continue
            }
            if (jdbcUrl.isBlank()) {
                result.failed.add(ImportFailure(name, "缺少 jdbc-url"))
                continue
            }
            importOne(name, jdbcUrl, username, null, null, null, result)
            // 密码缺失是该格式的固有限制,成功导入时提示一次即可
            if (!passwordWarned && result.imported.isNotEmpty()) {
                result.warnings.add("DataGrip 剪贴板内容不含连接密码,请导入后逐个编辑数据源补充密码")
                passwordWarned = true
            }
        }
        return result
    }

    /** 取直接子元素文本(去空白);不存在返回空串 */
    private fun childText(el: org.w3c.dom.Element, tag: String): String {
        val children = el.getElementsByTagName(tag)
        return if (children.length == 0) "" else children.item(0).textContent?.trim() ?: ""
    }

    /** 防 XXE 的 DocumentBuilderFactory:关闭 DOCTYPE 声明与外部实体 */
    private fun newSafeDocumentBuilderFactory(): DocumentBuilderFactory {
        val factory = DocumentBuilderFactory.newInstance()
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
        factory.setFeature("http://xml.org/sax/features/external-general-entities", false)
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false)
        factory.isXIncludeAware = false
        factory.isExpandEntityReferences = false
        return factory
    }

    /** 单条导入:重名自动加「 (2)」「 (3)」后缀;单条失败收集不中断整批 */
    private fun importOne(
        name: String,
        jdbcUrl: String,
        username: String?,
        plainPassword: String?,
        rowThreshold: Long?,
        sizeThresholdBytes: Long?,
        result: ImportResult,
        schemaFilter: List<String>? = null,
        sshEnabled: Boolean? = null,
        sshHost: String? = null,
        sshPort: Int? = null,
        sshUsername: String? = null,
        sshAuthMethod: String? = null,
        sshPassword: String? = null,
        sshPrivateKey: String? = null,
        sshPassphrase: String? = null,
    ) {
        try {
            val existing = repo.findAll().mapNotNull { it.name }.toMutableSet()
            existing.addAll(result.imported)
            existing.addAll(result.renamed.values)
            var finalName = name
            var seq = 2
            while (finalName in existing) {
                finalName = "$name (${seq++})"
            }
            if (finalName != name) {
                result.renamed[name] = finalName
            }
            dataSourceService.create(
                DataSourceRequest(finalName, jdbcUrl, username, plainPassword, rowThreshold, sizeThresholdBytes,
                    schemaFilter = schemaFilter,
                    sshEnabled = sshEnabled, sshHost = sshHost, sshPort = sshPort, sshUsername = sshUsername,
                    sshAuthMethod = sshAuthMethod, sshPassword = sshPassword,
                    sshPrivateKey = sshPrivateKey, sshPassphrase = sshPassphrase))
            result.imported.add(finalName)
        } catch (e: Exception) {
            result.failed.add(ImportFailure(name, e.message ?: "导入失败"))
        }
    }
}
