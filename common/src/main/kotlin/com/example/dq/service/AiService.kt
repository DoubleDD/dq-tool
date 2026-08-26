package com.example.dq.service

import com.example.dq.model.AiScene
import com.example.dq.model.ColumnMeta
import com.example.dq.model.TableStat
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import org.slf4j.LoggerFactory
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.time.LocalDateTime

/**
 * OpenAI 兼容大模型接口调用(/chat/completions)。
 * 表说明场景只发送表结构元数据,不涉及业务数据;
 * 扫描后自动打标场景在表无任何注释/描述时会发送抽样业务数据(前 20 列、100 行、单元格截断 100 字符)。
 * 调用成功后解析响应里的 usage 并回调 [usageRecorder](Token/费用统计),解析失败不影响主流程。
 */
class AiService(private val usageRecorder: UsageRecorder? = null) {

    /** 用量上报回调(AiUsageService 注入);统计落库失败由实现方自行吞掉,不干扰调用主流程 */
    fun interface UsageRecorder {
        fun record(scene: AiScene, model: String, promptTokens: Long, completionTokens: Long,
                   totalTokens: Long, requestTime: LocalDateTime)
    }

    private val objectMapper = jacksonObjectMapper()

    private val client: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofMillis(10_000))
        .build()

    /** 生成一张表的说明文字(场景:表说明) */
    fun describeTable(config: AiConfigService.Config, table: TableStat, columns: List<ColumnMeta>): String =
        chat(config, SYSTEM_PROMPT, buildTablePrompt(table, columns), AiScene.TABLE_DOC)

    /**
     * 连通性测试:发一个最小 chat 请求(max_tokens=1),只校验接口可达与鉴权,不解析返回内容。
     * 供「AI 配置」的「测试连接」按钮使用(未保存的表单值也可测);失败统一包装为 IllegalStateException。
     * 成功且响应带 usage 时同样计入用量统计(场景:连通测试)。
     */
    fun test(config: AiConfigService.Config) {
        val body = mapOf(
            "model" to config.model,
            "temperature" to 0,
            "max_tokens" to 1,
            "messages" to listOf(mapOf("role" to "user", "content" to "ping")),
        )
        try {
            val request = HttpRequest.newBuilder()
                .uri(URI.create(config.baseUrl + "/chat/completions"))
                .timeout(Duration.ofMillis(30_000))
                .header("Authorization", "Bearer " + config.apiKey)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(body)))
                .build()
            val response = client.send(request, HttpResponse.BodyHandlers.ofString())
            if (response.statusCode() !in 200..299) {
                throw IllegalStateException(
                    "大模型接口测试失败:HTTP " + response.statusCode() + " " + abbreviate(response.body())
                )
            }
            recordUsage(AiScene.TEST, config.model.orEmpty(), response.body())
        } catch (e: IllegalStateException) {
            throw e
        } catch (e: Exception) {
            throw IllegalStateException("大模型接口测试失败:" + abbreviate(e.message), e)
        }
    }

    /** 通用对话调用(表说明与自动打标等共用,scene 标识调用场景用于用量统计);失败统一包装为 IllegalStateException */
    fun chat(config: AiConfigService.Config, systemPrompt: String, userPrompt: String, scene: AiScene): String {
        val body = mapOf(
            "model" to config.model,
            "temperature" to 0.3,
            "messages" to listOf(
                mapOf("role" to "system", "content" to systemPrompt),
                mapOf("role" to "user", "content" to userPrompt),
            ),
        )
        val resp: Map<*, *>
        val bodyText: String
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
            bodyText = response.body()
            resp = objectMapper.readValue(bodyText, Map::class.java)
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
            recordUsage(scene, config.model.orEmpty(), bodyText)
            return content
        } catch (e: IllegalStateException) {
            throw e
        } catch (e: Exception) {
            throw IllegalStateException("大模型响应解析失败:" + abbreviate(resp.toString()), e)
        }
    }

    /** 解析响应里的 usage 并上报统计;响应无 usage(部分兼容接口)或上报失败均静默忽略 */
    private fun recordUsage(scene: AiScene, model: String, bodyText: String) {
        val recorder = usageRecorder ?: return
        try {
            val usage = objectMapper.readValue(bodyText, Map::class.java)["usage"] as? Map<*, *> ?: return
            val prompt = (usage["prompt_tokens"] as? Number)?.toLong() ?: 0L
            val completion = (usage["completion_tokens"] as? Number)?.toLong() ?: 0L
            val total = (usage["total_tokens"] as? Number)?.toLong() ?: (prompt + completion)
            recorder.record(scene, model, prompt, completion, total, LocalDateTime.now())
        } catch (e: Exception) {
            log.warn("AI 用量统计上报失败,忽略: {}", e.message)
        }
    }

    companion object {
        private val log = LoggerFactory.getLogger(AiService::class.java)

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
