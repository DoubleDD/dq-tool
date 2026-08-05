package com.example.dq.service

import com.example.dq.config.AppConfig
import com.example.dq.model.AiConfigRequest
import com.example.dq.model.AiConfigView
import com.example.dq.repository.AiConfigRepository
import com.example.dq.util.CryptoUtil

/**
 * AI 大模型接口配置:页面可视化配置,apiKey AES-GCM 加密存 H2。
 * 页面未配置的字段回落到 AppConfig 的 ai.* 默认配置(逐字段生效)。
 */
class AiConfigService(
    private val repository: AiConfigRepository,
    private val crypto: CryptoUtil,
    private val config: AppConfig,
) {

    /**
     * 解密后的可用配置。
     * usingDefault=true 表示用户未配置(任一字段回落到了 AppConfig 的默认值),
     * 此时对外不暴露任何默认配置细节,调用失败只提示用户自行配置。
     */
    data class Config(val baseUrl: String?, val apiKey: String?, val model: String?, val usingDefault: Boolean)

    /** 只回显用户自己的配置(H2),默认配置不回显、不暴露 */
    fun get(): AiConfigView =
        repository.get()
            ?.let { r -> AiConfigView(r.baseUrl, r.model, !r.apiKeyEnc.isNullOrEmpty()) }
            ?: AiConfigView(null, null, false)

    /** apiKey 为空串/null 时保留已存 key */
    fun save(req: AiConfigRequest) {
        var apiKeyEnc = repository.get()?.apiKeyEnc
        if (!req.apiKey.isNullOrBlank()) {
            apiKeyEnc = crypto.encrypt(req.apiKey.trim())
        }
        // 仓储层参数为非空 String,空值统一落空串;读取侧按 isNullOrBlank 判定,与 Java 版的 null 语义等价
        repository.upsert(trim(req.baseUrl) ?: "", apiKeyEnc ?: "", trim(req.model) ?: "")
    }

    /** 取合并默认配置后的可用配置;合并后仍不完整时抛异常,不泄露默认值 */
    fun requireConfig(): Config {
        val effective = effectiveConfig()
        val baseUrl = effective.baseUrl
        val model = effective.model
        val apiKey = effective.apiKey
        if (baseUrl.isNullOrBlank() || model.isNullOrBlank() || apiKey.isNullOrBlank()) {
            throw IllegalStateException("请先在「AI 配置」中填写完整的大模型接口信息(接口地址 / API Key / 模型)")
        }
        return Config(baseUrl.replace(Regex("/+$"), ""), apiKey, model, effective.usingDefault)
    }

    /** 逐字段合并:页面配置(H2)优先,空字段回落到 AppConfig 的 ai.* 默认值 */
    private fun effectiveConfig(): Config {
        val row = repository.get()
        var baseUrl = row?.let { trim(it.baseUrl) }
        var model = row?.let { trim(it.model) }
        var apiKey = if (row != null && !row.apiKeyEnc.isNullOrEmpty()) crypto.decrypt(row.apiKeyEnc) else null
        var usingDefault = false
        if (baseUrl.isNullOrBlank()) {
            baseUrl = trim(config.ai.baseUrl)
            usingDefault = true
        }
        if (model.isNullOrBlank()) {
            model = trim(config.ai.model)
            usingDefault = true
        }
        if (apiKey.isNullOrBlank()) {
            apiKey = trim(config.ai.apiKey)
            usingDefault = true
        }
        return Config(baseUrl, apiKey, model, usingDefault)
    }

    private companion object {
        fun trim(s: String?): String? = s?.trim()
    }
}
