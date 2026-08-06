package com.example.dq.repository

import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.sql.Statement
import javax.sql.DataSource

/**
 * 极简 JDBC 薄封装,替代 Spring JdbcTemplate。
 * 所有 SQL 仍为手写字样,参数按位置绑定;需要精细控制(可空类型、动态 SQL)时用 [withStatement]。
 */
class Jdbc(private val ds: DataSource) {

    /** 查询多行 */
    fun <T> query(sql: String, vararg args: Any?, mapper: (ResultSet) -> T): List<T> =
        ds.connection.use { conn ->
            conn.prepareStatement(sql).use { ps ->
                bind(ps, args)
                ps.executeQuery().use { rs ->
                    val list = ArrayList<T>()
                    while (rs.next()) list.add(mapper(rs))
                    list
                }
            }
        }

    /** 查询单行,无结果返回 null */
    fun <T> queryOne(sql: String, vararg args: Any?, mapper: (ResultSet) -> T): T? =
        query(sql, *args, mapper = mapper).firstOrNull()

    /** 执行 DML,返回影响行数 */
    fun update(sql: String, vararg args: Any?): Int =
        ds.connection.use { conn ->
            conn.prepareStatement(sql).use { ps ->
                bind(ps, args)
                ps.executeUpdate()
            }
        }

    /** 插入并返回自增主键 */
    fun insert(sql: String, vararg args: Any?): Long =
        ds.connection.use { conn ->
            conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS).use { ps ->
                bind(ps, args)
                ps.executeUpdate()
                ps.generatedKeys.use { rs ->
                    check(rs.next()) { "插入未返回自增主键" }
                    rs.getLong(1)
                }
            }
        }

    /** 需要自行拼 PreparedStatement 的更新(可空 bigint、动态 SQL、KeyHolder 等场景) */
    fun withStatement(prepare: (Connection) -> PreparedStatement, action: (PreparedStatement) -> Unit) {
        ds.connection.use { conn ->
            prepare(conn).use { ps -> action(ps) }
        }
    }

    /** 事务:同一连接内执行,异常回滚,正常提交 */
    fun <T> tx(block: (Connection) -> T): T {
        ds.connection.use { conn ->
            conn.autoCommit = false
            try {
                val result = block(conn)
                conn.commit()
                return result
            } catch (e: Throwable) {
                conn.rollback()
                throw e
            }
        }
    }

    private fun bind(ps: PreparedStatement, args: Array<out Any?>) {
        args.forEachIndexed { i, arg ->
            if (arg == null) ps.setObject(i + 1, null) else ps.setObject(i + 1, arg)
        }
    }
}
