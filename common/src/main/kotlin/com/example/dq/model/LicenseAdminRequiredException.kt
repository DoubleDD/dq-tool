package com.example.dq.model

/** 非管理员实例(未配置签发私钥)访问授权码管理接口时抛出,由 server web 层全局异常处理转 403 */
class LicenseAdminRequiredException(message: String) : RuntimeException(message)
