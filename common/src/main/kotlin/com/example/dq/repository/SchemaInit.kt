package com.example.dq.repository

import org.flywaydb.core.Flyway
import org.slf4j.LoggerFactory
import javax.sql.DataSource

/**
 * H2 库表迁移(Flyway):迁移脚本在 classpath:db/migration。
 * 存量老库(有表无 flyway_schema_history)baselineOnMigrate 自动基线到 V1,再执行 V2 补齐
 * license_info 与后加的列;新库从 V1 全量执行。
 * 规则:结构变更一律新增 V{n}__描述.sql,已发布的迁移文件禁止修改,不允许破坏性变更。
 */
object SchemaInit {

    private val log = LoggerFactory.getLogger(SchemaInit::class.java)

    fun run(ds: DataSource) {
        val result = Flyway.configure()
            .dataSource(ds)
            .baselineOnMigrate(true)
            .baselineVersion("1")
            .load()
            .migrate()
        log.info("H2 库表迁移完成:执行 {} 个迁移,当前版本 {}", result.migrationsExecuted, result.targetSchemaVersion)
    }
}
