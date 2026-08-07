package com.example.dq.model

/** 未激活或授权已过期时抛出,由 server web 层全局异常处理转 401 */
class LicenseRequiredException(message: String) : RuntimeException(message)
