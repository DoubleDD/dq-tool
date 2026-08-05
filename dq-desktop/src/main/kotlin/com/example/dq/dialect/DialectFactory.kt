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
    }

    private fun register(dialect: DbDialect) {
        dialects[dialect.type()] = dialect
    }

    fun get(type: DbType): DbDialect {
        return dialects[type] ?: throw IllegalArgumentException("不支持的数据库类型: $type")
    }
}
