package com.example.dq.util

import org.slf4j.LoggerFactory
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import java.sql.CallableStatement
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.sql.SQLException
import java.sql.Statement

/**
 * 业务库 SQL 日志代理:在 DataSourceService.getConnection 出口统一包装 Connection,
 * 拦截所有 execute 类调用并把完整 SQL(含 PreparedStatement 参数)打到独立 logger
 * "com.example.dq.sql"。一处实现覆盖 dialect 元数据/分段规划/统计 SQL/采样 SQL 全部执行点;
 * 本地 H2(repository 包)不走该出口,不受影响。
 *
 * 用法:DataSourceService.getConnection 返回 SqlLogConnection.wrap(真实连接)。
 * 包装是纯转发(close 归还连接池、registerStatement 的 cancel 等语义不变)。
 */
object SqlLogConnection {

    private val log = LoggerFactory.getLogger("com.example.dq.sql")

    /** setFetchSize/setQueryTimeout 等控制方法首参也是 Int,必须排除,避免误记为绑定参数 */
    private val SET_CONTROL_METHODS = setOf(
        "setFetchSize", "setFetchDirection", "setQueryTimeout", "setMaxRows",
        "setMaxFieldSize", "setEscapeProcessing", "setPoolable", "setCursorName",
        "setAutoCommit", "setReadOnly", "setTransactionIsolation", "setCatalog",
        "setSchema", "setNetworkTimeout", "setHoldability", "setTypeMap",
        "setClientInfo", "setSavepoint", "setWarnings"
    )

    /** 流/大对象类参数(首参也是 Int,但值不可序列化成日志):渲染为占位符 */
    private val SET_LOBS = setOf(
        "setCharacterStream", "setAsciiStream", "setBinaryStream",
        "setBlob", "setClob", "setNClob", "setArray", "setRef", "setRowId", "setSQLXML"
    )

    private val EXECUTE_METHODS = setOf(
        "executeQuery", "executeUpdate", "execute", "executeLargeUpdate",
        "executeBatch", "executeLargeBatch"
    )

    fun wrap(conn: Connection): Connection {
        val cl = conn.javaClass.classLoader ?: SqlLogConnection::class.java.classLoader
        return Proxy.newProxyInstance(cl, arrayOf(Connection::class.java), ConnectionHandler(conn)) as Connection
    }

    private class ConnectionHandler(private val target: Connection) : InvocationHandler {
        override fun invoke(proxy: Any, method: Method, args: Array<out Any>?): Any? {
            val name = method.name
            val result = method.invoke(target, *argsOrEmpty(args))
            if (name == "close") {
                return result
            }
            // createStatement / prepareStatement / prepareCall 返回的 Statement 也要包一层打日志;
            // prepareStatement/prepareCall 的 SQL 模板一并传入,日志才能打出完整语句
            if (result is Statement) {
                val sql = if (args != null && args.isNotEmpty() && args[0] is String) args[0] as String else null
                return wrapStatement(result, sql)
            }
            return result
        }
    }

    private fun wrapStatement(stmt: Statement, sql: String? = null): Statement {
        val cl = stmt.javaClass.classLoader ?: SqlLogConnection::class.java.classLoader
        val iface = when (stmt) {
            is CallableStatement -> CallableStatement::class.java
            is PreparedStatement -> PreparedStatement::class.java
            else -> Statement::class.java
        }
        return Proxy.newProxyInstance(cl, arrayOf(iface), StatementHandler(stmt, sql)) as Statement
    }

    private class StatementHandler(
        private val target: Statement,
        /** prepareStatement/prepareCall 的 SQL 模板;Statement 直执行时为 null(用 execute 参数) */
        private val template: String?,
    ) : InvocationHandler {
        /** PreparedStatement 的绑定参数:参数索引(1 起) -> 渲染后的字面量 */
        private val params = HashMap<Int, String>()

        override fun invoke(proxy: Any, method: Method, args: Array<out Any>?): Any? {
            val name = method.name
            // PreparedStatement 参数绑定:setXxx(索引, 值);setNull(索引, 类型) 无绑定值,固定渲染 NULL;
            // 流/大对象类参数渲染为占位符,避免打出对象地址
            if (name.startsWith("set") && name !in SET_CONTROL_METHODS
                && args != null && args.isNotEmpty() && args[0] is Int) {
                val result = method.invoke(target, *argsOrEmpty(args))
                val idx = args[0] as Int
                val rendered: String = when {
                    name == "setNull" -> "NULL"
                    name in SET_LOBS -> "<lob>"  // 流/大对象无法序列化,渲染占位符
                    else -> render(args.getOrNull(1))
                }
                params[idx] = rendered
                return result
            }
            // 执行类方法:打日志(执行后清空参数,避免 PreparedStatement 复用时的陈旧参数误报)
            if (name in EXECUTE_METHODS) {
                val sql = if (name == "executeBatch" || name == "executeLargeBatch")
                    "BATCH(" + target.javaClass.simpleName + ")"
                else sqlText(args)
                logSql(sql, params)
                params.clear()
                return method.invoke(target, *argsOrEmpty(args))
            }
            return method.invoke(target, *argsOrEmpty(args))
        }

        /** executeQuery(sql)/executeQuery() 两种形态:带参时取 args[0](Statement),否则用 prepareStatement 模板 */
        private fun sqlText(args: Array<out Any>?): String {
            if (args != null && args.isNotEmpty() && args[0] is String) {
                return args[0] as String
            }
            return template ?: "PREPARED"
        }

        private fun logSql(sql: String, params: Map<Int, String>) {
            if (params.isEmpty()) {
                log.info("SQL: {}", sql)
            } else {
                val sorted = params.toSortedMap()
                val rendered = sorted.entries.joinToString(", ") { (k, v) -> "$k=$v" }
                log.info("SQL: {}  [参数: {}]", sql, rendered)
            }
        }
    }

    private fun argsOrEmpty(args: Array<out Any>?): Array<out Any> = args ?: emptyArray()

    private fun render(value: Any?): String {
        return when (value) {
            null -> "NULL"
            is String -> "'" + value.replace("'", "''") + "'"
            is java.util.Date -> "'" + value.toString() + "'"
            is ByteArray -> "0x" + value.joinToString("") { "%02x".format(it) }
            else -> value.toString()
        }
    }
}
