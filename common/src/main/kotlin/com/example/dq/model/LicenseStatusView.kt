package com.example.dq.model

import java.time.LocalDate

/** 授权状态视图(只回传展示字段,不回传授权码本身) */
data class LicenseStatusView(
    val activated: Boolean,
    val expired: Boolean,
    val customer: String?,
    val expiresAt: LocalDate?,
    val daysLeft: Long?,
) {
    companion object {
        @JvmStatic
        fun notActivated() = LicenseStatusView(false, false, null, null, null)
    }
}
