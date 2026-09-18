package com.example.dq.dialect

import com.example.dq.model.DbType

import java.util.EnumMap

/** 按数据库类型返回对应方言 */
object DialectFactory {

    private val dialects: MutableMap<DbType, DbDialect> = EnumMap(DbType::class.java)

    init {
        register(MySqlDialect())
        register(PostgresDialect())
        register(DmDialect())
        register(KingbaseDialect())
        register(OceanBaseDialect())
        register(SqlServerDialect())
        register(OracleDialect())
        register(HighGoDialect())
    }

    private fun register(dialect: DbDialect) {
        // 统一包元数据读拦截器:读到元数据且本地 meta_* 未缓存时兜底回填(无上下文连接纯透传)
        dialects[dialect.type()] = MetaReadCachingDialect(dialect)
    }

    fun get(type: DbType): DbDialect {
        return dialects[type] ?: throw IllegalArgumentException("不支持的数据库类型: $type")
    }
}
