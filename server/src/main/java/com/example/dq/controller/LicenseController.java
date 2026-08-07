package com.example.dq.controller;

import com.example.dq.model.LicenseActivateRequest;
import com.example.dq.model.LicenseGenerateRequest;
import com.example.dq.service.LicenseService;
import com.example.dq.web.Validators;
import io.javalin.http.Context;

/** 授权码:查询状态 + 激活(不被授权拦截拦截);admin/* 为管理员实例(配置签发私钥)专属 */
public class LicenseController {

    private final LicenseService service;

    public LicenseController(LicenseService service) {
        this.service = service;
    }

    public void status(Context ctx) {
        ctx.json(service.status());
    }

    public void activate(Context ctx) {
        ctx.json(service.activate(ctx.bodyAsClass(LicenseActivateRequest.class).getCode()));
    }

    /** 授权码留档列表(含解密后的完整授权码,仅管理员) */
    public void adminList(Context ctx) {
        ctx.json(service.listLicenses());
    }

    /** 生成新授权码并留档(仅管理员) */
    public void adminGenerate(Context ctx) {
        LicenseGenerateRequest req = ctx.bodyAsClass(LicenseGenerateRequest.class);
        Validators.validate(req);
        ctx.json(service.generateLicense(req));
    }

    /** 删除留档记录(仅管理员;不影响已分发的授权码) */
    public void adminDelete(Context ctx) {
        service.deleteLicense(Long.parseLong(ctx.pathParam("id")));
        ctx.status(204);
    }
}
