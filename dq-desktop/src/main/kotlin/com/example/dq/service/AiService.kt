package com.example.dq.service

import com.example.dq.model.ColumnMeta
import com.example.dq.model.TableStat
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/** OpenAI 兼容大模型接口调用(/chat/completions),只发送表结构元数据,不涉及业务数据 */
class AiService {

    private val objectMapper = jacksonObjectMapper()

    private val client: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofMillis(10_000))
        .build()

    /** 生成一张表的说明文字 */
    fun describeTable(config: AiConfigService.Config, table: TableStat, columns: List<ColumnMeta>): String =
        chat(config, SYSTEM_PROMPT, buildTablePrompt(table, columns))

    private fun chat(config: AiConfigService.Config, systemPrompt: String, userPrompt: String): String {
        val body = mapOf(
            "model" to config.model,
            "temperature" to 0.3,
            "messages" to listOf(
                mapOf("role" to "system", "content" to systemPrompt),
                mapOf("role" to "user", "content" to userPrompt),
            ),
        )
        val resp: Map<*, *>
        try {
            val request = HttpRequest.newBuilder()
                .uri(URI.create(config.baseUrl + "/chat/completions"))
                .timeout(Duration.ofMillis(120_000))
                .header("Authorization", "Bearer " + config.apiKey)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(body)))
                .build()
            val response = client.send(request, HttpResponse.BodyHandlers.ofString())
            if (response.statusCode() !in 200..299) {
                throw IllegalStateException(
                    "大模型接口调用失败:HTTP " + response.statusCode() + " " + abbreviate(response.body())
                )
            }
            resp = objectMapper.readValue(response.body(), Map::class.java)
        } catch (e: IllegalStateException) {
            throw e
        } catch (e: Exception) {
            throw IllegalStateException("大模型接口调用失败:" + abbreviate(e.message), e)
        }
        try {
            val choices = resp["choices"] as List<*>
            val message = (choices[0] as Map<*, *>)["message"] as Map<*, *>
            val content = (message["content"] as String).trim()
            if (content.isEmpty()) {
                throw IllegalStateException("大模型返回了空内容")
            }
            return content
        } catch (e: IllegalStateException) {
            throw e
        } catch (e: Exception) {
            throw IllegalStateException("大模型响应解析失败:" + abbreviate(resp.toString()), e)
        }
    }

    companion object {
        /** 发给模型的字段数上限,超出截断标注,控制 token 消耗 */
        const val MAX_PROMPT_COLUMNS = 100

        const val SYSTEM_PROMPT = "你是数据库专家。根据用户提供的表结构信息,用简洁中文概括该表的业务用途和存储的数据。" +
                "只输出一段 100 字以内的描述文字,不要罗列字段,不要使用列表和标题。"

        /** 拼表结构 prompt:表名/注释/引擎/约行数 + 字段清单,纯函数便于单测 */
        @JvmStatic
        fun buildTablePrompt(table: TableStat, columns: List<ColumnMeta>): String {
            val sb = StringBuilder()
            sb.append("表名:").append(table.name).append('\n')
            if (notBlank(table.comment)) {
                sb.append("表注释:").append(table.comment).append('\n')
            }
            if (notBlank(table.storageInfo)) {
                sb.append("引擎/表空间:").append(table.storageInfo).append('\n')
            }
            if (table.estRows != null) {
                sb.append("约行数:").append(table.estRows).append('\n')
            }
            sb.append("字段(共 ").append(columns.size).append(" 个):\n")
            val limit = minOf(columns.size, MAX_PROMPT_COLUMNS)
            for (i in 0 until limit) {
                val c = columns[i]
                sb.append("- ").append(c.name)
                if (notBlank(c.displayType)) {
                    sb.append(' ').append(c.displayType)
                }
                if (c.keyLabel().isNotEmpty()) {
                    sb.append(" [").append(c.keyLabel()).append(']')
                }
                if (!c.nullable) {
                    sb.append(" 非空")
                }
                if (notBlank(c.comment)) {
                    sb.append(" — ").append(c.comment)
                }
                sb.append('\n')
            }
            if (columns.size > limit) {
                sb.append("...(其余 ").append(columns.size - limit).append(" 个字段省略)\n")
            }
            sb.append("\n请描述这张表的业务用途。")
            return sb.toString()
        }

        private fun notBlank(s: String?): Boolean = !s.isNullOrBlank()

        private fun abbreviate(s: String?): String {
            if (s == null) {
                return ""
            }
            return if (s.length <= 300) s else s.substring(0, 300) + "..."
        }
    }
}
