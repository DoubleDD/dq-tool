package com.example.dq.web;

/** 参数校验失败;继承 IllegalArgumentException,由 WebServer 的 400 异常映射统一处理 */
public class ValidationException extends IllegalArgumentException {

    public ValidationException(String message) {
        super(message);
    }
}
