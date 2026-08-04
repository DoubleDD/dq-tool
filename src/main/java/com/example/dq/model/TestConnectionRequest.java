package com.example.dq.model;

import jakarta.validation.constraints.NotBlank;

/** 测试连接请求:不需要名称,只要有连接参数 */
public record TestConnectionRequest(
        @NotBlank String jdbcUrl,
        String username,
        String password
) {
}
