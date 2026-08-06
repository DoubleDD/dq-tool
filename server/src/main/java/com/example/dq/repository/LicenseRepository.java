package com.example.dq.repository;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/** 授权信息(单行,id 固定 1) */
@Repository
public class LicenseRepository {

    /** code_enc 已加密存储,读取后由调用方解密 */
    public record LicenseRow(String codeEnc, String customer, LocalDate expiresAt) {
    }

    private final JdbcTemplate jdbc;

    public LicenseRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<LicenseRow> get() {
        List<LicenseRow> list = jdbc.query("SELECT * FROM license_info WHERE id=1",
                (rs, i) -> {
                    java.sql.Date d = rs.getDate("expires_at");
                    return new LicenseRow(rs.getString("code_enc"), rs.getString("customer"),
                            d == null ? null : d.toLocalDate());
                });
        return list.stream().findFirst();
    }

    /** expiresAt 为 null 表示永久授权,expires_at 列存 NULL */
    public void upsert(String codeEnc, String customer, LocalDate expiresAt) {
        java.sql.Date d = expiresAt == null ? null : java.sql.Date.valueOf(expiresAt);
        int n = jdbc.update("UPDATE license_info SET code_enc=?, customer=?, expires_at=?, activated_at=CURRENT_TIMESTAMP WHERE id=1",
                codeEnc, customer, d);
        if (n == 0) {
            jdbc.update("INSERT INTO license_info(id, code_enc, customer, expires_at) VALUES (1,?,?,?)",
                    codeEnc, customer, d);
        }
    }
}
