package com.example.dq.service

import com.example.dq.config.AppConfig
import com.example.dq.config.AiDefaults
import com.example.dq.model.AiConfigRequest
import com.example.dq.repository.AiConfigRepository
import com.example.dq.repository.Jdbc
import com.example.dq.repository.SchemaInit
import com.example.dq.util.CryptoUtil
import org.h2.jdbcx.JdbcDataSource
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.nio.file.Files

/** AI 配置的测试合并逻辑(resolveConfig):请求参数 > 已存配置 > 配置文件默认值 */
class AiConfigServiceTest {

    private lateinit var service: AiConfigService
    private lateinit var crypto: CryptoUtil
    private lateinit var repo: AiConfigRepository

    private val config = AppConfig(
        dataDir = Files.createTempDirectory("dq-ai-config-test"),
        ai = AiDefaults(apiKey = "default-key", baseUrl = "http://default/v1", model = "default-model"),
    )

    @BeforeEach
    fun setUp() {
        val ds = JdbcDataSource()
        ds.setURL("jdbc:h2:mem:ai_config_${System.nanoTime()};DB_CLOSE_DELAY=-1")
        ds.user = "sa"
        SchemaInit.run(ds)
        crypto = CryptoUtil(config)
        repo = AiConfigRepository(Jdbc(ds))
        // 测试只走 resolveConfig,不真正调用大模型接口
        service = AiConfigService(repo, crypto, config, AiService())
    }

    @Test
    fun `请求参数优先,且去除尾斜杠`() {
        val cfg = service.resolveConfig(
            AiConfigRequest("http://host:8080/v1/", "req-key", "req-model"))
        assertEquals("http://host:8080/v1", cfg!!.baseUrl)
        assertEquals("req-key", cfg.apiKey)
        assertEquals("req-model", cfg.model)
    }

    @Test
    fun `请求留空时回落到已存配置`() {
        service.save(AiConfigRequest("http://saved/v1", "saved-key", "saved-model"))
        val cfg = service.resolveConfig(AiConfigRequest(null, null, null))
        assertEquals("http://saved/v1", cfg!!.baseUrl)
        // 已存 key 是加密存储,解密后回落
        assertEquals("saved-key", cfg.apiKey)
        assertEquals("saved-model", cfg.model)
    }

    @Test
    fun `未保存且请求留空时回落到配置文件默认值`() {
        val cfg = service.resolveConfig(AiConfigRequest(null, null, null))
        assertEquals("http://default/v1", cfg!!.baseUrl)
        assertEquals("default-key", cfg.apiKey)
        assertEquals("default-model", cfg.model)
    }

    @Test
    fun `请求部分留空时逐项回落`() {
        service.save(AiConfigRequest("http://saved/v1", "saved-key", "saved-model"))
        // 只填 baseUrl,key 与 model 回落已存配置
        val cfg = service.resolveConfig(AiConfigRequest("http://new/v1", null, null))
        assertEquals("http://new/v1", cfg!!.baseUrl)
        assertEquals("saved-key", cfg.apiKey)
        assertEquals("saved-model", cfg.model)
    }

    @Test
    fun `仍不完整时返回 null`() {
        val emptyConfig = AppConfig(dataDir = Files.createTempDirectory("dq-ai-config-empty"), ai = AiDefaults())
        val emptyService = AiConfigService(AiConfigRepository(Jdbc(memDs())), CryptoUtil(emptyConfig), emptyConfig, AiService())
        assertNull(emptyService.resolveConfig(AiConfigRequest(null, null, null)))
        assertNull(emptyService.resolveConfig(AiConfigRequest("http://x/v1", null, "m")))
    }

    private fun memDs(): JdbcDataSource {
        val ds = JdbcDataSource()
        ds.setURL("jdbc:h2:mem:ai_config_empty_${System.nanoTime()};DB_CLOSE_DELAY=-1")
        ds.user = "sa"
        SchemaInit.run(ds)
        return ds
    }
}
