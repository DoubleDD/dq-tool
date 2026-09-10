package com.example.dq.util

import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.sql.SQLException
import java.sql.SQLTransientConnectionException
import java.util.Locale

/**
 * 业务库连接失败判定与分类(元数据浏览/刷新失败降级本地缓存用)。
 *
 * 不依赖具体驱动类型:8 种数据库都把网络层异常包在 cause 链里(MySQL 的 CommunicationsException
 * → SocketTimeoutException/ConnectException,Oracle 的 SQLRecoverableException → IOException 等),
 * 因此沿 cause 链找 `java.net.*` 异常,叠加 SQLState 08/28(connection / authorization 标准类别)
 * 与常见英文错误文案兜底,即可跨方言生效;Oracle/SQL Server 的部分异常走 `getNextException` 链,一并遍历。
 *
 * 注意:网络断开的常见表现是「Hikari 池借出超时」——`SQLTransientConnectionException`
 * (Connection is not available) 且不带 cause(池内旧连接失活被清、新建连接又失败),所以该文案也计入
 * 网络不可达;理论上连接池被业务占满也会给出同样文案(本项目池容量 = worker+2,留有余量,概率极低)。
 */
object ConnectionFailureClassifier {

    /** 网络不可达/超时(换网络环境、VPN 断开、目标机不可达等) */
    const val UNREACHABLE = "UNREACHABLE"

    /** 认证失败(账号密码错误/权限不足) */
    const val AUTH = "AUTH"

    /** 其他连接级失败 */
    const val OTHER = "OTHER"

    /** 是否为连接级失败(决定是否降级读缓存);SQL 语法/对象不存在等非连接类异常不降级,避免掩盖真实错误 */
    fun isConnectionFailure(e: Throwable?): Boolean = anyThrowing(e) { t ->
        isUnreachableThrowable(t) || isAuthThrowable(t) ||
                t is SQLTransientConnectionException ||
                looksLikeUnreachable(text(t)) || looksLikeAuth(text(t))
    }

    /** 分类,落 `data_source.conn_kind` 供前端给出准确提示 */
    fun classify(e: Throwable?): String = when {
        anyThrowing(e) { isUnreachableThrowable(it) || looksLikeUnreachable(text(it)) } -> UNREACHABLE
        anyThrowing(e) { isAuthThrowable(it) || looksLikeAuth(text(it)) } -> AUTH
        else -> OTHER
    }

    /** 取最内层原因的可读描述(落 `data_source.conn_error` 展示用) */
    fun describe(e: Throwable?): String {
        val root = rootCause(e) ?: return ""
        val msg = root.message?.trim().orEmpty()
        val head = if (msg.isEmpty()) root.javaClass.simpleName else "${root.javaClass.simpleName}: $msg"
        return head.take(1000)
    }

    private fun isUnreachableThrowable(t: Throwable): Boolean =
        t is SocketTimeoutException || t is SocketException || t is UnknownHostException ||
                (t is SQLException && t.sqlState.orEmpty().startsWith("08"))

    private fun isAuthThrowable(t: Throwable): Boolean =
        t is SQLException && t.sqlState.orEmpty().startsWith("28")

    /** 广度遍历 cause 链与 SQLException.nextException 链(去环、限深) */
    private inline fun anyThrowing(e: Throwable?, pred: (Throwable) -> Boolean): Boolean {
        val seen = HashSet<Throwable>()
        val queue = ArrayDeque<Throwable>()
        if (e != null) queue.add(e)
        while (queue.isNotEmpty() && seen.size < 16) {
            val t = queue.removeFirst()
            if (!seen.add(t)) continue
            if (pred(t)) return true
            t.cause?.let { if (it !== t) queue.add(it) }
            if (t is SQLException) t.nextException?.let { queue.add(it) }
        }
        return false
    }

    private fun rootCause(e: Throwable?): Throwable? {
        var t = e
        val seen = HashSet<Throwable>()
        while (t?.cause != null && t.cause !== t && seen.add(t)) t = t.cause
        return t
    }

    private fun text(t: Throwable): String = t.message.orEmpty().lowercase(Locale.ROOT)

    private fun looksLikeUnreachable(m: String): Boolean = UNREACHABLE_HINTS.any { m.contains(it) }

    private fun looksLikeAuth(m: String): Boolean = AUTH_HINTS.any { m.contains(it) }

    private val UNREACHABLE_HINTS = listOf(
        "communications link failure",
        "connection refused",
        "connect timed out", "connection timed out", "read timed out",
        "the network adapter could not establish the connection",
        "io error: the network adapter",
        "no route to host", "unknown host", "host is down", "network is unreachable",
        "connection reset", "broken pipe", "socket closed", "socket is closed",
        "ora-12170", "ora-12541", "tns:could not", "tns:no listener",
        "connection is not available",
        "数据源连接失败", "无法连接", "连接超时", "网络不可达",
    )

    private val AUTH_HINTS = listOf(
        "access denied", "authentication failed", "password authentication failed",
        "invalid username/password", "invalid password", "login failed",
        "not authorized", "ora-01017", "认证失败", "用户名或密码",
    )
}
