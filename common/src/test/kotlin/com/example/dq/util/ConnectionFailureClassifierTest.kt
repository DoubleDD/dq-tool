package com.example.dq.util

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.sql.SQLException
import java.sql.SQLTransientConnectionException

/** 连接失败判定与分类:跨驱动 cause 链、SQLState 08/28、Hikari 池借出超时文案 */
class ConnectionFailureClassifierTest {

    @Test
    fun `cause 链里的网络异常判为不可达`() {
        val mysql = SQLException("Communications link failure", ConnectException("Connection refused"))
        assertThat(ConnectionFailureClassifier.isConnectionFailure(mysql)).isTrue()
        assertThat(ConnectionFailureClassifier.classify(mysql))
            .isEqualTo(ConnectionFailureClassifier.UNREACHABLE)
    }

    @Test
    fun `深层嵌套的 cause 也能识别`() {
        val deep = RuntimeException(
            "computeIfAbsent 包装",
            IllegalStateException(
                "数据源连接失败: 1",
                SQLException("Communications link failure", SocketTimeoutException("Read timed out"))
            )
        )
        assertThat(ConnectionFailureClassifier.isConnectionFailure(deep)).isTrue()
        assertThat(ConnectionFailureClassifier.classify(deep))
            .isEqualTo(ConnectionFailureClassifier.UNREACHABLE)
    }

    @Test
    fun `SQLState 08 视为连接失败且不可达`() {
        val e = SQLException("IO Error", "08001")
        assertThat(ConnectionFailureClassifier.isConnectionFailure(e)).isTrue()
        assertThat(ConnectionFailureClassifier.classify(e)).isEqualTo(ConnectionFailureClassifier.UNREACHABLE)
    }

    @Test
    fun `SQLState 28 归为认证失败`() {
        val e = SQLException("Access denied for user 'root'", "28000")
        assertThat(ConnectionFailureClassifier.isConnectionFailure(e)).isTrue()
        assertThat(ConnectionFailureClassifier.classify(e)).isEqualTo(ConnectionFailureClassifier.AUTH)
    }

    @Test
    fun `Hikari 池借出超时按不可达处理`() {
        val e = SQLTransientConnectionException("Connection is not available, request timed out after 30000ms.")
        assertThat(ConnectionFailureClassifier.isConnectionFailure(e)).isTrue()
        assertThat(ConnectionFailureClassifier.classify(e)).isEqualTo(ConnectionFailureClassifier.UNREACHABLE)
    }

    @Test
    fun `未知主机判为不可达`() {
        val e = UnknownHostException("db.internal")
        assertThat(ConnectionFailureClassifier.classify(e)).isEqualTo(ConnectionFailureClassifier.UNREACHABLE)
    }

    @Test
    fun `普通 SQL 错误不降级`() {
        val e = SQLException("Table 'x' doesn't exist", "42S02")
        assertThat(ConnectionFailureClassifier.isConnectionFailure(e)).isFalse()
        assertThat(ConnectionFailureClassifier.classify(e)).isEqualTo(ConnectionFailureClassifier.OTHER)
    }

    @Test
    fun `描述取最内层原因`() {
        val e = SQLException("Communications link failure", SocketTimeoutException("Read timed out"))
        assertThat(ConnectionFailureClassifier.describe(e))
            .contains("SocketTimeoutException")
            .contains("Read timed out")
    }
}
