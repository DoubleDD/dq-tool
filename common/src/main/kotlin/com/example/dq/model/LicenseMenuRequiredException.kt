package com.example.dq.model

/** 授权码未开放该菜单(前端隐藏入口、后端接口返回 403) */
class LicenseMenuRequiredException(message: String) : RuntimeException(message)
