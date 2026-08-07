package com.example.dq.controller;

import com.example.dq.model.LicenseActivateRequest;
import com.example.dq.service.LicenseService;
import io.javalin.http.Context;

/** 授权码:查询状态 + 激活(不被授权拦截拦截) */
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
}
