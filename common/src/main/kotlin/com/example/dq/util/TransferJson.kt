package com.example.dq.util

import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import org.slf4j.LoggerFactory
import java.io.InputStream

/**
 * 三种导出文件(数据源/扫描记录/标记与描述)共用的 JSON 解析入口。
 * 导出统一写 UTF-8 无 BOM,但文件到客户手里可能被记事本另存(中文 Windows 默认 ANSI=GBK)、
 * 经 IM/邮件中转重编码等,因此 UTF-8 解析失败时按 GB18030(GBK 超集)兜底再解析一次;
 * 兜底避让必记 warn 日志(带堆栈),便于排查文件被转码的问题。
 * 解析忽略未知字段:v1 格式内只增不减,向后追加的字段不应拦截旧版本软件的导入。
 */
object TransferJson {

    private val log = LoggerFactory.getLogger(TransferJson::class.java)

    private val mapper: ObjectMapper = jacksonObjectMapper()
        .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)

    /** 读导出文件:UTF-8 优先,失败按 GB18030 兜底;两次都失败抛 UTF-8 那次的真实异常 */
    fun <T> read(input: InputStream, type: Class<T>): T {
        val bytes = input.readBytes()
        return try {
            mapper.readValue(bytes, type)
        } catch (e: Exception) {
            log.warn("导出文件 UTF-8 解析失败,按 GB18030 兜底重试(文件可能被记事本另存/中转转码)", e)
            try {
                mapper.readValue(String(bytes, charset("GB18030")), type)
            } catch (e2: Exception) {
                log.warn("导出文件 GB18030 兜底解析也失败: {}", e2.message)
                throw e
            }
        }
    }
}
