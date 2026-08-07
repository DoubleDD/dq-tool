package com.example.dq.repository

import com.example.dq.model.LicenseRecord
import java.sql.ResultSet

/** 授权码签发留档(license_record 表);只读本地 H2,不连业务库 */
class LicenseRecordRepository(private val jdbc: Jdbc) {

    private val mapper: (ResultSet) -> LicenseRecord = { rs ->
        val expires = rs.getDate("expires_at")
        val created = rs.getTimestamp("created_at")
        LicenseRecord(
            id = rs.getLong("id"),
            appVersion = rs.getString("app_version"),
            customer = rs.getString("customer"),
            expiresAt = expires?.toLocalDate(),
            serverUrl = rs.getString("server_url"),
            username = rs.getString("username"),
            sid = rs.getString("sid"),
            issuedAt = rs.getLong("issued_at"),
            codeEnc = rs.getString("code_enc"),
            createdAt = created?.toLocalDateTime(),
        )
    }

    /** 全部留档,新签发的在前 */
    fun findAll(): List<LicenseRecord> =
        jdbc.query("SELECT * FROM license_record ORDER BY id DESC", mapper = mapper)

    fun insert(record: LicenseRecord): Long =
        jdbc.insert(
            "INSERT INTO license_record(app_version, customer, expires_at, server_url, username, sid, issued_at, code_enc) " +
                "VALUES (?,?,?,?,?,?,?,?)",
            record.appVersion, record.customer,
            record.expiresAt?.let { java.sql.Date.valueOf(it) },
            record.serverUrl, record.username, record.sid, record.issuedAt, record.codeEnc)

    fun delete(id: Long) {
        jdbc.update("DELETE FROM license_record WHERE id=?", id)
    }
}
