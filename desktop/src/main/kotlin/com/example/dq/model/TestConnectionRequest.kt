package com.example.dq.model

/** 测试连接请求:不需要名称,只要有连接参数 */
data class TestConnectionRequest(
    val jdbcUrl: String?,
    val username: String?,
    val password: String?
)
