package com.example.dq.model

/** 当前授权码未包含某受控功能(logs/license_admin)时抛出,由 server web 层全局异常处理转 403 */
class LicenseFeatureRequiredException(message: String) : RuntimeException(message)
