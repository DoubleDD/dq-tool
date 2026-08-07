package com.example.dq.spike

import com.example.dq.model.DataSourceRequest
import com.example.dq.model.NullRule
import com.example.dq.model.ScanRequest
import jakarta.validation.Validation
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import tools.jackson.databind.json.JsonMapper
import tools.jackson.module.kotlin.KotlinModule

/**
 * 共享内核拆分的前置验证(spike):
 * server web 层用 Jackson 3(tools.jackson)+ hibernate-validator,内核模型是 Kotlin data class,
 * 必须确认两者能直接消费,否则 controller 边缘要保留 Java record DTO 映射。
 *
 * - Jackson 3 + jackson-module-kotlin(tools.jackson.module):Kotlin data class 序列化/反序列化
 * - hibernate-validator:@field: 注解在 Kotlin data class 上生效
 */
class Jackson3KotlinSpikeTest {

    private val mapper: JsonMapper = JsonMapper.builder()
        .addModule(KotlinModule.Builder().build())
        .build()

    @Test
    fun `Jackson3 序列化 Kotlin data class`() {
        val req = ScanRequest(1L, "public", null, listOf("t1", "t2"), true, listOf(NullRule("status", listOf("0"))), null)
        val json = mapper.writeValueAsString(req)
        val back = mapper.readValue(json, ScanRequest::class.java)
        assertEquals(req, back)
    }

    @Test
    fun `Jackson3 反序列化缺失字段走可空与默认值`() {
        // 前端只传必填字段:可空字段为 null,forceFull 落默认值 false
        val req = mapper.readValue("""{"datasourceId":1,"schema":"public"}""", ScanRequest::class.java)
        assertEquals(1L, req.datasourceId)
        assertEquals(false, req.forceFull)
        assertEquals(null, req.tables)
    }

    @Test
    fun `Jackson3 反序列化可空字段缺失时为 null,交由校验兜底`() {
        // 缺 jdbcUrl:与 Java record 行为一致,反序列化为 null,@field:NotBlank 在校验阶段拦截
        val req = mapper.readValue("""{"name":"x"}""", DataSourceRequest::class.java)
        assertEquals(null, req.jdbcUrl)
        val violations = Validation.buildDefaultValidatorFactory().validator.validate(req)
        assertEquals(setOf("jdbcUrl"), violations.map { it.propertyPath.toString() }.toSet())
    }

    @Test
    fun `hibernate-validator 校验 Kotlin data class 的 field 注解`() {
        val validator = Validation.buildDefaultValidatorFactory().validator

        val bad = DataSourceRequest("", null, "u", "p", null, null)
        val violations = validator.validate(bad)
        assertEquals(2, violations.size)
        assertEquals(setOf("name", "jdbcUrl"), violations.map { it.propertyPath.toString() }.toSet())

        val good = DataSourceRequest("n", "jdbc:mysql://localhost/db", "u", "p", null, null)
        assertEquals(0, validator.validate(good).size)
    }
}
