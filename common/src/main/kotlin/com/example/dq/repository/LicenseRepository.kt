package com.example.dq.repository

import java.time.LocalDate

/** 授权信息(单行,id 固定 1) */
class LicenseRepository(private val jdbc: Jdbc) {

    /** code_enc 已加密存储,读取后由调用方解密 */
    data class LicenseRow(val codeEnc: String?, val customer: String?, val expiresAt: LocalDate?)

    fun get(): LicenseRow? =
        jdbc.queryOne("SELECT * FROM license_info WHERE id=1") { rs ->
            val d = rs.getDate("expires_at")
            LicenseRow(rs.getString("code_enc"), rs.getString("customer"), d?.toLocalDate())
        }

    /** expiresAt 为 null 表示永久授权,expires_at 列存 NULL */
    fun upsert(codeEnc: String, customer: String, expiresAt: LocalDate?) {
        val d = expiresAt?.let { java.sql.Date.valueOf(it) }
        val n = jdbc.update(
            "UPDATE license_info SET code_enc=?, customer=?, expires_at=?, activated_at=CURRENT_TIMESTAMP WHERE id=1",
            codeEnc, customer, d
        )
        if (n == 0) {
            jdbc.update("INSERT INTO license_info(id, code_enc, customer, expires_at) VALUES (1,?,?,?)",
                codeEnc, customer, d)
        }
    }
}
