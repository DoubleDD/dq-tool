package com.example.dq.model;

/** 未激活或授权已过期时抛出,由全局异常处理转 401 */
public class LicenseRequiredException extends RuntimeException {

    public LicenseRequiredException(String message) {
        super(message);
    }
}
