package com.example.dq.model;

import java.time.LocalDate;

/** 授权状态视图(只回传展示字段,不回传授权码本身) */
public record LicenseStatusView(
        boolean activated,
        boolean expired,
        String customer,
        LocalDate expiresAt,
        Long daysLeft
) {
    public static LicenseStatusView notActivated() {
        return new LicenseStatusView(false, false, null, null, null);
    }
}
